package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.video.drive.*;
import com.example.video.model.VideoException;
import com.example.video.repository.GoogleDriveRepository;
import com.example.video.service.DriveService;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class DriveServiceTest {
  final GoogleDriveRepository google = mock(GoogleDriveRepository.class);
  final ObjectMapper json = new ObjectMapper();
  final GoogleSettings settings =
      new GoogleSettings(
          "client", "secret", "http://localhost:8080/api/drive/callback", 4294967296L);

  Map<String, String> query(String url) {
    Map<String, String> result = new HashMap<>();
    for (String part : URI.create(url).getRawQuery().split("&")) {
      String[] fields = part.split("=", 2);
      result.put(fields[0], URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  @Test
  void statePkceAndSingleUseCallback() throws Exception {
    DriveService service = new DriveService(settings, google);
    MockHttpSession session = new MockHttpSession();
    Map<String, String> values = query(service.authorization(session));
    assertEquals("S256", values.get("code_challenge_method"));
    assertEquals(43, values.get("code_challenge").length());
    assertEquals("offline", values.get("access_type"));
    assertEquals(DriveService.SCOPE, values.get("scope"));
    when(google.exchangeCode(eq("code"), anyString()))
        .thenReturn(new DriveConnection("access", "refresh", 3600));
    when(google.account(any()))
        .thenReturn(json.readTree("{\"emailAddress\":\"me@example.com\",\"displayName\":\"Me\"}"));
    service.callback(session, values.get("state"), "code", null);
    assertEquals(true, service.status(session).get("connected"));
    assertEquals("me@example.com", service.status(session).get("email"));
    assertFalse(service.status(session).toString().contains("refresh"));
    assertThrows(
        VideoException.class, () -> service.callback(session, values.get("state"), "code", null));
  }

  @Test
  void stateMismatchAndSessionIsolationPreventExchange() throws Exception {
    DriveService service = new DriveService(settings, google);
    MockHttpSession a = new MockHttpSession(), b = new MockHttpSession();
    Map<String, String> values = query(service.authorization(a));
    assertThrows(
        VideoException.class, () -> service.callback(b, values.get("state"), "code", null));
    assertThrows(VideoException.class, () -> service.callback(a, "wrong", "code", null));
    verifyNoInteractions(google);
    assertThrows(VideoException.class, () -> service.connection(b));
  }

  @Test
  void foldersRespectDestinationAndTrashInsteadOfDelete() throws Exception {
    DriveService service = new DriveService(settings, google);
    MockHttpSession session = new MockHttpSession();
    DriveConnection c = new DriveConnection("a", "r", 3600);
    session.setAttribute("drive.connection", c);
    JsonNode folder =
        json.readTree(
            "{\"id\":\"folder1\",\"name\":\"Movies\",\"mimeType\":\"application/vnd.google-apps.folder\",\"trashed\":false,\"capabilities\":{\"canAddChildren\":true,\"canTrash\":true}}");
    when(google.folder(c, "folder1")).thenReturn(folder);
    when(google.folder(c, "root")).thenReturn(json.readTree("{\"id\":\"rootid\"}"));
    when(google.createFolder(c, "New", "folder1")).thenReturn(json.readTree("{\"id\":\"newid\"}"));
    assertEquals("newid", service.create(session, "folder1", " New ").path("id").asText());
    service.trash(session, "folder1");
    verify(google).trashFolder(c, "folder1");
    assertThrows(VideoException.class, () -> service.trash(session, "root"));
    assertThrows(VideoException.class, () -> service.id("x' or trashed = true"));
  }

  @Test
  void disconnectedAndUnconfiguredRemainUsableForLocalDownload() {
    DriveService service =
        new DriveService(
            new GoogleSettings("", "", "http://localhost:8080/api/drive/callback", 100), google);
    assertEquals(false, service.status(new MockHttpSession()).get("configured"));
    assertThrows(VideoException.class, () -> service.authorization(new MockHttpSession()));
    verifyNoInteractions(google);
  }

  @Test
  void trashDocumentsAndFilesButRespectPermissions() throws Exception {
    DriveService service =
        new DriveService(new GoogleSettings("id", "secret", "callback", 100), google);
    MockHttpSession session = new MockHttpSession();
    DriveConnection c = new DriveConnection("token", "refresh", 3600);
    session.setAttribute("drive.connection", c);
    when(google.folder(c, "root")).thenReturn(json.readTree("{\"id\":\"rootid\"}"));
    for (String mime : new String[] {"video/mp4", "application/vnd.google-apps.document"}) {
      when(google.folder(c, "file1"))
          .thenReturn(
              json.readTree(
                  "{\"mimeType\":\"" + mime + "\",\"capabilities\":{\"canTrash\":true}}"));
      service.trash(session, "file1");
    }
    verify(google, times(2)).trashFolder(c, "file1");
    when(google.folder(c, "readonly"))
        .thenReturn(json.readTree("{\"capabilities\":{\"canTrash\":false}}"));
    assertThrows(VideoException.class, () -> service.trash(session, "readonly"));
    verify(google, never()).trashFolder(c, "readonly");
  }
}
