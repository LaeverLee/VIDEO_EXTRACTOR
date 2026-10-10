package com.example.video.repository;

import com.example.video.drive.*;
import com.example.video.model.VideoException;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongConsumer;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleHttpRepository implements GoogleDriveRepository {
  private final GoogleSettings settings;
  private final ObjectMapper json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(20))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private static final String API = "https://www.googleapis.com/drive/v3/";

  public GoogleHttpRepository(GoogleSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  private String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Google Drive 請求已中斷。", e);
    }
  }

  private JsonNode checked(HttpResponse<String> response) throws IOException {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String message = "Google Drive 回應 HTTP " + response.statusCode();
      try {
        JsonNode body = json.readTree(response.body());
        String detail =
            body.path("error").isObject()
                ? body.path("error").path("message").asText()
                : body.path("error_description").asText();
        if (!detail.isBlank()) message += "：" + detail;
      } catch (Exception ignored) {
      }
      throw new VideoException(message);
    }
    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
  }

  private JsonNode token(Map<String, String> values) throws IOException {
    StringJoiner body = new StringJoiner("&");
    values.forEach((k, v) -> body.add(encode(k) + "=" + encode(v)));
    return checked(
        send(
            HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()));
  }

  public DriveConnection exchangeCode(String code, String verifier) throws IOException {
    JsonNode body =
        token(
            Map.of(
                "client_id",
                settings.clientId(),
                "client_secret",
                settings.clientSecret(),
                "redirect_uri",
                settings.redirectUri(),
                "grant_type",
                "authorization_code",
                "code",
                code,
                "code_verifier",
                verifier));
    if (body.path("access_token").asText().isBlank()) throw new VideoException("Google 未回傳登入權杖。");
    return new DriveConnection(
        body.path("access_token").asText(),
        body.path("refresh_token").asText(),
        body.path("expires_in").asLong(3600));
  }

  public String accessToken(DriveConnection connection) throws IOException {
    synchronized (connection) {
      if (connection.expiresAt.isBefore(java.time.Instant.now().plusSeconds(60))) {
        if (connection.refreshToken.isBlank())
          throw new VideoException("Google Drive 登入已過期，請重新連結。");
        JsonNode body =
            token(
                Map.of(
                    "client_id",
                    settings.clientId(),
                    "client_secret",
                    settings.clientSecret(),
                    "grant_type",
                    "refresh_token",
                    "refresh_token",
                    connection.refreshToken));
        connection.accessToken = body.path("access_token").asText();
        connection.expiresAt =
            java.time.Instant.now().plusSeconds(body.path("expires_in").asLong(3600));
      }
      return connection.accessToken;
    }
  }

  private JsonNode api(DriveConnection c, String path, String method, Object body)
      throws IOException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(API + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + accessToken(c));
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return checked(send(request.build()));
  }

  public JsonNode account(DriveConnection c) throws IOException {
    return api(c, "about?fields=user(displayName,emailAddress)", "GET", null).path("user");
  }

  public JsonNode folders(DriveConnection c, String parent, String pageToken) throws IOException {
    String query = "'" + parent + "' in parents and trashed =" + " false";
    return api(
        c,
        "files?q="
            + encode(query)
            + "&pageSize=100&orderBy=name&fields="
            + encode("nextPageToken,files(id,name,mimeType,modifiedTime,capabilities(canTrash,canAddChildren))")
            + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
            + (pageToken.isBlank() ? "" : "&pageToken=" + encode(pageToken)),
        "GET",
        null);
  }

  public JsonNode folder(DriveConnection c, String id) throws IOException {
    return api(
        c,
        "files/"
            + encode(id)
            + "?fields="
            + encode("id,name,mimeType,trashed,capabilities(canTrash,canAddChildren)")
            + "&supportsAllDrives=true",
        "GET",
        null);
  }

  public JsonNode createFolder(DriveConnection c, String name, String parent) throws IOException {
    return api(
        c,
        "files?fields=id,name&supportsAllDrives=true",
        "POST",
        Map.of(
            "name",
            name,
            "mimeType",
            "application/vnd.google-apps.folder",
            "parents",
            List.of(parent)));
  }

  public void trashFolder(DriveConnection c, String id) throws IOException {
    api(c, "files/" + encode(id) + "?supportsAllDrives=true", "PATCH", Map.of("trashed", true));
  }

  public JsonNode upload(
      DriveConnection c, Path file, String name, String mime, String parent, LongConsumer progress)
      throws IOException {
    long size = Files.size(file);
    HttpRequest init =
        HttpRequest.newBuilder(
                URI.create(
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,webViewLink&supportsAllDrives=true"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + accessToken(c))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", String.valueOf(size))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("name", name, "parents", List.of(parent)))))
            .build();
    HttpResponse<String> start = send(init);
    checked(start);
    String location =
        start
            .headers()
            .firstValue("Location")
            .orElseThrow(() -> new VideoException("Google Drive 沒有回傳上傳位置。"));
    URI session = URI.create(location);
    if (!session.getScheme().equals("https") || !session.getHost().equals("www.googleapis.com"))
      throw new VideoException("Google Drive 上傳位置無效。");
    final int chunkSize = 8 * 1024 * 1024;
    long offset = 0;
    try (RandomAccessFile source = new RandomAccessFile(file.toFile(), "r")) {
      while (offset < size) {
        int count = (int) Math.min(chunkSize, size - offset);
        byte[] chunk = new byte[count];
        source.seek(offset);
        source.readFully(chunk);
        long end = offset + count - 1;
        int retries = 0;
        while (true) {
          HttpRequest request =
              HttpRequest.newBuilder(session)
                  .timeout(Duration.ofMinutes(5))
                  .header("Authorization", "Bearer " + accessToken(c))
                  .header("Content-Type", mime)
                  .header("Content-Range", "bytes " + offset + "-" + end + "/" + size)
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                  .build();
          HttpResponse<String> response;
          try {
            response = send(request);
          } catch (IOException failure) {
            if (++retries > 3) throw failure;
            response = queryUpload(c, session, size);
          }
          if (response.statusCode() >= 200 && response.statusCode() < 300) {
            progress.accept(size);
            return checked(response);
          }
          if (response.statusCode() == 308) {
            String range = response.headers().firstValue("Range").orElse("");
            long confirmed =
                range.isBlank()
                    ? 0
                    : Long.parseLong(range.substring(range.lastIndexOf('-') + 1)) + 1;
            if (confirmed < offset || confirmed > size)
              throw new VideoException("Google Drive 回報的上傳進度無效。");
            if (confirmed == offset) {
              if (++retries > 3) throw new VideoException("Google Drive 上傳進度停滯。");
              continue;
            }
            offset = confirmed;
            progress.accept(offset);
            break;
          }
          if (response.statusCode() >= 500 && ++retries <= 3) {
            HttpResponse<String> status = queryUpload(c, session, size);
            if (status.statusCode() >= 200 && status.statusCode() < 300) {
              progress.accept(size);
              return checked(status);
            }
            if (status.statusCode() == 308) {
              String range = status.headers().firstValue("Range").orElse("");
              long confirmed =
                  range.isBlank()
                      ? 0
                      : Long.parseLong(range.substring(range.lastIndexOf('-') + 1)) + 1;
              if (confirmed > offset) {
                offset = confirmed;
                progress.accept(offset);
                break;
              }
            }
            continue;
          }
          checked(response);
        }
      }
    }
    return checked(queryUpload(c, session, size));
  }

  private HttpResponse<String> queryUpload(DriveConnection c, URI session, long size)
      throws IOException {
    return send(
        HttpRequest.newBuilder(session)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + accessToken(c))
            .header("Content-Range", "bytes */" + size)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build());
  }

  public void revoke(DriveConnection c) throws IOException {
    String body = "token=" + encode(c.refreshToken.isBlank() ? c.accessToken : c.refreshToken);
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/revoke"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    if (response.statusCode() != 200 && response.statusCode() != 400) checked(response);
  }
}
