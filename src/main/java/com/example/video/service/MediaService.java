package com.example.video.service;

import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class MediaService {
  private final RemoteRepository remote;
  private final UrlPolicy urls;

  public MediaService(RemoteRepository remote, UrlPolicy urls) {
    this.remote = remote;
    this.urls = urls;
  }

  public RemoteResource openMedia(String url, String referer, String range) throws IOException {
    List<String> refs =
        referer.isEmpty()
            ? List.of("")
            : new ArrayList<>(
                new LinkedHashSet<>(
                    List.of(
                        urls.origin(urls.validate(referer)) + "/", urls.validate(referer), "")));
    VideoException last = null;
    List<Map<String, Object>> attempts = new ArrayList<>();
    for (String ref : refs) {
      String actual = url;
      RemoteResource resource = null;
      String preview = "";
      try {
        resource = remote.fetch(url, ref, range);
        actual = resource.url();
        byte[] prefix = resource.body().readNBytes(512);
        preview = new String(prefix, StandardCharsets.UTF_8).stripLeading();
        if (preview.length() > 512) preview = preview.substring(0, 512);
        String type =
            resource.header("content-type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean mp4 =
            prefix.length >= 12
                && new String(prefix, 4, 4, StandardCharsets.US_ASCII).equals("ftyp");
        if (prefix.length == 0) throw new VideoException("來源回傳空檔案。");
        // HTML/XML/JSON prefix check intentionally remains disabled, as requested.
        if (!mp4 && !type.startsWith("video/"))
          throw new VideoException("來源內容未辨識為影片（回傳類型：" + (type.isEmpty() ? "未提供" : type) + "）。");
        Map<String, String> headers = new LinkedHashMap<>(resource.headers());
        headers.put("content-type", mp4 ? "video/mp4" : type);
        return new RemoteResource(
            actual,
            resource.status(),
            headers,
            new SequenceInputStream(new ByteArrayInputStream(prefix), resource.body()));
      } catch (Exception failure) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("url", url);
        attempt.put("actualURL", actual);
        attempt.put("referer", ref.isEmpty() ? "未傳送" : ref);
        attempt.put("error", failure.getMessage());
        if (resource != null) {
          attempt.put("status", resource.status());
          attempt.put(
              "contentType",
              resource.header("content-type").isEmpty() ? "未提供" : resource.header("content-type"));
          attempt.put("preview", preview);
          resource.close();
        }
        attempts.add(attempt);
        last =
            new VideoException(
                failure.getMessage(),
                Map.of("url", url, "actualURL", actual, "attempts", new ArrayList<>(attempts)));
      }
    }
    throw last;
  }

  public List<String> playlist(String url, String referer, int depth) throws IOException {
    if (depth > 3) throw new VideoException("播放清單層級過多。");
    RemoteResource response = remote.fetch(url, referer, "");
    String actual = response.url();
    String text = remote.readText(response, 1024 * 1024);
    if (!text.strip().startsWith("#EXTM3U")) throw new VideoException("不是有效的 HLS 播放清單。");
    List<String> lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    record Variant(String url, long rate) {}
    List<Variant> variants = new ArrayList<>();
    for (int i = 0; i < lines.size() - 1; i++)
      if (lines.get(i).startsWith("#EXT-X-STREAM-INF:") && !lines.get(i + 1).startsWith("#")) {
        Matcher bandwidth = Pattern.compile("BANDWIDTH=(\\d+)").matcher(lines.get(i));
        variants.add(
            new Variant(
                urls.resolve(lines.get(i + 1), actual),
                bandwidth.find() ? Long.parseLong(bandwidth.group(1)) : 0));
      }
    if (!variants.isEmpty()) {
      variants.sort(Comparator.comparingLong(Variant::rate).reversed());
      return playlist(variants.getFirst().url(), referer, depth + 1);
    }
    if (lines.stream()
        .anyMatch(
            l ->
                l.startsWith("#EXT-X-KEY:")
                    && !Pattern.compile("METHOD=NONE(?:,|$)").matcher(l).find()))
      throw new VideoException("此 HLS 已加密，無法合併下載。");
    if (!lines.contains("#EXT-X-ENDLIST")) throw new VideoException("此來源為直播，尚無完整影片可下載。");
    if (lines.stream().anyMatch(l -> l.matches("^#EXT-X-(MAP|BYTERANGE|DISCONTINUITY).*")))
      throw new VideoException("此 HLS 使用分段 MP4、位元組範圍或不連續串流，目前無法合併。");
    List<String> segments =
        lines.stream()
            .filter(l -> !l.startsWith("#"))
            .map(l -> urls.validate(urls.resolve(l, actual)))
            .toList();
    if (segments.isEmpty() || segments.size() > 1500)
      throw new VideoException("影片分段為空或超過 1500 段限制。");
    return segments;
  }

  public RemoteResource firstSegment(List<String> segments, String referer) throws IOException {
    return remote.fetch(segments.getFirst(), referer, "");
  }

  public void writeSegments(
      List<String> segments, String referer, RemoteResource first, OutputStream out)
      throws IOException {
    long total = 0;
    for (int i = 0; i < segments.size(); i++) {
      try (RemoteResource response = i == 0 ? first : remote.fetch(segments.get(i), referer, "")) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = response.body().read(buffer)) != -1) {
          total += n;
          if (total > 4L * 1024 * 1024 * 1024) throw new VideoException("影片超過 4 GB 限制。");
          out.write(buffer, 0, n);
          out.flush();
        }
      }
    }
  }
}
