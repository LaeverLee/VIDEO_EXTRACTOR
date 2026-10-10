# Java 雲端部署

這版需要 Java 21／Docker 常駐主機，Sites 的 Workers 執行環境不能直接執行 JAR。

## 主機與網域

準備 Linux 主機、Docker Compose、足夠暫存磁碟，及你能管理 DNS 的網域。
網域 A 記錄指向主機，開放 TCP 80、443；不要將 Java 8080 埠對外開放。
目前最多兩個 Drive 工作，每個最多 4 GB，暫存空間至少須能容納工作總量並保留餘裕。

## 設定及啟動

將原始碼傳到主機，複製 `.env.example` 為 `.env`，填入新 OAuth 憑證，並新增：

```dotenv
SITE_DOMAIN=video.example.com
```

主機上的 `.env` 僅允許管理者讀取，不上傳到 Git。Google Cloud 的重新導向 URI 加入：
`https://video.example.com/api/drive/callback`（換成你的網域）。
正式 Compose 自動使用該 HTTPS 回呼，本機設定不會被覆寫。

```sh
docker compose -f compose.production.yaml up -d --build
docker compose -f compose.production.yaml logs --tail=100
```

Caddy 自動申請 HTTPS 憑證，需先完成 DNS 並讓 80／443 可由外部存取。
正式設定啟用 Secure Session Cookie 與代理標頭處理；代理是唯一入口。

## 上線確認

確認首頁、解析、本機下載、OAuth 回呼及 Drive 上傳。OAuth 測試模式仍只允許測試名單；
網站公開與 Google OAuth 驗證是兩件事，公開 Drive 功能仍需完成 Google 對應驗證。
來源影片流量會經過主機，主機費用、出口流量及 Google 驗證費用應另行評估。

Drive 工作保留在記憶體，重啟服務會清除登入與工作狀態，無法跨主機恢復。
此部署先使用單一 app 實例。停止／更新服務前應等待工作完成。
異常停止留下的暫存影片位於 `video_temp`，應定期清理已無工作使用的暫存檔。
公網服務需在雲端網路層限制往內網、metadata 與管理服務的出口存取，並依使用量配置限流。
