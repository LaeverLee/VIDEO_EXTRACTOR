# Zeabur 部署紀錄

- 網址：https://video-extractor-laever.zeabur.app/
- Zeabur 專案：video-extractor
- Project ID：6aca6bbe4873e494859cfc5f
- Environment ID：6aca6bbe5a13ed952df086eb
- Service ID：6aca6be74873e494859cfc6b
- 主機：既有 Alibaba Cloud / Ubuntu 24.04
- 部署來源：outputs/video-parser-cloud，Dockerfile 建置 Java 21
- 目前已測：HTTPS 首頁、直接 MP4 解析、下載 video/mp4 及自訂檔名、拒絕 metadata 位址。
- Google Drive：使用者已同意將 OAuth 憑證存入 Zeabur 服務環境變數，已重啟啟用；/api/drive/status configured=true。
- 正式 OAuth callback：https://video-extractor-laever.zeabur.app/api/drive/callback
- Google Cloud 已新增並儲存相同 callback；已確認 Drive scope 已儲存。OAuth 測試名單與公開驗證仍由 Google 控制。已驗證 connect 使用 HTTPS callback、PKCE S256 與 Secure session cookie；尚未完成實際 Google 授權與 Drive 上傳驗證。
- 暫存影片與登入／工作狀態目前不跨容器重啟保存。
- 新服務已設定 Kubernetes Egress NetworkPolicy：允許平台 DNS 與公網 HTTP/HTTPS，拒絕私有網路及 metadata。
- 請使用 Zeabur「重新上傳」更新獨立服務，勿更動其他既有服務。


- 2026-10-11：privacy.html、terms.html 與首頁頁尾連結已部署，HTTPS 200；Google 品牌政策連結已儲存，發布按鈕已可用，等待正式發布最後確認。更新 Deployment ID：6aca7022da7cf920c26375d2。
- 2026-10-11：Google Search Console HTML 擁有權驗證成功。google843dbf1d87f87e48.html 已部署在根目錄，HTTPS 200 且內容相符。請保留檔案；依 Google 提示等待 24 小時後重試 OAuth 品牌驗證。
- 2026-10-11：依使用者提供版本新增 Dean Edwards Packer/Base62 解析並部署。Deployment ID：6aca7c61fb15905b77d54d7c；狀態運作中。本機測試通過，雲端 Docker 建置成功；公開 MP4 inspect 回傳單一來源，Drive configured=true，Google 驗證檔 HTTPS 200。尚未 commit。
- 2026-10-11：HLS segments.size() > 10000，錯誤訊息同步改為 10000。HlsTest 通過，Deployment ID 6aca808dc0a5252069b832fb 運作中。首頁、MP4 解析與 OAuth 設定檢查通過；未驗證實際大型 HLS 下載。
- 2026-10-11：HttpRemoteRepository 有 Referer 時送出推導 Origin；Deployment ID 6aca83d5da7cf920c26376cf 運作中。首頁、解析 API 與 OAuth 設定正常；尚未驗證原失敗來源 403 是否解決。
- 2026-10-11：HttpRemoteRepository.java 完整替換為使用者提供版本（Chrome 122 / macOS、Accept、Accept-Language、Sec-Ch-Ua、Sec-Fetch 等標頭）。Maven test 通過；Deployment ID 6acb3d5c48c5c4b07806e7eb 運作中。HTTPS 首頁 200、MP4 inspect 正常、OAuth configured=true。尚未驗證原來源 403；尚未 commit/push。
- 2026-10-11：Playwright 1.48.0 Chromium 備援解析已部署；Deployment ID 6acb475b48c5c4b07806e8ee 運作中。修正 Zeabur root 啟動：ENTRYPOINT 以 runuser 切換 app，保留 Chromium sandbox。平台環境變數 PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 已儲存並重啟，Dockerfile 同步設定供後續部署。Linux Docker 建置成功；動態 JS 測試頁經 Chromium 渲染後 /api/inspect 回傳 200 及單一來源；首頁 200、直接 MP4 inspect 與 OAuth configured=true。尚未驗證原網站 403、Drive 實際下載／上傳；尚未 commit/push。
- 2026-10-11：BrowserService.java 替換為使用者附件 b508543a 的版本並部署。Deployment ID 6acb623dc0a5252069b84a16 運作中；chromiumSandbox=false、無 sandbox 參數、20 秒逾時、300 次請求與 45 秒路由期限、8 秒等待及驗證提示時再等 5 秒。主機拉取映像約 25 分鐘，下載持续前進，磁碟與負載正常；未改動主機或網路設定。HTTPS 首頁 200、動態 JavaScript 測試頁 inspect 200 回傳單一來源、直接 MP4 inspect 與 OAuth configured=true。本機 24 個測試通過；尚未驗證原網站 Cloudflare 403 是否解除，尚未 commit/push。
- 2026-10-11：BrowserService.java 完整替換為附件 ef7705a0 版本（SHA256 B0186A3DB916B657CC5C52F46BDC6EA4D81ABC07ECA1103D388A61C520CE8703），加入頁面中心點擊、iframe HTML 合併及 disable-web-security。Deployment ID 6acb755848c5c4b07806eced 運作中；Pod 1/1 Running。Docker 使用 build-stage bind mount 安裝 Chromium，移除約 197 MB 的暫存依賴映像層。全部 24 個本機測試通過，含 iframe、點擊、JS 動態 DOM 與媒體阻擋；HTTPS 首頁 200、动态 JS inspect 200、直接 MP4 inspect 正常、Drive configured=true。主機拉取映像約 18 分鐘，進度持續增加。未驗證特定來源 403、實際影片下載或 Drive 上傳；尚未 commit/push。
