# Playwright 備援解析

原本的 Jsoup／Regex 適配器及優先順序不變。直接 MP4／M3U8 與靜態解析成功時不啟動瀏覽器。只有「未找到影片來源」、HTTP 403 或 429 才嘗試 Chromium：執行頁面 JavaScript、等待 5 秒，再交由同一個 extract 方法解析渲染後 HTML。HTML 仍找不到來源時，使用觀察到的第一個 MP4／M3U8 請求網址。輸出仍只有第一集、第一個來源；下載仍由下載按鈕觸發。

BrowserService 每次建立及關閉獨立 Playwright／Chromium／Context，所有操作在同一個執行緒，同時最多一個瀏覽器解析；忙碌時回傳可重試錯誤，不建立無限排隊。啟動／導覽逾時各 15 秒，最多攔截 200 個請求，30 秒後阻止新增請求。這些是操作限制，不是涵蓋 DNS、程序啟動與關閉的整體硬逾時。

## 本機

使用 Java 21 與 Maven。首次執行前安裝瀏覽器：

```sh
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
mvn test -Dvideo.browser.integration=true
mvn spring-boot:run
```

設定 `VIDEO_BROWSER_ENABLED=false` 可停用備援，回到原本的解析流程。瀏覽器未安裝不影響靜態解析，只在需要備援時回傳錯誤。一般 `mvn test` 不需要 Chromium；上述 opt-in 測試會實際啟動 Chromium，驗證 JavaScript 動態 DOM、媒體請求攔截及拒絕子資源。

Windows 已安裝 Google Chrome 時，可設定 `VIDEO_BROWSER_CHANNEL=chrome` 使用系統 Chrome。整合測試對應指令為 `mvn test -Dvideo.browser.integration=true -Dvideo.browser.channel=chrome`。容器預設使用打包的 Chromium，不需設定 channel。

## 容器

Dockerfile 使用 Playwright Java 1.48.0（依本次提供的依賴版本），建置時透過官方 CLI 安裝 Chromium 與 Linux 依賴。執行時使用非 root 帳號及 Chromium sandbox；主機必須允許非特權 user namespaces，seccomp 也必須允許 Chromium sandbox 所需 syscall。Compose 設定 2 GiB 記憶體上限、256 MiB shared memory 及 256 PID 上限；Zeabur 必須在平台另外設定資源，Compose 上限不會自動套用。

Zeabur 的執行環境會覆寫映像 USER 而以 root 啟動。因此 ENTRYPOINT 額外檢查 UID：root 時透過 runuser 切換 app 再執行 Java；非 root 時直接執行。維持 Chromium sandbox 啟用。

執行時設定 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`，避免 Playwright Java 在建立 driver 時再次下載 Firefox／WebKit 等額外瀏覽器。Dockerfile 在 Chromium 安裝完成後設定此變數；Zeabur 已另外儲存相同變數並重啟套用。

公開部署必須保留平台層 outbound 規則，拒絕私有位址、metadata 等，僅允許必要 DNS 與公網 HTTP/HTTPS。URL policy 與 DNS 檢查套用於可攔截的頁面／子資源，Service Worker 與 WebSocket 關閉，但 Chromium DNS 無法由這些檢查固定，重新導向也不能只依賴 route handler 阻擋；應用程式檢查不能替代獨立的 egress 防護。

## 適用範圍

不自動點擊播放器、不登入、不處理 CAPTCHA 或 DRM。攔下 MP4、M3U8、TS、M4S 及 media 類型請求，避免解析階段下載影片內容，因此播放器可能顯示載入失敗；這不影響收集網址。無副檔名的 fetch 媒體請求、blob／MSE 等未完整支援。

瀏覽器 Cookies 不跨解析工作保留，也不傳給既有下載流程；若來源要求 Cookies、特定 Referer、簽名或同 IP，取得網址後仍可能下載失敗。Playwright 不能保證通過 Cloudflare／WAF，也不能讓 reference JavaScript 與 Java 解析自動達到 100% 一致。

官方參考：
- https://playwright.dev/java/docs/multithreading
- https://playwright.dev/java/docs/browsers
- https://playwright.dev/java/docs/docker

## 本次驗證（2026-10-11）

全部 24 個測試通過，包含以已安裝 Google Chrome 執行的動態頁面整合測試。Playwright 1.48.0 隨附 Chromium 130 在此 Windows 主機回報 SideBySide 並列設定錯誤，因此本機實際啟動請使用上述 chrome channel。尚未驗證 Linux Docker 建置／sandbox 啟動（本機沒有 Docker）；尚未部署、commit 或 push。本次測試沒有驗證特定來源的 403 是否解決。

後續雲端驗證：Linux Docker 已建置成功，部署 6acb475b48c5c4b07806e8ee 運作中。修正 root 啟動及執行時額外下載後，公網動態測試頁（JavaScript 建立 video 標籤，初始 HTML 沒有可解析影片標籤）經 /api/inspect 回傳 200 與單一來源，確認 Chromium sandbox 啟動與 DOM 渲染成功。測試網址是解析探針，並非實際影片下載測試。尚未 commit/push，也未確認原網站 403 是否解決。
