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
| 08 | 遠端媒體驗證／OBS | 有 Subscribe-only `SubscriberVerifier`，可驗證 catalog、video、audio decode | moq.pro 接收矩陣、OBS telemetry | TESTED | verifier 編譯與 JVM 規則 | BLOCKED | 無手機、Token、OBS |
| 09 | 主動異常警報 | 有錯誤 UI、前景通知與 diagnostics events | Alert manager、冷卻、歷史、通知與健康事件 | IMPLEMENTED | `StreamAlertManagerTest`、`StreamHealthTest` | 未驗證 | 目前使用 App 畫面與 diagnostics，背景震動／提示音尚未實測 |
| 10 | 長時間穩定性 | 有 2／4 小時測試矩陣、資源檢查及診斷合併工具 | 取得正式壓測與 leak 檢查紀錄 | IMPLEMENTED | ZIP 合併腳本以含缺口的資料驗證 | BLOCKED | 需要手機、OBS、Relay 憑證與測試時段 |
| 11 | 開播前檢查 | PASS／WARNING／ERROR 預檢已串入開播，硬體 surface 清單缺項會提示並允許實際啟動編碼器 | 以原回報裝置驗證 H.264／H.265 開播 | TESTED | `BroadcastPreflightTest` 重現能力清單誤擋，並檢查權限、核心與無效參數 | BLOCKED | 尚無原回報裝置 |
| 12 | 直播中靜音／增益 | 有持久 gain 與 PCM saturation | mute、gain ramp、背景通知狀態 | IMPLEMENTED | `AudioSettingsTest`、`AudioHealthMetricsTest` | 未驗證 | 需要裝置實測切換增益時的實際聽感 |
| 13 | 麥克風音量表 | AudioHealthMetrics 已計算 peak／靜音 | UI RMS／dBFS／peak hold | IMPLEMENTED | `AudioHealthMetricsTest` | 未驗證 | 聲道與削波顯示需要不同硬體麥克風實測 |
| 14 | 音訊輸入設備 | AudioRecord 使用預設裝置 | AudioManager 列表、偏好與拔除處理 | IMPLEMENTED | `AudioInputDeviceTest` 與 AudioRecord 編譯驗證 | 未驗證 | 需要多種實體音訊裝置實測路由與拔除回退 |
| 15 | 硬體編碼相容性 | DeviceCapabilityRepository 提供能力摘要，開播保留選定 codec、解析度與 FPS，MediaCodec 啟動失敗時保存候選編碼器錯誤 | 驗證廠商能力回報、實際開播與同 codec 的降級選項 | IMPLEMENTED | `BroadcastPreflightTest`、`SettingsPersistenceTest` 已編譯、release compile、lint | BLOCKED | 原回報手機未連接，CameraX 會依相機模式取得實際解析度 |
| 16 | 直播中調整畫質 | 可一次套用最低／目標／最高碼率與 ABR。編碼器接受才保存設定，拒絕時保留舊設定並提示 | 解析度／FPS 安全重建 | IN_PROGRESS | `VideoBitrateControlTest`、`VideoBitrateSettingsTest` | 未驗證 | 動態碼率以各裝置的 MediaCodec 為準 |
| 17 | 直播儀表板 | LiveStats 有 RTT、bitrate、bytes、FPS、codec | health、audio、queue、thermal 的簡易／專業模式 | PARTIAL | metrics tests、release compile | 未驗證 | 沒有 OBS 接收數據；目前已顯示 publisher queue、encoder bitrate 與 packet loss |
| 18 | 裝置健康／溫度 | diagnostics 已記錄 thermal、battery、memory | 主動警告與品質策略 | IMPLEMENTED | `DeviceHealthTest`、release compile、lint | 未驗證 | 部分 Android 無 thermal API；熱度／記憶體壓力提供使用者觸發的降低碼率操作，不自動改動品質 |
| 19 | 畫質 Preset | 內建與自訂 Preset、視訊與音訊設定驗證，套用後使用 CameraX 與實際編碼器檢查 | 明確的複製操作、實機模式驗證 | IN_PROGRESS | `StreamPresetTest`、release compile | 未驗證 | CameraX 依實際支援模式擷取 |
| 20 | 完整離線 APK | core 快取、checksum、ABI／FFI 驗證與 fallback | 真正內含 compatible core 的離線 variant | IMPLEMENTED | `assembleOffline`、APK v2／zipalign、CoreLibraryFiles tests | 未驗證 | offline APK 約 35 MB，啟動時仍會將 asset 安全安裝到 app-private 目錄；需要 Android 實機確認 native loading |
| 21 | Release pipeline | 本機 signed release、tag、checksum、GitHub Release | production key 安全遷移策略 | TESTED | publish script、APK checks | BLOCKED | 目前使用 debug key，沒有正式私鑰 |
| 22 | 停止／Android lifecycle | Service、generation、wake lock、screen off 已有處理 | stop confirmation、emergency stop、更多 race tests | TESTED | session／settings tests | 未驗證 | 需各 Android 版本實機 |
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
- v1.0.40 補上 01～05／07／10 的重連、Watchdog 與驗收工具。第 08／09 項仍在開發。
