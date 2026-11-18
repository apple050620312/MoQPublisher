# MoQ Publisher 實作追蹤

狀態只代表目前程式碼與可取得的測試證據。`TESTED` 不代表已在 Android 手機、Cloudflare 或 OBS 完成端對端驗證。

| 編號 | 功能 | 原始狀態與已有能力 | 缺少能力／本批次範圍 | 實作狀態 | 自動測試 | 實機驗證 | 已知限制 |
|---|---|---|---|---|---|---|---|
| 01 | 端對端直播測試基礎 | 有 JVM、遠端 verifier 與 release 流程 | 建立可重複的 moq.pro／Cloudflare／OBS 手動流程與測試紀錄 | IN_PROGRESS | 基線建置通過 | BLOCKED | 沒有 Android 裝置、OBS、有效 Relay 帳號 |
| 02 | 完整直播狀態機 | 有 Idle、Connecting、Live、Error 與 relay phase | 統一 publisher lifecycle 與遠端驗證狀態 | IMPLEMENTED | `BroadcastLifecycleTest` 與既有 RelayConnectionPolicyTest | 未驗證 | OBS 接收狀態仍由獨立 verifier 表達 |
| 03 | 直播 Watchdog | 有每秒 StreamMetrics 與 AudioHealthMetrics | 健康等級、門檻、遲滯、UI／診斷整合 | IMPLEMENTED | `StreamHealthTest`、`StreamMetricsTest`、`VideoPublishQueueTest` | 未驗證 | 需要長時間裝置資料校準 |
| 04 | 媒體管線自動恢復 | 有 cleanup、generation、keyframe recovery | 元件級有限重試與恢復協調器 | IMPLEMENTED | `BroadcastRecoveryTest`、`BroadcastLifecycleTest` | 未驗證 | 需要實機 Codec／CameraX／AudioRecord 故障情境 |
| 05 | 網路切換與重連 | 有 DefaultNetworkMonitor、QUIC reconnect、退避與 keyframe | 完整網路異常矩陣與 15 秒恢復量測 | IMPLEMENTED | RelayConnectionPolicyTest | BLOCKED | 未連接 Wi-Fi／4G／5G 與 Relay |
| 06 | ABR | 有 VideoBitrateControl、headroom、遲滯與動態 encoder bitrate | 解析度／FPS 動態切換與更多 queue age 規則 | TESTED | VideoBitrateControlTest | 未驗證 | Codec 是否支援動態 bitrate 依裝置而異 |
| 07 | 音畫同步 | 有 AudioTimeline、VideoTimestampMapper、reset 與 monotonic tests | 接收端延遲與音畫誤差量測 | TESTED | AudioTimeline、VideoTimestampMapper tests | BLOCKED | 端到端誤差不能以 RTT 代替 |
| 08 | 遠端媒體驗證／OBS | 有 Subscribe-only `SubscriberVerifier`，可驗證 catalog、video、audio decode | moq.pro 接收矩陣、OBS telemetry | TESTED | verifier 編譯與 JVM 規則 | BLOCKED | 無手機、Token、OBS |
| 09 | 主動異常警報 | 有錯誤 UI、前景通知與 diagnostics events | Alert manager、冷卻、歷史、通知與健康事件 | IMPLEMENTED | `StreamAlertManagerTest`、`StreamHealthTest` | 未驗證 | 目前使用 App 畫面與 diagnostics，背景震動／提示音尚未實測 |
| 10 | 長時間穩定性 | 有 queue、資源 cleanup、metrics | 2／4 小時正式回歸紀錄與 leak 檢查 | NOT_STARTED | 可執行 JVM regression | BLOCKED | 需要實機與長時間環境 |
| 11 | 開播前檢查 | MainViewModel 已驗證權限、核心、relay、codec 設定 | 統一 PASS／WARNING／ERROR preflight 報告 | IMPLEMENTED | `BroadcastPreflightTest` 與既有設定 tests | 未驗證 | 真實設備能力與廠牌熱度資料需在 Android 執行 |
| 12 | 直播中靜音／增益 | 有持久 gain 與 PCM saturation | mute、gain ramp、背景通知狀態 | IMPLEMENTED | `AudioSettingsTest`、`AudioHealthMetricsTest` | 未驗證 | 需要裝置實測切換增益時的實際聽感 |
| 13 | 麥克風音量表 | AudioHealthMetrics 已計算 peak／靜音 | UI RMS／dBFS／peak hold | IMPLEMENTED | `AudioHealthMetricsTest` | 未驗證 | 聲道與削波顯示需要不同硬體麥克風實測 |
| 14 | 音訊輸入設備 | AudioRecord 使用預設裝置 | AudioManager 列表、偏好與拔除處理 | IMPLEMENTED | `AudioInputDeviceTest` 與 AudioRecord 編譯驗證 | 未驗證 | 需要多種實體音訊裝置實測路由與拔除回退 |
| 15 | 硬體編碼相容性 | CameraX mode、codec／resolution errors、diagnostics | 統一 DeviceCapabilityRepository 與 UI 報告 | PARTIAL | release compile、JVM regression | 未驗證 | codec 清單與動態碼率能力依手機而異；preflight 只確認硬體 MIME／surface 能力，實際解析度與幀率由 MediaCodec.configure() 判斷 |
| 16 | 直播中調整畫質 | bitrate／ABR 可在 encoder 支援時更新 | min／max 設定與解析度／FPS 安全重建 | PARTIAL | bitrate tests、release compile | 未驗證 | MediaCodec 動態能力不同；目前支援直播中 target／ABR，解析度與 FPS 仍需重新開播 |
| 17 | 直播儀表板 | LiveStats 有 RTT、bitrate、bytes、FPS、codec | health、audio、queue、thermal 的簡易／專業模式 | PARTIAL | metrics tests、release compile | 未驗證 | 沒有 OBS 接收數據；目前已顯示 publisher queue、encoder bitrate 與 packet loss |
| 18 | 裝置健康／溫度 | diagnostics 已記錄 thermal、battery、memory | 主動警告與品質策略 | PARTIAL | `DeviceHealthTest`、release compile | 未驗證 | 部分 Android 無 thermal API；目前提供警報與狀態顯示，未自動降低品質 |
| 19 | 畫質 Preset | 持久化個別 video／audio 設定 | Preset CRUD 與能力檢查 | IMPLEMENTED | `StreamPresetTest`、release compile | 未驗證 | 真實硬體 encoder 與相機模式需在 Android 裝置確認；內建 Preset 會使用 CameraX 的最近可用模式 |
| 20 | 完整離線 APK | core 快取、checksum、ABI／FFI 驗證與 fallback | 真正內含 compatible core 的離線 variant | IMPLEMENTED | `assembleOffline`、APK v2／zipalign、CoreLibraryFiles tests | 未驗證 | offline APK 約 35 MB，啟動時仍會將 asset 安全安裝到 app-private 目錄；需要 Android 實機確認 native loading |
| 21 | Release pipeline | 本機 signed release、tag、checksum、GitHub Release | production key 安全遷移策略 | TESTED | publish script、APK checks | BLOCKED | 目前使用 debug key，沒有正式私鑰 |
| 22 | 停止／Android lifecycle | Service、generation、wake lock、screen off 已有處理 | stop confirmation、emergency stop、更多 race tests | TESTED | session／settings tests | 未驗證 | 需各 Android 版本實機 |
| 23 | 認證／安全 | Keystore、token 分離、redaction、signature check | token permission probing、clear UI、錯誤分類擴充 | PARTIAL | repository／redaction tests | 未驗證 | Token 權限由 Relay 回應決定 |
| 24 | OBS 連接體驗 | Cloudflare watch／OBS text、remote verification | QR、Browser Source、native source guide、moq.pro guidance | PARTIAL | RelayProfileTest | BLOCKED | OBS 相容性需實測 |
| 25 | 本機備援錄影 | — | MediaMuxer、分段、儲存監測、獨立錯誤處理 | NOT_STARTED | — | 未驗證 | — |
| 26 | 多鏡頭／防手震 | front/back、zoom、focus、torch、exposure | logical camera、stabilization、focus／exposure lock | PARTIAL | camera policy／geometry tests | 未驗證 | 需要裝置能力矩陣 |
| 27 | 遠端監控／控制 | — | 安全配對、可撤銷控制通道 | NOT_STARTED | — | 未驗證 | 需定義低維運傳輸方案 |
| 28 | Android 螢幕直播 | — | MediaProjection、screen source、rotation、playback audio | NOT_STARTED | — | 未驗證 | Android 權限與前景服務限制 |
| 29 | 直播疊圖 | GPU renderer 可作為整合點 | overlay model、clock、logo、opacity、同步預覽 | NOT_STARTED | — | 未驗證 | — |
| 30 | RTMP／SRT 備援 | — | gateway research 或獨立 publisher | NOT_STARTED | — | 未驗證 | 需避免第二路阻塞 MoQ |
| 31 | 輸出方向／進階相機 | 固定輸出方向、front mirror、exposure／focus | 直播中安全方向重配置與 metadata | PARTIAL | geometry tests | 未驗證 | H.264／H.265 receiver 需實測 |

## 本輪基線

- `assembleRelease` 與 `testDebugUnitTest` 在 v1.0.20 基線通過。
- 目前沒有連接 Android 裝置，不能把真實 Cloudflare、moq.pro 或 OBS 行為標示為通過。
- 下一個開發批次先完成 11，再處理 12～18 的產品化整合。
