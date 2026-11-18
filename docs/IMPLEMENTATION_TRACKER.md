# MoQ Publisher 實作追蹤

狀態只代表目前程式碼與可取得的測試證據。`TESTED` 不代表已在 Android 手機、Cloudflare 或 OBS 完成端對端驗證。

| 編號 | 功能 | 原始狀態與已有能力 | 缺少能力／本批次範圍 | 實作狀態 | 自動測試 | 實機驗證 | 已知限制 |
|---|---|---|---|---|---|---|---|
| 01 | 端對端直播測試基礎 | 有 moq.pro／Cloudflare、OBS、認證、codec、延遲與音畫量測流程，支援合併匯出的診斷樣本 | 取得實際端對端矩陣 | IMPLEMENTED | ZIP 合併腳本以重疊樣本驗證去重、排序、期間篩選 | BLOCKED | 未連接 Android 裝置或測試 Relay |
| 02 | 完整直播狀態機 | 補齊 handover → connecting → announced，從健康事件進入 Degraded，遠端驗證在等待關鍵影格期間停用 | 各種 Relay 與 Service 生命週期實測 | IMPLEMENTED | `BroadcastLifecycleTest`、`RelayConnectionPolicyTest` | 未驗證 | 遠端解碼結果使用獨立 verifier |
| 03 | 直播 Watchdog | 每秒監控相機、GPU 提交、編碼、發布、音訊擷取和發布、Relay 與佇列。低 FPS 採十秒門檻 | 長時間裝置資料校準 | IMPLEMENTED | `StreamHealthTest`、`StreamMetricsTest`、`VideoPublishQueueTest` | 未驗證 | 裝置和畫質影響實際門檻 |
| 04 | 媒體管線自動恢復 | 元件級重試採穩定窗口與有限退避，分流 Codec callback，停止／切線先等恢復 worker 結束 | 驗證元件重新輸出及真實故障情境 | IMPLEMENTED | `BroadcastRecoveryTest`、`BroadcastLifecycleTest` | 未驗證 | Codec／CameraX／AudioRecord 需實機故障驗收 |
| 05 | 網路切換與重連 | DefaultNetworkMonitor 與 QUIC reconnect，共用直播時間基準，清除舊媒體並等新關鍵影格 | 真實網路異常矩陣與 15 秒恢復量測 | IMPLEMENTED | `RelayConnectionPolicyTest`、`AudioPublishQueueTest` 涵蓋舊寫入、相同 epoch 重連與關鍵影格 | BLOCKED | 尚未連接可切換 Wi-Fi／4G／5G 的 Android 裝置 |
| 06 | ABR | VideoBitrateControl 從目標碼率開始，依佇列年齡、深度和網路容量調整，限制於最低及最高設定 | 解析度與 FPS 動態重建見 16 | TESTED | `VideoBitrateControlTest` 涵蓋拒絕動態調整、舊佇列、持續高 RTT 與碼率上下限 | 未驗證 | 動態碼率以 MediaCodec 執行結果為準 |
| 07 | 音畫同步 | 有 AudioTimeline、VideoTimestampMapper、reset 與 monotonic tests | 接收端延遲與音畫誤差量測 | TESTED | AudioTimeline、VideoTimestampMapper tests | BLOCKED | 端到端誤差不能以 RTT 代替 |
| 08 | 遠端媒體驗證／OBS | Cloudflare Subscribe-only 檢查分連線、認證、catalog、訂閱、收到關鍵影格及影音解碼，重連或影片 track 重建會失效 | 使用實際 Relay 與 OBS 填寫接收矩陣 | IMPLEMENTED | `RelayProfileTest` 涵蓋空名稱與訂閱憑證分離，編譯驗證解碼流程 | BLOCKED | 無 Android 裝置與測試 Token |
| 09 | 主動異常警報 | 主畫面健康提示、依嚴重度排序的背景警報、冷卻、可切換提示音／震動、可查看歷史 | 裝置驗證通知、音效、震動與恢復 | IMPLEMENTED | `StreamAlertManagerTest`、`StreamHealthTest`、`SettingsPersistenceTest` 已編譯 | 未驗證 | 通知受 Android 系統權限和頻道設定控制 |
| 10 | 長時間穩定性 | 有 2／4 小時測試矩陣、資源檢查及診斷合併工具 | 取得正式壓測與 leak 檢查紀錄 | IMPLEMENTED | ZIP 合併腳本以含缺口的資料驗證 | BLOCKED | 需要手機、OBS、Relay 憑證與測試時段 |
| 11 | 開播前檢查 | PASS／WARNING／ERROR 預檢已串入開播，硬體 surface 清單缺項會提示並允許實際啟動編碼器 | 以原回報裝置驗證 H.264／H.265 開播 | TESTED | `BroadcastPreflightTest` 重現能力清單誤擋，並檢查權限、核心與無效參數 | BLOCKED | 尚無原回報裝置 |
| 12 | 直播中靜音／增益 | 有持久 gain 與 PCM saturation | mute、gain ramp、背景通知狀態 | IMPLEMENTED | `AudioSettingsTest`、`AudioHealthMetricsTest` | 未驗證 | 需要裝置實測切換增益時的實際聽感 |
| 13 | 麥克風音量表 | AudioHealthMetrics 已計算 peak／靜音 | UI RMS／dBFS／peak hold | IMPLEMENTED | `AudioHealthMetricsTest` | 未驗證 | 聲道與削波顯示需要不同硬體麥克風實測 |
| 14 | 音訊輸入設備 | AudioRecord 使用預設裝置 | AudioManager 列表、偏好與拔除處理 | IMPLEMENTED | `AudioInputDeviceTest` 與 AudioRecord 編譯驗證 | 未驗證 | 需要多種實體音訊裝置實測路由與拔除回退 |
| 15 | 硬體編碼相容性 | DeviceCapabilityRepository 提供能力摘要，開播保留選定 codec、解析度與 FPS，MediaCodec 啟動失敗時保存候選編碼器錯誤 | 驗證廠商能力回報、實際開播與同 codec 的降級選項 | IMPLEMENTED | `BroadcastPreflightTest`、`SettingsPersistenceTest` 已編譯、release compile、lint | BLOCKED | 原回報手機未連接，CameraX 會依相機模式取得實際解析度 |
| 16 | 直播中調整畫質 | 最低／目標／最高碼率與 ABR 可整組套用。解析度／FPS 重建 CameraX 與影片 Track，收到新的關鍵影格才保存 | 驗證成功套用、編碼器拒絕、逾時復原及接收端解碼 | IMPLEMENTED | `VideoBitrateControlTest`、`LiveVideoModeChangeTest`、`VideoPublishQueueTest` | BLOCKED | 動態碼率與畫質組合由 MediaCodec 啟動及實際輸出確認 |
| 17 | 直播儀表板 | 簡易模式顯示直播時間、幀率、傳輸碼率與網路，專業模式顯示實際編碼器、尺寸、丟幀、佇列、RTT、電量、熱度與解碼結果 | 實機驗證版面與連續資料 | IMPLEMENTED | metrics tests、release compile | 未驗證 | 遠端解碼資料來自 App 的獨立訂閱連線 |
| 18 | 裝置健康／溫度 | 電量、充電、熱度、記憶體、省電、App CPU 用量與編碼表現進入監控。使用者可採用降碼率建議 | 真實溫度及負載驗收 | IMPLEMENTED | `DeviceHealthTest`、release compile、lint | 未驗證 | CPU 壓力由 App 使用率及實際編碼 FPS 判斷 |
| 19 | 畫質 Preset | 內建與自訂 Preset 可套用、建立、編輯、刪除、複製及重設 | 實機模式驗證 | IMPLEMENTED | `StreamPresetTest`、release compile | 未驗證 | CameraX 依支援模式擷取 |
| 20 | 完整離線 APK | core 快取、checksum、ABI／FFI 驗證與 fallback | 真正內含 compatible core 的離線 variant | IMPLEMENTED | `assembleOffline`、APK v2／zipalign、CoreLibraryFiles tests | 未驗證 | offline APK 約 35 MB，啟動時仍會將 asset 安全安裝到 app-private 目錄；需要 Android 實機確認 native loading |
| 21 | Release pipeline | 本機簽章、JVM、lint、APK 包名／版本／對齊／簽章檢查、GitHub tag／SHA-256 核對 | 正式簽章與遷移 | IN_PROGRESS | publish script、APK checks | BLOCKED | 目前公開 APK 沿用既有開發簽章 |
| 22 | 停止／Android lifecycle | App 可設定停止確認，通知有立即停止，直播取樣監控相機及音訊權限，Service 以 generation 辨識直播 | Android 背景、急停、權限撤銷及快速啟停實測 | IMPLEMENTED | session／settings tests | 未驗證 | 需各 Android 版本實機 |
| 23 | 認證／安全 | Keystore、token 分離、redaction、signature check | token permission probing、clear UI、錯誤分類擴充 | IMPLEMENTED | repository／redaction tests、release compile、lint | 未驗證 | Token 權限由 Relay 回應決定；設定頁可清除加密 JWT 與 Cloudflare Token |
| 24 | OBS 連接體驗 | Cloudflare watch／OBS text、remote verification | QR、Browser Source、native source guide、moq.pro guidance | IMPLEMENTED | `RelayProfileTest`、release compile、lint | BLOCKED | 真實 OBS／瀏覽器／moq.pro 接收能力仍需實測；QR 僅在獨立 Subscribe Token 與 `.hang` 名稱可用時提供 |
| 25 | 本機備援錄影 | — | MediaMuxer、分段、儲存監測、獨立錯誤處理 | NOT_STARTED | — | 未驗證 | — |
| 26 | 多鏡頭／防手震 | front/back、zoom、focus、torch、exposure | logical camera、stabilization、focus／exposure lock | PARTIAL | camera policy／geometry tests | 未驗證 | 需要裝置能力矩陣 |
| 27 | 遠端監控／控制 | — | 安全配對、可撤銷控制通道 | NOT_STARTED | — | 未驗證 | 需定義低維運傳輸方案 |
| 28 | Android 螢幕直播 | — | MediaProjection、screen source、rotation、playback audio | NOT_STARTED | — | 未驗證 | Android 權限與前景服務限制 |
| 29 | 直播疊圖 | GPU renderer 可作為整合點 | overlay model、clock、logo、opacity、同步預覽 | NOT_STARTED | — | 未驗證 | — |
| 30 | RTMP／SRT 備援 | — | gateway research 或獨立 publisher | NOT_STARTED | — | 未驗證 | 需避免第二路阻塞 MoQ |
| 31 | 輸出方向／進階相機 | 固定輸出方向、front mirror、exposure／focus | 直播中安全方向重配置與 metadata | PARTIAL | geometry tests | 未驗證 | H.264／H.265 receiver 需實測 |

## 本輪基線

- `assembleRelease` 與 `testDebugUnitTest` 在 v1.0.20 基線通過。
- 目前沒有連接 Android 裝置。端對端及長時間直播依 `docs/testing/END_TO_END.md` 的流程逐案例驗證。
- 01 與 10 已有操作流程及資料彙整工具，需取得實際裝置、Relay 與接收端的驗收資料。
