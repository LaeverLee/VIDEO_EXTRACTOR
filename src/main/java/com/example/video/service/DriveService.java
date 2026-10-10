package com.example.video.service;

import com.example.video.drive.*;
import com.example.video.model.VideoException;
import com.example.video.repository.GoogleDriveRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DriveService {
  private static final String CONNECTION = "drive.connection", PENDING = "drive.oauth";
  public static final String SCOPE = "https://www.googleapis.com/auth/drive";
  private final GoogleSettings settings;
  private final GoogleDriveRepository repo;
  private final SecureRandom random = new SecureRandom();

  public DriveService(GoogleSettings settings, GoogleDriveRepository repo) {
    this.settings = settings;
    this.repo = repo;
  }

  private record Pending(String state, String verifier, Instant expires) {}

  public Map<String, Object> status(HttpSession session) {
    DriveConnection c = (DriveConnection) session.getAttribute(CONNECTION);
    return Map.of(
        "configured",
        settings.configured(),
        "connected",
        c != null,
        "email",
        c == null ? "" : c.email,
        "displayName",
        c == null ? "" : c.displayName);
  }

  public DriveConnection connection(HttpSession session) {
    DriveConnection c = (DriveConnection) session.getAttribute(CONNECTION);
    if (c == null) throw new VideoException("請先連結 Google Drive。");
    return c;
  }

  public String authorization(HttpSession session) {
    if (!settings.configured())
      throw new VideoException("尚未設定 Google OAuth，請先填入 GOOGLE_CLIENT_ID 與 GOOGLE_CLIENT_SECRET。");
    String state = secret(), verifier = secret(), challenge;
    try {
      challenge =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256")
                      .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    session.setAttribute(PENDING, new Pending(state, verifier, Instant.now().plusSeconds(600)));
    Map<String, String> values = new LinkedHashMap<>();
    values.put("client_id", settings.clientId());
    values.put("redirect_uri", settings.redirectUri());
    values.put("response_type", "code");
    values.put("scope", SCOPE);
    values.put("access_type", "offline");
    values.put("prompt", "consent");
    values.put("state", state);
    values.put("code_challenge", challenge);
    values.put("code_challenge_method", "S256");
    StringJoiner query = new StringJoiner("&");
    values.forEach((k, v) -> query.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    return "https://accounts.google.com/o/oauth2/v2/auth?" + query;
  }

  public void callback(HttpSession session, String state, String code, String error)
      throws IOException {
    Pending p;
    synchronized (session) {
      p = (Pending) session.getAttribute(PENDING);
      session.removeAttribute(PENDING);
    }
    if (p == null
        || state == null
        || p.expires().isBefore(Instant.now())
        || !MessageDigest.isEqual(
            p.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8)))
      throw new VideoException("Google 登入驗證已失效，請重新連結。");
    if (error != null && !error.isBlank()) throw new VideoException("已取消 Google Drive 授權。");
    if (code == null || code.isBlank()) throw new VideoException("Google 未回傳授權碼。");
    DriveConnection c = repo.exchangeCode(code, p.verifier());
    JsonNode account = repo.account(c);
    c.email = account.path("emailAddress").asText();
    c.displayName = account.path("displayName").asText();
    session.setAttribute(CONNECTION, c);
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public void disconnect(HttpSession session) throws IOException {
    DriveConnection c = connection(session);
    repo.revoke(c);
    session.removeAttribute(CONNECTION);
    session.removeAttribute(PENDING);
  }

  public String id(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new VideoException("資料夾識別碼無效。");
    return id;
  }

  public JsonNode validateFolder(DriveConnection c, String id, boolean write) throws IOException {
    JsonNode folder = repo.folder(c, id(id));
    if (!folder.path("mimeType").asText().equals("application/vnd.google-apps.folder")
        || folder.path("trashed").asBoolean()) throw new VideoException("所選位置不是可使用的資料夾。");
    if (write && !folder.path("capabilities").path("canAddChildren").asBoolean())
      throw new VideoException("你沒有在此資料夾存放檔案的權限。");
    return folder;
  }

  public JsonNode folders(HttpSession session, String parent, String pageToken) throws IOException {
    DriveConnection c = connection(session);
    validateFolder(c, parent, false);
    return repo.folders(c, id(parent), pageToken == null ? "" : pageToken);
  }

  public JsonNode create(HttpSession session, String parent, String name) throws IOException {
    if (name == null || name.isBlank() || name.strip().length() > 255)
      throw new VideoException("請輸入 1 至 255 字元的資料夾名稱。");
    DriveConnection c = connection(session);
    validateFolder(c, parent, true);
    return repo.createFolder(c, name.strip(), id(parent));
  }

  public void trash(HttpSession session, String id) throws IOException {
    DriveConnection c = connection(session);
    id = id(id);
    if (id.equals("root") || repo.folder(c, "root").path("id").asText().equals(id))
      throw new VideoException("無法刪除 Google Drive 根目錄。");
    JsonNode folder = repo.folder(c, id);
    if (folder.path("trashed").asBoolean()) throw new VideoException("此項目已在垃圾桶。");
    if (!folder.path("capabilities").path("canTrash").asBoolean())
      throw new VideoException("你沒有刪除此項目的權限。");
    repo.trashFolder(c, id);
  }
}
