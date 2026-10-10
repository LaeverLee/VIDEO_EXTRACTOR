# Google Drive 連線設定

這份 Java 版讓每位使用者透過自己的 Google 帳號連線。Codex 的 Google Drive 連接器不會自動提供網站的 OAuth Client ID。

## Google Cloud 設定

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 建立或選擇專案，啟用 **Google Drive API**。
2. 設定 Google Auth Platform 的應用名稱、支援 Email 與 Audience。測試階段將要登入的 Google 帳號加入 Test users。
3. 建立類型為 **Web application** 的 OAuth Client。
4. 加入 Authorized redirect URI：`http://localhost:8080/api/drive/callback`。若使用 `127.0.0.1`，須另建對應的 redirect URI 並讓環境變數一致；不要混用兩種網址。
5. 取得 Client ID 與 Client Secret，填入伺服器環境變數。不要把 Secret 貼到公開頁面或 GitHub。

## Windows PowerShell 啟動

```powershell
$env:GOOGLE_CLIENT_ID='你的 Client ID'
$env:GOOGLE_CLIENT_SECRET='你的 Client Secret'
$env:GOOGLE_REDIRECT_URI='http://localhost:8080/api/drive/callback'
java -jar video-parser-java.jar
```

Docker Compose 會讀取同目錄 `.env`。複製 `.env.example` 為 `.env` 後填入設定，再執行 `docker compose up -d --build`。直接執行 JAR 不會自動讀取 `.env`；本機可執行 `./start-local.ps1` 載入同目錄 `.env` 後啟動，或使用上述環境變數。

公開部署改用實際 HTTPS 網域，例如 `https://your-domain.example/api/drive/callback`，並同時修改 Google Cloud 的 redirect URI 與環境變數。HTTP localhost 僅適合本機測試。

## 使用流程

1. 按「連結 Google Drive」，完成 Google 授權。
2. 按「管理檔案與資料夾」，開啟小視窗。可瀏覽檔案與子資料夾、建立資料夾、將一般檔案／Google 文件／資料夾移到垃圾桶，並按「存到此資料夾」選擇位置。
3. 解析影片後，選擇「本機」或「Google Drive」，按下方「下載影片」。解析成功不會自動下載。
4. Drive 模式會顯示取得影片與上傳進度，完成後可開啟 Drive 檔案。

資料夾刪除採用 Google Drive 的 `trashed=true`，可到 Drive 垃圾桶復原。共享資料夾會依 Google 回傳的建立／刪除權限決定是否允許操作。

## 權限與資料保存

為了管理既有 Drive 資料夾，這版使用 `https://www.googleapis.com/auth/drive`。此為廣泛的 Drive 權限；提供給一般外部使用者時，Google 可能要求 OAuth 驗證及相關審核。`drive.file` 不能直接替代目前任意既有資料夾管理流程。

Access token / refresh token 僅保留在伺服器的使用者 Session，不傳至頁面、不写入程式或資料庫。重新啟動服務、Session 到期後需重新連線。解除連結會撤銷 Google 授權。

上傳工作在後端執行，先將影片串流到伺服器暫存檔，再用 8 MB 分段的 resumable upload 傳到 Drive。正常成功／失敗時刪除暫存檔。預設上傳上限 4 GB，可用 `GOOGLE_DRIVE_MAX_UPLOAD_BYTES` 設定；主機須有足夠暫存空間。異常終止程序可能留下系統暫存檔，正式主機應配置暫存清理。

同一 Session 同時只允許一個上傳工作；全服務最多兩個工作執行，佇列最多 16 個。上傳狀態保留一小時，並限制使用者只能查詢自己的工作。這版不提供跨伺服器持久登入或工作恢復。

## 官方文件

- [OAuth Web Server 流程](https://developers.google.com/identity/protocols/oauth2/web-server)
- [Drive 資料夾建立](https://developers.google.com/workspace/drive/api/guides/folder)
- [Drive 垃圾桶與刪除](https://developers.google.com/workspace/drive/api/guides/delete)
- [Resumable upload](https://developers.google.com/workspace/drive/api/guides/manage-uploads)

Google OAuth 尚未設定時，本機下載功能仍可使用。實際 Google 登入／上傳需有效 OAuth 設定與使用者授權；自動測試使用測試替身，不會寫入真實 Drive。
