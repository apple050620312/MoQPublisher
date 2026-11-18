# 直播端對端驗收

在原回報手機及至少一部其他廠牌手機上測試。記錄型號、Android 版本、App 版本與 APK SHA-256、MoQ Core 版本、相機、網路、Relay 類型與 Draft、OBS 版本、Browser Source 或原生 Source 版本及作業系統。憑證留在 App 與測試用接收端設定，保存證據前遮住觀看連結和 Token。

## 推流與接收

1. 安裝相同簽章的 Release APK，確認 MoQ 核心就緒。首次安裝可選 Offline APK 測試啟動。升級回歸應從受影響的 APK 覆蓋安裝，記下原有 codec、解析度、FPS 和碼率。
2. **moq.pro** 使用測試 Relay URL、已授權的 broadcast path 和該 Relay 要求的 JWT。接收端使用對應的訂閱授權。記錄實際可用的 Browser Source 觀看頁或原生 MoQ Source 版本與協定。
3. **Cloudflare** 在 Draft 18 Profile 填入 Publish connection token。名稱留空的案例使用同 Relay 的 Subscribe-only connection token，測試 App 內遠端驗證與相容原生接收端。另用具名的 `.hang` 案例測試 moq.watch 連結與 OBS Browser Source。保留 Publish 與 Subscribe Token 的權限分離。
4. **OBS Browser Source** 建立瀏覽器來源，填入訂閱用觀看頁並設定寬高。啟用「透過 OBS 控制音訊」後觀察混音器及錄影。若 OBS 的 Chromium 組建缺少 WebTransport 或 HEVC，保留 Source 版本與錯誤，用獨立瀏覽器重試並記錄各自結果。
5. **OBS 原生 MoQ Source** 使用已安裝、支援實際 Relay Draft 及媒體格式的 Source。依外掛文件填入訂閱端點、路徑及訂閱憑證。若沒有相容外掛，該列記為未驗證並記下外掛名稱、版本或搜尋結果。
6. 每個可用 Relay 測試 H.264＋Opus、H.265＋Opus，以及原回報可用的解析度與 FPS。至少測試 720p30、1080p30 和裝置支援的 1080p60。確認 Android 設定、診斷中的 `encoder_open`／`video_track_published`、OBS 實際畫面和混音器。記錄選定解析度與 CameraX 實際尺寸。
7. 暫時在測試 Profile 使用過期或權限不符的測試 JWT／Token，分別驗證發布失敗與訂閱失敗。核對 App 的錯誤分類及重試是否終止，隨後恢復有效憑證重測。Cloudflare 的 Subscribe 權限失敗時也檢查既有 Publisher 能否繼續推流。

## 量測

- **開始播放時間**取按下開播到 OBS 第一張實際解碼畫面、第一個可聽音訊的時間差。用一段外部錄影同時拍到按鈕和 OBS 畫面，或使用同步時鐘記錄。
- **端對端延遲**讓推流相機拍攝能顯示毫秒的畫面，並用同一台輔助相機同時拍下計時器和 OBS 畫面。至少在三個時間點讀取兩邊差距，記下最大值與中位數。
- **音畫同步**在手機前拍手或使用有同步閃光和聲響的已知訊號。用 OBS 錄影逐格比較接觸或閃光畫格與音軌峰值，記錄有正負號的毫秒誤差。以 ±150 ms 作為目標，至少測量開播初期、長時間直播末段與網路恢復後。
- **重連**依序測試 Wi-Fi → 行動網路、行動網路 → Wi-Fi、短暫斷網、測試 Relay 端主動斷線。記錄網路恢復時間與 OBS 第一張可解碼畫面的差距，目標為 15 秒。核對 `relay_status`、`relay_video_resumed`、關鍵影格與音訊恢復。若 Relay 管理介面無法中斷測試 Session，記錄該案例的限制。

## OBS 相容性矩陣

| Relay 與接收方式 | 需確認的協定與媒體能力 | H.264＋Opus | H.265＋Opus |
|---|---|---|---|
| Cloudflare 與 OBS Browser Source | moq.watch 使用具名 `.hang` 路徑，OBS 內嵌 Chromium 需具備 WebTransport 及對應解碼能力 | 未驗證 | 未驗證 |
| Cloudflare 與 OBS 原生來源 | Draft 18、Hang Catalog、選定 namespace（含空路徑）與對應解碼器 | 未驗證 | 未驗證 |
| moq.pro 與 OBS Browser Source | 該 Relay 提供的訂閱端點、相容的播放頁與對應的 Hang／Draft | 未驗證 | 未驗證 |
| moq.pro 與 OBS 原生來源 | 對應的 Relay Draft、Transport、Hang Catalog 及訂閱權限 | 未驗證 | 未驗證 |

Cloudflare 的 App 內檢查透過獨立的訂閱連線記錄 `subscriber_verification_stage`。在 moq.pro 驗收時，先以已知具訂閱權限的憑證，在獨立接收端確認能取得 Hang catalog 與影音。連線或訂閱失敗時分別記錄 Relay URL 的網域、Draft、路徑與失敗階段。

## 兩小時連續直播與四小時壓測

| 類型 | 畫質 | 操作 |
|---|---|---|
| 2 小時連續直播 | 720p30、1080p30 | 每種畫質各持續兩小時。讓接收端持續錄影，保留開始、過程及結束的資源和接收端樣本 |
| 4 小時壓力測試 | 1080p30，支援時增加 1080p60 | 在每輪中切換前後鏡頭、切到背景、關閉螢幕及切換網路，再回到前景。測試 USB 音訊輸入拔除及可重現的相機／編碼器故障恢復 |
| 重複啟停 | 任一已能開播的 H.264 或 H.265 模式 | 依序啟停十次，記錄每一輪是否只建立一個 Publisher 且能再次開播 |

1. App 的 diagnostics 事件歷史上限為 1 MiB。先跑短預演，檢查一次 ZIP 可涵蓋幾分鐘。正式測試每隔小於輪替時間的間隔匯出 ZIP，重連或故障後也立即匯出。螢幕關閉與背景案例前後各保存一份。
2. 開播前、直播中固定間隔和停止後保存 `adb shell dumpsys meminfo com.sangege.moqpublisher`。裝置提供時也比較 `adb shell dumpsys media.resource_manager` 與 `adb shell dumpsys media.audio_flinger` 中該 App 的編碼器及錄音資源。資源可用性依 Android 版本而異。
3. 用 diagnostics 比較開播後的穩定基線、直播末段、停止後與重複啟停各輪。檢查 CPU、Java／native heap、thermal、queue depth／age、FPS、drop、RTT 與傳輸碼率。確認停止後相機、編碼器、AudioRecord 和 MoQ workers 已釋放。若 native 資源只能由 heap、執行緒與 `broadcast_cleaned_up` 間接觀察，記錄證據範圍。
4. 記錄實際案例的 PASS、FAIL 或未驗證。每筆記下 UTC 起訖、裝置、App／Core／OBS／Relay 版本、codec／畫質、接收端類型、首次音畫、量測值、重連時間、診斷檔名及可重現步驟。

## 合併診斷樣本

以下指令把同一手機的匯出 ZIP 合併成按時間排序的 CSV，重疊樣本會去重，終端會列出最大樣本間隔與資源用量。請按實際案例替換 UTC 起訖值與檔案路徑。

```powershell
$exports = (Get-ChildItem -LiteralPath .\evidence -Filter '*-diagnostics-*.zip').FullName
python docs/testing/summarize_run.py $exports --from-utc 2026-11-19T03:00:00Z --to-utc 2026-11-19T07:00:00Z --csv .\evidence\run.csv
```

樣本間隔異常時對照失聯時間與檔案輪替。OBS 實際解碼、端對端延遲與音畫同步以接收端錄影為證據。App 內遠端驗證結果應另行記錄。

## 實測紀錄

| App 版本 | 裝置 | Relay 與 OBS | 結果 |
|---|---|---|---|
| v1.0.39 | 原回報手機尚未連接 | 尚無測試憑證及接收端 | 端對端與長時間直播未驗證 |
