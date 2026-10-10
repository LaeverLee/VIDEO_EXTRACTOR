package com.example.video.repository;

import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.fasterxml.jackson.databind.*;
import com.google.common.net.InetAddresses;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Repository;

@Repository
public class HttpRemoteRepository implements RemoteRepository {
  private final UrlPolicy policy;
  private final ObjectMapper json;
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .proxy(java.net.Proxy.NO_PROXY)
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .build();
  private final OkHttpClient dnsClient =
      client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build();
  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/120.0.0.0 Safari/537.36";

  public HttpRemoteRepository(UrlPolicy policy, ObjectMapper json) {
    this.policy = policy;
    this.json = json;
  }

  public void checkPublic(String value) throws IOException {
    String url = policy.validate(value), host = HttpUrl.get(url).host();
    if (InetAddresses.isInetAddress(host)) return;
    List<String> ips = new ArrayList<>();
    for (int type : List.of(1, 28)) {
      HttpUrl query =
          HttpUrl.get("https://cloudflare-dns.com/dns-query")
              .newBuilder()
              .addQueryParameter("name", host)
              .addQueryParameter("type", String.valueOf(type))
              .build();
      try (Response response =
          dnsClient
              .newCall(
                  new Request.Builder().url(query).header("Accept", "application/dns-json").build())
              .execute()) {
        if (!response.isSuccessful()) throw new VideoException("無法檢查來源網址。");
        JsonNode root = json.readTree(response.body().string());
        for (JsonNode answer : root.path("Answer"))
          if (answer.path("type").asInt() == 1 || answer.path("type").asInt() == 28)
            ips.add(answer.path("data").asText());
      }
    }
    if (ips.isEmpty()
        || ips.stream().anyMatch(ip -> !InetAddresses.isInetAddress(ip) || !policy.isGlobal(ip)))
      throw new VideoException("來源不存在，或指向非公開的網路位址。");
  }

  public RemoteResource fetch(String value, String referer, String range) throws IOException {
    String url = policy.validate(value);
    checkPublic(url);
    for (int i = 0; i < 6; i++) {
      Request.Builder request = new Request.Builder().url(url).header("User-Agent", UA);
      if (referer != null && !referer.isEmpty()) {
        String validReferer = policy.validate(referer);
        request.header("Referer", validReferer);
        HttpUrl refUrl = HttpUrl.get(validReferer);
        String host = refUrl.host();
        if (host.contains(":")) host = "[" + host + "]";
        String origin = refUrl.scheme() + "://" + host;
        if (refUrl.port() != HttpUrl.defaultPort(refUrl.scheme()))
          origin += ":" + refUrl.port();
        request.header("Origin", origin);
      }
      if (range != null && !range.isEmpty()) request.header("Range", range);
      Response response = client.newCall(request.build()).execute();
      int status = response.code();
      if (List.of(301, 302, 303, 307, 308).contains(status)) {
        String location = response.header("location");
        response.close();
        if (location == null) throw new VideoException("來源重新導向缺少網址。");
        url = policy.validate(policy.resolve(location, url));
        checkPublic(url);
        continue;
      }
      if (!response.isSuccessful()) {
        response.close();
        throw new VideoException("來源回應 " + status + "，可能限制存取或連結已失效。");
      }
      Map<String, String> headers = new LinkedHashMap<>();
      for (String name : response.headers().names())
        headers.put(name.toLowerCase(Locale.ROOT), response.header(name));
      InputStream stream =
          new FilterInputStream(response.body().byteStream()) {
            @Override
            public void close() throws IOException {
              super.close();
              response.close();
            }
          };
      return new RemoteResource(url, status, headers, stream);
    }
    throw new VideoException("來源重新導向次數過多。");
  }

  public String readText(RemoteResource resource, int limit) throws IOException {
    try (resource;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n, total = 0;
      while ((n = resource.body().read(buffer)) != -1) {
        total += n;
        if (total > limit) throw new VideoException("來源超過檔案大小限制。");
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
