package com.example.video.service;

import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.RemoteRepository;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Each operation owns its Playwright objects on the calling thread. */
@Service
public class BrowserService {
  public record RenderedPage(String html, String url, List<String> mediaUrls) {}
  private final UrlPolicy urls;
  private final RemoteRepository remote;
  private final boolean enabled;
  private final String channel;
  private final Semaphore slot = new Semaphore(1);

  public BrowserService(UrlPolicy urls, RemoteRepository remote, boolean enabled) {
    this(urls, remote, enabled, "");
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BrowserService(UrlPolicy urls, RemoteRepository remote,
      @Value("${video.browser.enabled:true}") boolean enabled,
      @Value("${video.browser.channel:}") String channel) {
    this.urls = urls;
    this.remote = remote;
    this.enabled = enabled;
    this.channel = channel;
  }

  public boolean enabled() { return enabled; }

  public RenderedPage fetchRenderedHtml(String value) {
    if (!enabled) throw new VideoException("瀏覽器解析未啟用。");
    String url = urls.validate(value);
    if (!slot.tryAcquire()) throw new VideoException("瀏覽器解析忙碌中，請稍後重試。");
    try (Playwright playwright = Playwright.create()) {
      BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
          .setHeadless(true).setChromiumSandbox(true).setTimeout(15000);
      if (!channel.isBlank()) launch.setChannel(channel);
      try (Browser browser = playwright.chromium().launch(launch)) {
        try (BrowserContext context = browser.newContext(
            new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                .setAcceptDownloads(false))) {
          context.setDefaultTimeout(15000);
          Set<String> checkedHosts = new HashSet<>();
          Set<String> mediaUrls = new LinkedHashSet<>();
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
          int[] requests = {0};
          // No WebSocket connection is needed to discover supported MP4/HLS sources.
          context.routeWebSocket("**/*", socket -> socket.close());
          // Validate intercepted documents and subresources, not only the input URL.
          // DNS checks supplement deployment-level egress rules; they cannot pin Chromium DNS.
          context.route("**/*", route -> {
            try {
              if (++requests[0] > 200 || System.nanoTime() > deadline) {
                route.abort();
                return;
              }
              String target = urls.validate(route.request().url());
              String host = java.net.URI.create(target).getHost();
              if (!checkedHosts.contains(host)) {
                remote.checkPublic(target);
                checkedHosts.add(host);
              }
              // Parsing must not download the video before the user presses Download.
              if (route.request().resourceType().equals("media")
                  || urls.path(target).matches("(?i).*\\.(mp4|m3u8|ts|m4s)$")) {
                if (urls.path(target).matches("(?i).*\\.(mp4|m3u8)$")) mediaUrls.add(target);
                route.abort();
              } else {
                route.resume();
              }
            } catch (Exception rejected) {
              route.abort();
            }
          });
          Page page = context.newPage();
          Response response = page.navigate(url,
              new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
          if (response != null && response.status() >= 400)
            throw new VideoException("瀏覽器來源回應 " + response.status() + "，無法取得頁面。");
          // A bounded event-loop wait allows scripts/AJAX to populate the DOM.
          page.waitForTimeout(5000);
          String actual = urls.validate(page.url());
          remote.checkPublic(actual);
          String html = page.content();
          if (html.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
            throw new VideoException("來源超過檔案大小限制。");
          return new RenderedPage(html, actual, List.copyOf(mediaUrls));
        }
      }
    } catch (VideoException error) {
      throw error;
    } catch (Exception error) {
      VideoException failure = new VideoException("瀏覽器解析失敗；請確認 Chromium 已安裝，或來源需要登入／人工驗證。");
      failure.initCause(error);
      throw failure;
    } finally {
      slot.release();
    }
  }
}
