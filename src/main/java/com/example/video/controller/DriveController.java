package com.example.video.controller;

import com.example.video.model.VideoException;
import com.example.video.service.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drive")
public class DriveController {
  private final DriveService drive;
  private final DriveUploadService uploads;

  public DriveController(DriveService drive, DriveUploadService uploads) {
    this.drive = drive;
    this.uploads = uploads;
  }

  private void origin(HttpServletRequest r) {
    String own = r.getScheme() + "://" + r.getHeader("Host");
    if (!own.equals(r.getHeader("Origin"))) throw new VideoException("不允許跨網站請求。");
  }

  @GetMapping("/status")
  public Object status(HttpSession session, HttpServletResponse r) {
    r.setHeader("Cache-Control", "no-store");
    return drive.status(session);
  }

  @PostMapping("/connect")
  public Object connect(HttpServletRequest r) {
    origin(r);
    return Map.of("url", drive.authorization(r.getSession()));
  }

  @GetMapping("/callback")
  public void callback(
      HttpServletRequest r,
      HttpServletResponse response,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error)
      throws IOException {
    try {
      drive.callback(r.getSession(), state, code, error);
      r.changeSessionId();
      response.sendRedirect("/?drive_connected=1");
    } catch (Exception e) {
      response.sendRedirect(
          "/?drive_error="
              + URLEncoder.encode(
                  e.getMessage() == null ? "Google 連線失敗。" : e.getMessage(),
                  StandardCharsets.UTF_8));
    }
  }

  @PostMapping("/disconnect")
  public Object disconnect(HttpServletRequest r) throws IOException {
    origin(r);
    drive.disconnect(r.getSession());
    return Map.of("connected", false);
  }

  @GetMapping("/folders")
  public Object folders(
      HttpSession session,
      @RequestParam(defaultValue = "root") String parent,
      @RequestParam(defaultValue = "") String pageToken)
      throws IOException {
    return drive.folders(session, parent, pageToken);
  }

  public record FolderInput(String parent, String name) {}

  @PostMapping("/folders")
  public Object create(HttpServletRequest r, @RequestBody FolderInput input) throws IOException {
    origin(r);
    return drive.create(r.getSession(), input.parent(), input.name());
  }

  @DeleteMapping({"/folders/{id}", "/files/{id}"})
  public Object trash(HttpServletRequest r, @PathVariable String id) throws IOException {
    origin(r);
    drive.trash(r.getSession(), id);
    return Map.of("trashed", true);
  }

  public record UploadInput(String url, String referer, String name, String parent) {}

  @PostMapping("/uploads")
  public Object upload(HttpServletRequest r, @RequestBody UploadInput input) throws IOException {
    origin(r);
    HttpSession session = r.getSession();
    return uploads.start(
        session.getId(),
        drive.connection(session),
        input.url(),
        input.referer() == null ? "" : input.referer(),
        input.name(),
        input.parent());
  }

  @GetMapping("/uploads/{id}")
  public Object uploadStatus(HttpSession session, @PathVariable String id) {
    return uploads.status(session.getId(), id);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> error(Exception e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage() == null ? "Google Drive 操作失敗。" : e.getMessage());
    body.put("details", e instanceof VideoException v ? v.details : null);
    return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(body);
  }
}
