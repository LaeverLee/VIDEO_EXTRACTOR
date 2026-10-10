package com.example.video.controller;

import com.example.video.model.*;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.example.video.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class VideoController {
  private final VideoParser parser;
  private final MediaService media;
  private final UrlPolicy urls;
  private final ObjectMapper json;

  public VideoController(
      VideoParser parser, MediaService media, UrlPolicy urls, ObjectMapper json) {
    this.parser = parser;
    this.media = media;
    this.urls = urls;
    this.json = json;
  }

  @GetMapping("/")
  public ResponseEntity<byte[]> index() throws IOException {
    return ResponseEntity.ok()
        .header("Content-Type", "text/html; charset=utf-8")
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'"
                + " 'unsafe-inline'; media-src 'self' https: http:; img-src 'self' data:;"
                + " object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        .body(new ClassPathResource("static/index.html").getContentAsByteArray());
  }

  @PostMapping("/api/inspect")
  public ResponseEntity<?> inspect(HttpServletRequest request) throws IOException {
    String origin = request.getHeader("Origin"),
        own = request.getScheme() + "://" + request.getHeader("Host");
    if (origin != null && !origin.equals(own)) return failure("不允許跨網站請求。", null, 403);
    if (request.getContentLengthLong() > 12000) return failure("請求過大。", null, 413);
    byte[] body = request.getInputStream().readNBytes(12001);
    if (body.length > 12000) throw new VideoException("來源超過檔案大小限制。");
    JsonNode input;
    try {
      input = json.readTree(body);
    } catch (Exception e) {
      throw new VideoException("請輸入有效的網址。");
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(parser.inspect(input.path("url").isTextual() ? input.path("url").asText() : null));
  }

  @GetMapping({"/api/media", "/api/download"})
  public void download(
      @RequestParam String url,
      @RequestParam(defaultValue = "") String referer,
      @RequestParam(defaultValue = "影片") String name,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    url = urls.validate(url);
    boolean download = request.getRequestURI().equals("/api/download");
    if (urls.path(url).matches("(?i).*\\.m3u8$")) {
      if (!download) throw new VideoException("HLS 請使用原始來源播放，或按下載合併。");
      List<String> segments = media.playlist(url, referer, 0);
      RemoteResource first = media.firstSegment(segments, referer);
      baseHeaders(response, "video/mp2t");
      response.setHeader("Content-Disposition", filename(name, "ts"));
      media.writeSegments(segments, referer, first, response.getOutputStream());
      return;
    }
    String range = Optional.ofNullable(request.getHeader("Range")).orElse("");
    if (!range.isEmpty() && !range.matches("bytes=\\d*-\\d*"))
      throw new VideoException("不支援此範圍請求。");
    try (RemoteResource resource = media.openMedia(url, referer, range)) {
      response.setStatus(resource.status());
      baseHeaders(response, resource.header("content-type"));
      for (String key : List.of("content-length", "content-range", "accept-ranges"))
        if (!resource.header(key).isEmpty()) response.setHeader(key, resource.header(key));
      if (download) response.setHeader("Content-Disposition", filename(name, "mp4"));
      resource.body().transferTo(response.getOutputStream());
    }
  }

  private void baseHeaders(HttpServletResponse response, String type) {
    response.setContentType(type);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
  }

  private String filename(String name, String ext) {
    String clean =
        (name == null || name.isEmpty() ? "影片" : name).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    if (clean.length() > 100) clean = clean.substring(0, 100);
    String encoded =
        URLEncoder.encode(clean + "." + ext, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~");
    return "attachment; filename*=UTF-8''" + encoded;
  }

  private ResponseEntity<?> failure(String error, Object details, int status) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("details", details);
    return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> error(Exception e) {
    return failure(
        e.getMessage() == null ? "解析失敗，請稍後重試。" : e.getMessage(),
        e instanceof VideoException v ? v.details : null,
        400);
  }
}
