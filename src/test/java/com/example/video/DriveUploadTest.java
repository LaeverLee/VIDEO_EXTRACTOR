package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.video.drive.*;
import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.example.video.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DriveUploadTest {
  @Test
  void uploadsSelectedFolderWithoutChangingMediaAndCleansTemporaryFile() throws Exception {
    DriveService drive = mock(DriveService.class);
    GoogleDriveRepository google = mock(GoogleDriveRepository.class);
    MediaService media = mock(MediaService.class);
    DriveConnection c = new DriveConnection("a", "r", 3600);
    byte[] content = {0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109};
    when(media.openMedia("https://example.com/a.mp4", "https://example.com/watch", ""))
        .thenReturn(
            new RemoteResource(
                "https://example.com/a.mp4",
                200,
                Map.of("content-type", "video/mp4"),
                new ByteArrayInputStream(content)));
    AtomicReference<Path> temporary = new AtomicReference<>();
    when(google.upload(eq(c), any(), eq("Movie.mp4"), eq("video/mp4"), eq("folder1"), any()))
        .thenAnswer(
            i -> {
              Path file = i.getArgument(1);
              temporary.set(file);
              assertArrayEquals(content, Files.readAllBytes(file));
              return new ObjectMapper()
                  .readTree(
                      "{\"id\":\"file1\",\"webViewLink\":\"https://drive.google.com/file/d/file1/view\"}");
            });
    DriveUploadService uploads =
        new DriveUploadService(
            drive,
            google,
            media,
            new UrlPolicy(),
            new GoogleSettings("c", "s", "http://localhost", 100));
    try {
      Map<String, Object> result =
          uploads.start(
              "owner",
              c,
              "https://example.com/a.mp4",
              "https://example.com/watch",
              "Movie",
              "folder1");
      String id = (String) result.get("id");
      assertThrows(VideoException.class, () -> uploads.status("other-user", id));
      Map<String, Object> status = null;
      for (int i = 0; i < 200; i++) {
        status = uploads.status("owner", id);
        if (List.of("succeeded", "failed").contains(status.get("state"))) break;
        Thread.sleep(10);
      }
      assertEquals("succeeded", status.get("state"), String.valueOf(status));
      for (int i = 0; i < 100 && Files.exists(temporary.get()); i++) Thread.sleep(10);
      assertFalse(Files.exists(temporary.get()));
      verify(drive).validateFolder(c, "folder1", true);
    } finally {
      uploads.close();
    }
  }

  @Test
  void sizeLimitFailsBeforeUploading() throws Exception {
    DriveService drive = mock(DriveService.class);
    GoogleDriveRepository google = mock(GoogleDriveRepository.class);
    MediaService media = mock(MediaService.class);
    when(media.openMedia(anyString(), anyString(), anyString()))
        .thenReturn(
            new RemoteResource(
                "https://example.com/a.mp4",
                200,
                Map.of("content-type", "video/mp4"),
                new ByteArrayInputStream(new byte[100])));
    DriveUploadService uploads =
        new DriveUploadService(
            drive,
            google,
            media,
            new UrlPolicy(),
            new GoogleSettings("c", "s", "http://localhost", 10));
    try {
      String id =
          (String)
              uploads
                  .start(
                      "owner",
                      new DriveConnection("a", "r", 100),
                      "https://example.com/a.mp4",
                      "",
                      "Movie",
                      "root")
                  .get("id");
      Map<String, Object> status = null;
      for (int i = 0; i < 100; i++) {
        status = uploads.status("owner", id);
        if (status.get("state").equals("failed")) break;
        Thread.sleep(10);
      }
      assertEquals("failed", status.get("state"));
      verifyNoInteractions(google);
    } finally {
      uploads.close();
    }
  }
}
