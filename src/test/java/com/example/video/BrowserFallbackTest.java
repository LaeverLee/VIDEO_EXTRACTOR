package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.video.model.*;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.example.video.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;

class BrowserFallbackTest {
  final String url = "https://example.com/watch";
  final RemoteRepository remote = mock(RemoteRepository.class);
  final BrowserService browser = mock(BrowserService.class);
  final ParserService parser = new ParserService(new UrlPolicy(), remote, new ObjectMapper(), browser);

  void staticPage(String html) throws Exception {
    RemoteResource resource = new RemoteResource(url, 200, Map.of(), InputStream.nullInputStream());
    when(remote.fetch(url, "", "")).thenReturn(resource);
    when(remote.readText(resource, 8 * 1024 * 1024)).thenReturn(html);
    when(browser.enabled()).thenReturn(true);
  }

  @Test void staticSuccessDoesNotLaunchBrowser() throws Exception {
    staticPage("<video src='/one.mp4'></video>");
    assertEquals(List.of("https://example.com/one.mp4"), parser.inspect(url).episodes().getFirst().urls());
    verifyNoInteractions(browser);
  }

  @Test void renderedFallbackKeepsFirstSourceAndRedirectReferer() throws Exception {
    staticPage("<title>loading</title>");
    when(browser.fetchRenderedHtml(url)).thenReturn(new BrowserService.RenderedPage(
        "<title>Dynamic</title><video src='/one.mp4'></video><video src='/two.mp4'></video>",
        "https://example.com/actual", List.of()));
    ParseResult result = parser.inspect(url);
    assertEquals("Dynamic", result.title());
    assertEquals("https://example.com/actual", result.pageURL());
    assertEquals(List.of("https://example.com/one.mp4"), result.episodes().getFirst().urls());
  }

  @Test void observedMediaFallbackAfter403() throws Exception {
    when(remote.fetch(url, "", "")).thenThrow(new VideoException("來源回應 403，可能限制存取或連結已失效。"));
    when(browser.enabled()).thenReturn(true);
    when(browser.fetchRenderedHtml(url)).thenReturn(new BrowserService.RenderedPage(
        "<title>JS player</title>", url, List.of("https://example.com/first.m3u8", "https://example.com/second.mp4")));
    assertEquals(List.of("https://example.com/first.m3u8"), parser.inspect(url).episodes().getFirst().urls());
  }

  @Test void disabledBrowserAndPermanentFailuresDoNotRetry() throws Exception {
    staticPage("<title>loading</title>");
    when(browser.enabled()).thenReturn(false);
    assertThrows(VideoException.class, () -> parser.inspect(url));
    verify(browser, never()).fetchRenderedHtml(anyString());
    when(browser.enabled()).thenReturn(true);
    when(remote.fetch(url, "", "")).thenThrow(new VideoException("來源回應 404，可能限制存取或連結已失效。"));
    assertThrows(VideoException.class, () -> parser.inspect(url));
    verify(browser, never()).fetchRenderedHtml(anyString());
  }

  @Test void privateInputNeverLaunchesBrowser() {
    assertThrows(VideoException.class, () -> parser.inspect("http://127.0.0.1/"));
    verifyNoInteractions(remote, browser);
  }
}
