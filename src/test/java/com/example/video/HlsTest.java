package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.example.video.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class HlsTest {
  RemoteResource text(String url, String text) {
    return new RemoteResource(
        url, 200, Map.of(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void selectsHighestBandwidthAndMergesInOrder() throws Exception {
    RemoteRepository repo = mock(RemoteRepository.class);
    String master = "https://example.com/master.m3u8";
    when(repo.fetch(master, "", ""))
        .thenAnswer(
            i ->
                text(
                    master,
                    "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=10\n"
                        + "low.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=20\n"
                        + "high.m3u8"));
    when(repo.fetch("https://example.com/high.m3u8", "", ""))
        .thenAnswer(
            i ->
                text(
                    "https://example.com/high.m3u8",
                    "#EXTM3U\n#EXTINF:1,\n1.ts\n#EXTINF:1,\n2.ts\n#EXT-X-ENDLIST"));
    when(repo.readText(any(), eq(1024 * 1024)))
        .thenAnswer(
            i -> {
              try (RemoteResource r = i.getArgument(0)) {
                return new String(r.body().readAllBytes(), StandardCharsets.UTF_8);
              }
            });
    when(repo.fetch("https://example.com/1.ts", "", ""))
        .thenAnswer(i -> text("https://example.com/1.ts", "one"));
    when(repo.fetch("https://example.com/2.ts", "", ""))
        .thenAnswer(i -> text("https://example.com/2.ts", "two"));
    MediaService media = new MediaService(repo, new UrlPolicy());
    List<String> segments = media.playlist(master, "", 0);
    assertEquals(List.of("https://example.com/1.ts", "https://example.com/2.ts"), segments);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    media.writeSegments(segments, "", media.firstSegment(segments, ""), out);
    assertEquals("onetwo", out.toString(StandardCharsets.UTF_8));
    verify(repo, never()).fetch("https://example.com/low.m3u8", "", "");
  }

  @Test
  void rejectsSameUnsupportedFormats() throws Exception {
    for (String playlist :
        List.of(
            "not hls",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128\n1.ts\n#EXT-X-ENDLIST",
            "#EXTM3U\n1.ts",
            "#EXTM3U\n#EXT-X-MAP:URI=x\n1.ts\n#EXT-X-ENDLIST",
            "#EXTM3U\n#EXT-X-ENDLIST")) {
      RemoteRepository repo = mock(RemoteRepository.class);
      when(repo.fetch(anyString(), anyString(), anyString()))
          .thenReturn(text("https://example.com/a.m3u8", playlist));
      when(repo.readText(any(), anyInt())).thenReturn(playlist);
      assertThrows(
          VideoException.class,
          () ->
              new MediaService(repo, new UrlPolicy())
                  .playlist("https://example.com/a.m3u8", "", 0));
    }
  }
}
