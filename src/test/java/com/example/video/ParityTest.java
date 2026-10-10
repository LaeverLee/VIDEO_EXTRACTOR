package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.video.model.*;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.example.video.service.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ParityTest {
  final ObjectMapper json = new ObjectMapper();
  final UrlPolicy urls = new UrlPolicy();

  @Test
  void extractionMatchesOriginalJavascript() throws Exception {
    ParserService parser = new ParserService(urls, mock(RemoteRepository.class), json);
    JsonNode fixtures = json.readTree(getClass().getResourceAsStream("/parser-fixtures.json"));
    for (JsonNode f : fixtures) {
      if (f.has("error")) {
        VideoException e =
            assertThrows(
                VideoException.class,
                () -> parser.extract(f.path("html").asText(), f.path("base").asText()),
                f.path("name").asText());
        assertEquals(f.path("error").asText(), e.getMessage());
      } else
        assertEquals(
            f.get("expected"),
            json.valueToTree(parser.extract(f.path("html").asText(), f.path("base").asText())),
            f.path("name").asText());
    }
  }

  @Test
  void inspectOnlyFirstEpisodeAndSource() throws Exception {
    RemoteRepository repo = mock(RemoteRepository.class);
    RemoteResource r =
        new RemoteResource(
            "https://example.com/page", 200, Map.of(), InputStream.nullInputStream());
    when(repo.fetch(anyString(), anyString(), anyString())).thenReturn(r);
    when(repo.readText(r, 8 * 1024 * 1024))
        .thenReturn("<video src='/a.mp4'></video><video src='/b.mp4'></video>");
    ParseResult result = new ParserService(urls, repo, json).inspect("https://example.com/page");
    assertEquals(1, result.episodes().size());
    assertEquals(List.of("https://example.com/a.mp4"), result.episodes().getFirst().urls());
  }

  @Test
  void privateUrlsRemainRejected() {
    for (String value :
        List.of(
            "http://127.0.0.1",
            "http://10.0.0.1",
            "http://[::1]",
            "http://169.254.169.254",
            "http://localhost",
            "http://user:pass@example.com",
            "http://example.com:8080"))
      assertThrows(VideoException.class, () -> urls.validate(value), value);
  }

  @Test
  void mediaRefererRetryAndDiagnostics() throws Exception {
    RemoteRepository repo = mock(RemoteRepository.class);
    String url = "https://cdn.example/a.mp4", ref = "https://example.com/watch";
    when(repo.fetch(eq(url), anyString(), eq("")))
        .thenAnswer(
            i ->
                new RemoteResource(
                    url,
                    200,
                    Map.of("content-type", "text/html"),
                    new ByteArrayInputStream("<html>denied</html>".getBytes())));
    VideoException error =
        assertThrows(
            VideoException.class, () -> new MediaService(repo, urls).openMedia(url, ref, ""));
    List<?> attempts = (List<?>) error.details.get("attempts");
    assertEquals(3, attempts.size());
    assertEquals("text/html", ((Map<?, ?>) attempts.getFirst()).get("contentType"));
    verify(repo).fetch(url, "https://example.com/", "");
    verify(repo).fetch(url, ref, "");
    verify(repo).fetch(url, "", "");
  }

  @Test
  void mislabeledMp4ReplaysAllBytes() throws Exception {
    RemoteRepository repo = mock(RemoteRepository.class);
    byte[] bytes = {0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109};
    when(repo.fetch(anyString(), anyString(), anyString()))
        .thenReturn(
            new RemoteResource(
                "https://example.com/a.mp4",
                200,
                Map.of("content-type", "text/plain"),
                new ByteArrayInputStream(bytes)));
    try (RemoteResource result =
        new MediaService(repo, urls).openMedia("https://example.com/a.mp4", "", "")) {
      assertEquals("video/mp4", result.header("content-type"));
      assertArrayEquals(bytes, result.body().readAllBytes());
    }
  }

  @Test
  void disabledHtmlCheckStaysDisabled() throws Exception {
    RemoteRepository repo = mock(RemoteRepository.class);
    when(repo.fetch(anyString(), anyString(), anyString()))
        .thenReturn(
            new RemoteResource(
                "https://example.com/a.mp4",
                200,
                Map.of("content-type", "video/mp4"),
                new ByteArrayInputStream("<html>denied</html>".getBytes())));
    try (RemoteResource result =
        new MediaService(repo, urls).openMedia("https://example.com/a.mp4", "", "")) {
      assertEquals(200, result.status());
    }
  }
}
