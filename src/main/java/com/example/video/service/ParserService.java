package com.example.video.service;

import com.example.video.model.*;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.stereotype.Service;

/** Adapter priority and quirks follow the user-supplied parser.js verbatim in behavior. */
@Service
public class ParserService implements VideoParser {
  private final UrlPolicy urls;
  private final RemoteRepository remote;
  private final ObjectMapper json;
  private final BrowserService browser;

  private static Pattern pattern(String s) {
    return Pattern.compile(s, Pattern.DOTALL);
  }

  public ParserService(UrlPolicy urls, RemoteRepository remote, ObjectMapper json) {
    this(urls, remote, json, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ParserService(UrlPolicy urls, RemoteRepository remote, ObjectMapper json, BrowserService browser) {
    this.urls = urls;
    this.remote = remote;
    this.json = json;
    this.browser = browser;
  }

  public ParseResult inspect(String value) throws IOException {
    String url = urls.validate(value);
    remote.checkPublic(url);
    if (urls.path(url).matches("(?i).*\\.(mp4|m3u8)$"))
      return new ParseResult("影片", List.of(new Episode("主影片", List.of(url))), url);
    String actual;
    ParseResult result;
    try {
      RemoteResource resource = remote.fetch(url, "", "");
      actual = resource.url();
      String html = remote.readText(resource, 8 * 1024 * 1024);
      result = extract(html, actual);
    } catch (VideoException error) {
      // Do not retry policy failures, oversized pages, 404s or other permanent errors.
      String message = error.getMessage();
      if (browser == null || !browser.enabled()
          || !(message.startsWith("未找到影片來源")
              || message.startsWith("來源回應 403")
              || message.startsWith("來源回應 429"))) throw error;
      BrowserService.RenderedPage rendered = browser.fetchRenderedHtml(url);
      actual = rendered.url();
      try {
        result = extract(rendered.html(), actual);
      } catch (VideoException noSource) {
        if (!noSource.getMessage().startsWith("未找到影片來源") || rendered.mediaUrls().isEmpty())
          throw noSource;
        String title = Jsoup.parse(rendered.html()).title().split(" - ", -1)[0].strip();
        result = main(title.isEmpty() ? "影片" : title, rendered.mediaUrls());
      }
    }
    Episode first = result.episodes().getFirst();
    return new ParseResult(
        result.title(),
        List.of(new Episode(first.name(), List.of(first.urls().getFirst()))),
        actual);
  }

  private List<String> clean(List<String> values, String base) {
    Set<String> out = new LinkedHashSet<>();
    for (String value : values)
      if (value != null && !value.strip().isEmpty())
        try {
          out.add(urls.validate(urls.resolve(value.strip(), base)));
        } catch (RuntimeException ignored) {
        }
    return new ArrayList<>(out);
  }

  private ParseResult main(String title, List<String> found) {
    return new ParseResult(title, List.of(new Episode("主影片", found)), null);
  }

  // 新增：處理大於 36 進位 (Base62) 的加密轉換
  private String encodeBase62(int value) {
    String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    if (value == 0) return "0";
    StringBuilder sb = new StringBuilder();
    while (value > 0) {
      sb.insert(0, chars.charAt(value % 62));
      value /= 62;
    }
    return sb.toString();
  }

  public ParseResult extract(String html, String base) {
    Document doc = Jsoup.parse(html);
    Element titleNode = doc.selectFirst("title");
    String title = titleNode == null ? "影片" : titleNode.wholeText().split(" - ", -1)[0].strip();
    if (title.isEmpty()) title = "影片";

    // 1. videourls 適配器
    Matcher episodeMatch = Pattern.compile("videourls:\\s*(?=\\[)").matcher(html);
    if (episodeMatch.find())
      try {
        String tail = html.substring(episodeMatch.end());
        int depth = 0, end = 0;
        boolean inString = false, escape = false;
        for (int i = 0; i < tail.length(); i++) {
          char c = tail.charAt(i);
          if (inString) {
            if (escape) escape = false;
            else if (c == '\\') escape = true;
            else if (c == '"') inString = false;
          } else if (c == '"') inString = true;
          else if (c == '[') depth++;
          else if (c == ']' && --depth == 0) {
            end = i + 1;
            break;
          }
        }
        JsonNode groups = json.readTree(tail.substring(0, end));
        List<Episode> episodes = new ArrayList<>();
        for (int i = 0; i < groups.get(0).size(); i++) {
          List<String> candidates = new ArrayList<>();
          for (JsonNode group : groups)
            if (i < group.size()) {
              JsonNode u = group.get(i).get("url");
              candidates.add(u != null && u.isTextual() ? u.asText() : null);
            }
          List<String> found = clean(candidates, base);
          JsonNode name = groups.get(0).get(i).get("name");
          if (!found.isEmpty())
            episodes.add(
                new Episode("第 " + (name == null ? "undefined" : name.asText()) + " 集", found));
        }
        if (!episodes.isEmpty()) return new ParseResult(title, episodes, null);
      } catch (Exception ignored) {
      }

    // 2. 字串陣列 + 索引陣列混淆適配器
    try {
      List<List<String>> stringsList = new ArrayList<>();
      List<List<Integer>> indicesList = new ArrayList<>();
      Matcher arrays = pattern("\\[(.*?)\\]").matcher(html);
      while (arrays.find()) {
        String content = arrays.group(1);
        if (content.isEmpty()) continue;
        try {
          Matcher quotes = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*?)\"").matcher(content);
          List<String> strings = new ArrayList<>();
          while (quotes.find())
            strings.add(json.readValue("\"" + quotes.group(1) + "\"", String.class));
          if (strings.size() > 3) {
            stringsList.add(strings);
            continue;
          }
        } catch (Exception ignored) {
        }
        if (content.matches("[\\d\\s,]+")) {
          Matcher nums = Pattern.compile("\\b\\d+\\b").matcher(content);
          List<Integer> indices = new ArrayList<>();
          while (nums.find()) indices.add(Integer.parseInt(nums.group()));
          if (indices.size() > 3) indicesList.add(indices);
        }
      }
      for (List<String> strings : stringsList)
        for (List<Integer> indices : indicesList) {
          if (strings.size() != indices.size()) continue;
          try {
            StringBuilder a = new StringBuilder();
            for (int index : indices) if (index < strings.size()) a.append(strings.get(index));
            List<String> found = decodeCandidate(a.toString(), base);
            if (!found.isEmpty()) return main(title, found);
          } catch (Exception ignored) {
          }
          try {
            TreeMap<Integer, String> slots = new TreeMap<>();
            for (int i = 0; i < strings.size(); i++) slots.put(indices.get(i), strings.get(i));
            StringBuilder b = new StringBuilder();
            slots.values().forEach(b::append);
            List<String> found = decodeCandidate(b.toString(), base);
            if (!found.isEmpty()) return main(title, found);
          } catch (Exception ignored) {
          }
        }
    } catch (Exception ignored) {
    }

    // 3. 新增：Dean Edwards Packer (eval混淆) 適配器
    try {
      Matcher packers = pattern("eval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*d\\s*\\).*?\\}\\s*\\((.*?)\\)\\s*\\)").matcher(html);
      while (packers.find()) {
        String args = packers.group(1);
        Matcher argsMatch = pattern("(['\"])(.*?)\\1\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(['\"])(.*?)\\5\\.split\\(['\"]\\|['\"]\\)").matcher(args);

        if (argsMatch.find()) {
          String p = argsMatch.group(2).replace("\\'", "'").replace("\\\"", "\"");
          int a = Integer.parseInt(argsMatch.group(3)); // 進位制
          int c = Integer.parseInt(argsMatch.group(4)); // 字典大小
          String[] k = argsMatch.group(6).split("\\|");

          // 反向還原字典
          while (c-- > 0) {
            if (c < k.length && !k[c].isEmpty()) {
              String search = "\\b" + (a <= 36 ? Integer.toString(c, a) : encodeBase62(c)) + "\\b";
              p = p.replaceAll(search, Matcher.quoteReplacement(k[c]));
            }
          }

          // 從還原出的 JavaScript 明文中尋找影片網址
          List<String> unpackedCandidates = new ArrayList<>();
          Matcher unpackedUrls = Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>\\\\]*)?", Pattern.CASE_INSENSITIVE).matcher(p);
          while (unpackedUrls.find()) {
            unpackedCandidates.add(unpackedUrls.group());
          }

          List<String> found = clean(unpackedCandidates, base);
          if (!found.isEmpty()) return main(title, found);
        }
      }
    } catch (Exception ignored) {
    }

    // 4. HTML video / source 標籤
    List<String> candidates = new ArrayList<>();
    for (Element n : doc.select("video[src], video source[src]")) candidates.add(n.attr("src"));

    // 5. Open Graph 資訊
    Element typeNode = doc.selectFirst("meta[property=og:video:type]");
    String declared = typeNode == null ? "" : typeNode.attr("content").toLowerCase(Locale.ROOT);
    for (Element n : doc.select("meta[property=og:video],meta[property=og:video:url]"))
      try {
        String candidate = urls.resolve(n.attr("content"), base);
        boolean same = candidate.split("#", 2)[0].equals(base.split("#", 2)[0]);
        boolean media =
            urls.path(candidate).matches("(?i).*\\.(mp4|m3u8)$")
                || List.of("video/mp4", "application/vnd.apple.mpegurl", "application/x-mpegurl")
                    .contains(declared);
        if (!same && !declared.equals("text/html") && media) candidates.add(candidate);
      } catch (RuntimeException ignored) {
      }

    // 6. 全文備用搜尋
    if (candidates.isEmpty()) {
      Matcher fallback =
          Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>\\\\]*)?")
              .matcher(html.replace("\\/", "/"));
      while (fallback.find()) candidates.add(fallback.group());
    }

    List<String> found = clean(candidates, base);
    if (found.isEmpty()) throw new VideoException("未找到影片來源；這個網站需要新增播放器解析器，或需要登入／執行 JavaScript。");
    List<Episode> episodes = new ArrayList<>();
    for (int i = 0; i < found.size(); i++)
      episodes.add(new Episode("影片 " + (i + 1), List.of(found.get(i))));
    return new ParseResult(title, episodes, null);
  }

  private List<String> decodeCandidate(String input, String base) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < input.length(); ) {
      if (input.charAt(i) != '%') {
        out.append(input.charAt(i++));
        continue;
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      while (i < input.length() && input.charAt(i) == '%') {
        if (i + 2 >= input.length()) throw new IllegalArgumentException();
        int b = Integer.parseInt(input.substring(i + 1, i + 3), 16);
        bytes.write(b);
        i += 3;
      }
      try {
        out.append(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())));
      } catch (CharacterCodingException e) {
        throw new IllegalArgumentException(e);
      }
    }
    Matcher m =
        Pattern.compile("^(https?://.*\\.(?:mp4|m3u8))", Pattern.CASE_INSENSITIVE).matcher(out);
    return m.find() ? clean(List.of(m.group(1)), base) : List.of();
  }
}
