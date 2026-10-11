package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.RemoteRepository;
import com.example.video.service.BrowserService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in: mvn test -Dvideo.browser.integration=true (requires installed Chromium). */
@EnabledIfSystemProperty(named="video.browser.integration", matches="true")
class BrowserIntegrationTest {
  @Test void executesJavascriptBlocksPrivateSubresourcesAndDoesNotDownloadMedia() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger mediaDownloads = new AtomicInteger();
    AtomicInteger privateRequests = new AtomicInteger();
    server.createContext("/movie.mp4", exchange -> {
      mediaDownloads.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
    });
    server.createContext("/private", exchange -> {
      privateRequests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
    });
    server.createContext("/frame", exchange -> {
      byte[] html = "<title>frame-only-marker</title><body>iframe content</body>".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length); exchange.getResponseBody().write(html); exchange.close();
    });
    server.createContext("/page", exchange -> {
      byte[] html = ("<title>dynamic</title><body><iframe src='/frame'></iframe><script>"
          + "document.addEventListener('click',()=>document.body.dataset.clicked='yes');setTimeout(()=>{"
          + "document.body.insertAdjacentHTML('beforeend','<video src=\"/movie.mp4\" preload=\"auto\"></video>');"
          + "fetch('/movie.mp4').catch(()=>{});fetch('/private').catch(()=>{});},100);</script>")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length); exchange.getResponseBody().write(html); exchange.close();
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      // Test-only URL policy allows the local fixture; production policy remains strict.
      UrlPolicy policy = spy(new UrlPolicy());
      doAnswer(call -> {
        String url = call.getArgument(0);
        if (url.equals(base + "/private")) throw new VideoException("blocked fixture");
        return url;
      }).when(policy).validate(anyString());
      BrowserService browser = new BrowserService(policy, mock(RemoteRepository.class), true,
          System.getProperty("video.browser.channel", ""));
      BrowserService.RenderedPage rendered = browser.fetchRenderedHtml(base + "/page");
      assertTrue(rendered.html().contains("src=\"/movie.mp4\""));
      assertTrue(rendered.mediaUrls().contains(base + "/movie.mp4"));
      assertTrue(rendered.html().contains("frame-only-marker"));
      assertTrue(rendered.html().contains("<!-- IFrame:"));
      assertTrue(rendered.html().contains("data-clicked=\"yes\""));
      assertEquals(0, mediaDownloads.get());
      assertEquals(0, privateRequests.get());
    } finally { server.stop(0); }
  }
}
