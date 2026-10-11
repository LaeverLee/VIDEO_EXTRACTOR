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
          .setHeadless(true)
          .setChromiumSandbox(false) // 雲端容器部署必須設為 false
          .setTimeout(20000)
          .setArgs(List.of(
              "--disable-blink-features=AutomationControlled", // 隱藏自動化標記
              "--no-sandbox",                                // 雲端環境必須配置
              "--disable-setuid-sandbox",                    // 雲端環境必須配置
              "--disable-infobars",
              "--disable-web-security"                       // 允許跨來源 iframe 的內容讀取
          ));
          
      if (!channel.isBlank()) launch.setChannel(channel);
      
      try (Browser browser = playwright.chromium().launch(launch)) {
        
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
            .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
            .setAcceptDownloads(false)
            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .setViewportSize(1920, 1080)
            .setLocale("zh-TW");

        try (BrowserContext context = browser.newContext(contextOptions)) {
          context.setDefaultTimeout(20000);
          
          // 抹除 Playwright 特徵：覆寫 navigator.webdriver，避免被防爬蟲機制抓包
          context.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

          Set<String> checkedHosts = new HashSet<>();
          Set<String> mediaUrls = new LinkedHashSet<>();
          // 延長整體處理期限至 45 秒，以應付漫長的 WAF 驗證過程
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
          int[] requests = {0};
          
          context.routeWebSocket("**/*", socket -> socket.close());
          context.route("**/*", route -> {
            try {
              // 放寬請求數量上限，應付動態網頁大量的資源載入
              if (++requests[0] > 300 || System.nanoTime() > deadline) {
                route.abort();
                return;
              }
              String target = urls.validate(route.request().url());
              String host = java.net.URI.create(target).getHost();
              if (!checkedHosts.contains(host)) {
                remote.checkPublic(target);
                checkedHosts.add(host);
              }
              // 攔截到媒體資源 (m3u8 / mp4) 直接記錄
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
          
          // 移除嚴格的 400/403 中斷檢查，容許 WAF 挑戰頁面繼續執行
          page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
          
          // 兩段式智慧等待：先等 8 秒讓 WAF 進行 JS 運算與跳轉
          page.waitForTimeout(8000);
          String html = page.content();
          
          // 如果偵測到畫面還在驗證階段，再多給它 5 秒鐘處理轉跳
          if (html.contains("cf-browser-verification") || html.contains("Just a moment") || html.contains("Cloudflare")) {
            page.waitForTimeout(5000);
          }

          // 模擬滑鼠點擊畫面中心，觸發部分網站的播放器懶加載 (Lazy Load) 或互動解鎖
          try {
            page.mouse().click(1920 / 2.0, 1080 / 2.0);
            page.waitForTimeout(2000); // 點擊後給予網路請求 2 秒的反應時間
          } catch (Exception ignored) {}

          // 深度抓取：將主頁面與所有 iframe (播放器框架) 的 HTML 內容合併
          StringBuilder fullHtml = new StringBuilder(page.content());
          for (Frame frame : page.frames()) {
            if (frame != page.mainFrame()) {
                try {
                    fullHtml.append("\n<!-- IFrame: ").append(frame.url()).append(" -->\n");
                    fullHtml.append(frame.content());
                } catch (Exception ignored) {} // 忽略跨域讀取可能偶發的例外
            }
          }
          
          html = fullHtml.toString();

          String actual = urls.validate(page.url());
          remote.checkPublic(actual);
          
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