# 待辦事項開始
你是一名資深 Android、Kotlin、即時影音串流、MediaCodec、CameraX、Opus、QUIC、MoQ（Media over QUIC）及 OBS 整合工程師。

請針對目前工作目錄中的 MoQPublisher 專案，完成一次全面性的正式直播軟體升級。

專案：
https://github.com/apple050620312/MoQPublisher

開發目標：
將目前已具備基本直播能力的 MoQPublisher，升級為可長時間穩定執行的專業 Android 行動直播應用程式。

核心使用情境：
Android 手機 → Wi-Fi / 4G / 5G → moq.pro 或 Cloudflare Relay → 電腦 OBS

本次工作包含 P0、P1、P2 共 31 項需求，必須依照以下優先順序開發。

========================================
一、最高原則
========================================

1. 先完整閱讀目前專案的原始碼、README、架構、依賴、測試、CI/CD 及已知限制。
2. 以實際程式碼為準，不要只相信 README。
3. 如果功能已完整實作且符合需求，保留原實作，不要重寫。
4. 如果功能已有部分實作，優先擴充現有程式。
5. 只有確實缺少的能力才新增模組。
6. 不要為了重構而重構。
7. 不要破壞現有 moq.pro 與 Cloudflare Profile。
8. 不要隨意改變目前的 MoQ、Opus、MediaCodec、CameraX 或 GPU Rendering 核心架構。
9. 保留繁體中文與英文介面。
10. 新增設定應合理保存，並兼容既有使用者資料。
11. 不允許在直播主執行緒進行可能造成卡頓的操作。
12. 所有非同步資源必須有正確的生命週期、取消、逾時及釋放機制。
13. 不允許因非核心功能失敗而無條件停止主要直播。
14. 嚴禁在程式碼、Log、診斷檔中洩漏真實 Token 或其他敏感資料。
15. 優先使用 Android 官方 API 與目前專案既有依賴。
16. 不要引入不必要的大型第三方框架。
17. 不要假設某個 Android API 或 Codec 在全部手機上都可使用。
18. 不要假設 Relay 連線成功就代表 OBS 成功接收。
19. 對無法在目前環境進行的實機測試，明確標示未驗證，禁止假稱通過。
20. 禁止以 Mock 成功取代真正的端對端成功。
21. 不要只輸出規劃文件或 TODO，必須實際實作。
22. 不需要每完成一個小步驟就詢問我；請依照優先順序自主推進。
23. 若遇到無法完成的外部依賴，記錄阻礙並繼續其他不依賴它的項目。
24. 所有修改都需要兼顧效能、穩定性、安全性及可維護性。
25. 禁止為了讓測試通過而移除測試、跳過關鍵檢查或隱藏錯誤。

========================================
二、開始實作前的工作
========================================

首先檢查目前實際存在的檔案與類別，例如：

- MoqStreamer
- BroadcastSession
- BroadcastService
- CameraCaptureEngine
- CameraSurfaceRenderer
- SurfaceVideoEncoder
- VideoBitrateControl
- AudioTimeline
- VideoTimestampMapper
- SubscriberVerifier
- StreamMetrics
- RelayProfile
- MainActivity
- MainViewModel
- 相關設定與診斷模組

以上名稱僅是調查線索，實際位置和架構以目前專案為準。

建立 docs/IMPLEMENTATION_TRACKER.md：

- 需求編號
- 功能名稱
- 原始狀態
- 已有功能
- 缺少能力
- 預計修改範圍
- 實作狀態
- 自動測試結果
- 實機驗證狀態
- 已知限制

狀態使用：
NOT_STARTED
IN_PROGRESS
IMPLEMENTED
TESTED
BLOCKED

IMPLEMENTED 不等於 TESTED。

另外建立 docs/ARCHITECTURE.md，記錄重要元件、資料流及生命週期關係。

完成基線測試，確認修改前專案的建置狀態。

完成以上調查後，直接開始實作，不要停在規劃階段。

========================================
P0：直播核心可靠性
========================================

以下 01～10 是最高優先級。

【01】端對端直播測試基礎

建立可重複執行的測試與驗證流程。

需求：

- 測試 moq.pro Publisher。
- 測試 Cloudflare Publisher。
- 檢查各自的 Publish / Subscribe 認證。
- 驗證 H.264 / H.265 與 Opus 相容性。
- 提供 OBS 接收測試步驟。
- 測試 Browser Source 和可用的原生 MoQ Source。
- 記錄開始播放時間。
- 量測端到端延遲。
- 量測音畫同步誤差。
- 模擬斷線、網路切換和 Relay 異常。
- 保存測試設備及版本資訊。
- 建立 docs/testing/。

能自動化的部分應自動化。

OBS、Android 實機及真實 Relay 測試若無法在目前環境執行，建立可直接操作的測試程序並明確標示未驗證。

【02】完整直播狀態機

改善目前直播成功狀態的定義。

建立適當的 State Machine，至少能表達：

- Idle
- Preparing
- Connecting
- Announced
- WaitingForSubscriber
- Publishing
- Degraded
- Reconnecting
- Recovering
- Failed
- Stopping

遠端媒體驗證狀態需與 Publisher 連線狀態分開。

要求：

- Relay Connected 不等於 OBS Connected。
- Announced 不等於媒體正在發布。
- 沒有 Subscriber 不代表直播故障。
- 重連不能錯誤顯示正常。
- UI、Service、診斷系統共用一致的狀態來源。
- 避免非法狀態轉換。
- 避免重複建立直播 Session。
- 所有狀態轉換加入測試。

【03】直播 Watchdog 與健康監控

新增 StreamHealthMonitor 或符合現有架構的等效模組。

持續監測：

- Camera Frame Activity
- GPU Rendering Activity
- Video Encoder Output
- Video Publishing Activity
- Audio Capture Activity
- Audio Publishing Activity
- Relay Connectivity
- FPS
- Dropped Frames
- Queue Depth
- Queue Media Age
- RTT
- Bitrate
- Reconnection Count

健康狀態至少包含：

HEALTHY
WARNING
CRITICAL
RECOVERING

建議初始門檻：

- 有影像需求時 3 秒沒有新相機影格：Warning。
- 相機正常但 3 秒沒有編碼影格：Critical。
- 有影像需求時 5 秒沒有成功發布影格：Critical。
- 預期音訊存在但 2 秒無音訊擷取：Warning。
- 10 秒內 FPS 持續低於目標 70%：Warning。

門檻需可依裝置能力及實際情況調整。

注意：

- 不得把沒有 Subscriber 誤判為故障。
- 不得把正常靜音誤判為麥克風故障。
- 不能破壞現有統計採樣。
- 避免高頻率輪詢造成耗電。
- 使用遲滯與警報冷卻機制。

【04】媒體管線自動故障恢復

新增 BroadcastRecoveryCoordinator 或等效機制。

支援：

- Camera Session Restart
- CameraX Rebind
- EGL / GPU Surface Recovery
- MediaCodec Reinitialization
- AudioRecord Reinitialization
- Audio Device Recovery
- Video Publish Queue Recovery
- Keyframe Recovery

錯誤分類：

Recoverable
Transient
Fatal

要求：

- 優先只修復故障元件。
- 不要每次發生錯誤就停止整場直播。
- 修復時不得形成重複 Camera / Encoder / AudioRecord。
- 有限次重試及指數退避。
- 避免無限恢復循環。
- 手動停止時取消全部恢復程序。
- 使用 Session / Generation 隔離，避免舊回呼破壞新資源。
- 編碼器恢復後請求 IDR / Keyframe。
- 必要時正確更新 MoQ Track / Catalog。
- 保持音訊時間戳一致。
- 修復結果必須反映在 Watchdog、UI 和診斷紀錄。

確實無法恢復時，才安全結束直播。

【05】網路切換與斷線重連

保留並強化目前已有的網路重連邏輯。

支援：

- Wi-Fi → 4G / 5G
- 4G / 5G → Wi-Fi
- 短暫完全斷網
- Relay 主動斷線
- IP 改變
- DNS 失敗
- Authentication Failure
- 網路高延遲或高丟包

要求：

- 不重複建立 Publisher。
- 防止新舊 Session 互相干擾。
- 清除過期影格。
- 不補送造成高延遲的舊封包。
- 重新公告必要的 Broadcast / Track 資訊。
- 重連後產生新的可解碼關鍵影格。
- 確保音訊和影片恢復。
- 記錄斷線時間及恢復時間。
- Token 無效時不能無限制重試。
- moq.pro 與 Cloudflare 的傳輸差異分開處理。

目標：網路恢復可用後，盡可能在 15 秒內重新提供可解碼畫面。

【06】ABR 自適應位元率強化

保留並改善現有 VideoBitrateControl。

新增：

- Minimum Bitrate
- Target Bitrate
- Maximum Bitrate
- Fast Decrease
- Slow Increase
- Congestion Detection
- Queue Age Detection
- Bitrate Hysteresis
- Manual / Auto Mode

參考預設：

480p30：600～1200 Kbps
720p30：1500～3000 Kbps
1080p30：3000～5000 Kbps
1080p60：5000～8000 Kbps

必須允許使用者自訂，不可強制使用固定值。

要求：

- 網路狀況惡化時快速下降。
- 網路恢復時緩慢上升。
- 避免碼率震盪。
- 區分編碼碼率和實際傳輸碼率。
- 不以網路類型直接等同可用頻寬。
- MediaCodec 不支援動態碼率時，提供安全降級策略。
- 解析度與 FPS 動態切換需考慮 Encoder / Track 重建。
- 不允許 ABR 無限制累積佇列。

【07】音畫同步與延遲控制

強化現有 AudioTimeline 和 VideoTimestampMapper。

要求：

- 使用合理的共同時間基準。
- 正確轉換 Camera Sensor Timestamp。
- 正確處理 Audio Timestamp。
- 保證 PTS 單調性。
- 處理 Camera Rebind。
- 處理 Encoder Restart。
- 處理 Network Reconnect。
- 處理 Camera Switching。
- 處理 Timestamp Discontinuity。
- 防止影像佇列累積過期影格。
- 避免音訊長時間漂移。
- 提供同步與延遲的診斷資料。

建議音畫同步驗收目標：±150 ms。

實際端到端延遲需要從接收端量測，不可用 RTT 冒充。

【08】遠端媒體驗證與 OBS 接收確認

擴充現有 SubscriberVerifier。

保留現有 Cloudflare Subscribe-only 設計。

驗證階段至少包含：

- Connection
- Authentication
- Catalog
- Track Subscription
- Media Received
- Video Decoded
- Audio Decoded

要求：

- 明確區分各種失敗原因。
- 能偵測 Token 無效或權限不足。
- 能偵測收到媒體但不能解碼。
- 支援開播後進行短時媒體驗證。
- 重連或改變 Codec 後，原驗證狀態應失效。
- 不影響主要 Publisher。
- 評估 moq.pro 對應的接收驗證方式。
- 建立 OBS 相容性矩陣。
- 檢查實際 MoQ Draft / Hang / Codec 相容性。
- 不將遠端測試成功直接標示為 OBS 成功。

若要取得 OBS 真實接收狀態，設計接收端 Telemetry Adapter。

【09】主動異常警報

新增 StreamAlertManager。

支援：

- Network Degraded
- Stream Stalled
- Video Encoder Failure
- Audio Capture Failure
- Relay Disconnected
- Reconnecting
- Recovery Failed
- Recovery Successful
- Device Overheating
- Low Battery

警報方式：

- App 主畫面提示。
- 顏色／狀態文字。
- 可設定震動。
- 可設定提示音。
- 背景通知。
- 警報歷史。

要求：

- 不重複轟炸使用者。
- 重要錯誤優先顯示。
- 錯誤恢復時解除警報。
- 不使用 Exception 原文作為唯一的使用者訊息。
- 不影響影像與音訊執行緒。

【10】長時間穩定性與回歸測試

建立正式驗收流程。

至少涵蓋：

- 2 小時連續直播。
- 4 小時壓力測試。
- 720p30。
- 1080p30。
- 裝置支援時測試 1080p60。
- Camera Switching。
- Background Streaming。
- Screen Off。
- Network Reconnect。
- Encoder Recovery。
- Audio Recovery。
- CPU / Memory / Thermal Monitoring。
- Queue Growth。
- MediaCodec Resource Leaks。
- AudioRecord Resource Leaks。
- Native MoQ Resource Leaks。

自動化可測部分。

建立正式回歸測試紀錄。

========================================
P1：正式直播操作與產品完整性
========================================

完成 P0 的核心實作後，再開發以下需求。

【11】開播前檢查

建立 BroadcastPreflight。

檢查：

- Camera Permission
- Microphone Permission
- Notification Permission
- Camera Availability
- Audio Device Availability
- Encoder Capability
- Resolution / FPS Compatibility
- Relay URL
- Publish Token
- Subscribe Token（適用時）
- Network Availability
- MoQ Core Availability
- Battery / Thermal Warning

區分：

PASS
WARNING
ERROR

區分開播前檢查與開播後媒體解碼驗證，不要強迫尚未發布的媒體通過解碼測試。

【12】直播中靜音與音量增益

新增：

- Mute / Unmute
- 即時 Gain Adjustment
- Gain Ramp
- Mute State
- UI Feedback

要求：

- 不需要停止直播。
- 不需要重新建立 MoQ Session。
- 靜音時維持合法音訊時間線。
- 優先持續送出合法靜音音訊封包。
- 操作必須執行緒安全。
- 避免切換時的爆音。
- 背景通知能反映靜音狀態。

【13】麥克風音量表

新增 Audio Level Meter。

包含：

- PCM Peak
- RMS
- dBFS
- Peak Hold
- Stereo Level
- Clipping Detection
- Silence Monitoring

在主畫面顯示即時音量。

避免在 UI Thread 進行 PCM 計算。

不要將安靜環境直接判定為麥克風故障。

【14】音訊輸入設備選擇

使用 Android AudioManager 與 AudioDeviceInfo。

支援可用的：

- Built-in Microphone
- Wired Headset
- USB Microphone
- USB Audio Interface
- Bluetooth Microphone

要求：

- 列出可用設備。
- 選擇偏好輸入。
- 驗證實際音訊路由。
- 偵測設備拔除。
- 嘗試切換備援設備。
- 正確處理裝置權限。
- 不支援時清楚提示。
- 盡可能避免中斷主要直播。

【15】硬體編碼相容性檢查

建立 DeviceCapabilityRepository。

檢查：

- H.264
- H.265
- Hardware Encoder
- Resolution
- FPS
- Bitrate Range
- Supported Camera Modes
- Dynamic Bitrate Support

要求：

- 開播前確認可用組合。
- 顯示實際選用的 Encoder。
- 無法支援時提供合理降級。
- 預設優先 H.264 相容性。
- 不要假設 H.265 在所有 OBS 接收端可用。
- 儲存能力診斷資訊。

【16】直播中調整畫質

支援即時：

- Target Bitrate
- ABR Enable / Disable
- Minimum Bitrate
- Maximum Bitrate

要求：

- 不必重啟整場直播。
- 與 ABR 控制器保持一致。
- 失敗時回復原本有效設定。
- 顯示實際生效的 Bitrate。
- 不支援動態修改時清楚提示。
- 解析度／FPS 調整需使用安全的重新配置流程。

【17】直播儀表板

建立簡易模式與專業模式。

簡易模式：

- Live Duration
- Health Status
- FPS
- Bitrate
- Network
- Audio Level
- Warnings

專業模式：

- Video Codec
- Resolution
- FPS Target / Actual
- Encoder Name
- Encoder Bitrate
- Upload Throughput
- RTT
- Packet Loss
- Dropped Frames
- Queue Depth
- Queue Media Age
- Reconnection Count
- Battery
- Thermal Status
- Relay Status
- Remote Decode Verification
- Diagnostics Export

若沒有 OBS Telemetry，不要顯示虛構的 OBS 接收數據。

【18】裝置健康與溫度保護

建立 DeviceHealthMonitor。

監測：

- Battery Level
- Charging Status
- Android Thermal Status
- Power Saving Mode
- CPU Pressure
- Memory Pressure
- Encoder Performance

要求：

- 熱節流時主動警告。
- 適當調整直播品質。
- 不造成持續反覆重建 Encoder。
- 不假設所有設備都提供實際溫度。
- 低電量時警告。
- 保留使用者對品質模式的控制權。

【19】直播畫質 Preset

建立 StreamPreset。

內建：

- Data Saver
- Mobile Streaming
- High Quality
- High FPS
- Custom

包含：

- Resolution
- FPS
- Codec
- Target Bitrate
- ABR
- Audio Quality

支援：

- 建立。
- 編輯。
- 刪除。
- 複製。
- 儲存。
- 恢復預設。

畫質 Preset 與 Relay Profile 分開管理。

所有 Preset 必須經裝置能力檢查。

【20】完整離線 APK

保留 Compact APK 的 MoQ Core 下載機制。

新增：

- Full / Offline APK
- 可選 ABI 分包
- Core Version Compatibility Check
- Core Integrity Verification
- Download Failure Handling
- Existing Core Fallback
- Core Installation Status UI

要求：

- Full APK 不需要首次下載 MoQ Core。
- 不在直播期間替換正在使用的核心。
- 不破壞既有 Compact APK。
- 不將不相容的 Core 與 Kotlin FFI 打包。
- 確保核心來源及版本可追溯。

【21】正式簽章與 Release Pipeline

改善發行流程。

要求：

- Production Signing Key。
- 禁止提交私鑰。
- 正式版／測試版分離。
- Version Code / Name 一致。
- Git Tag 對應。
- APK Build Validation。
- Unit Tests。
- Lint。
- Signature Verification。
- SHA-256 Verification。
- Release Notes。
- Upgrade Compatibility。

注意既有 APK 如使用其他簽章，可能無法直接覆蓋更新。

必須提供安全遷移策略。

不要在沒有正式金鑰的情況下捏造可用的正式簽章。

【22】停止直播與 Android 生命週期

改善：

- Stop Confirmation / Long Press Option
- Emergency Stop
- BroadcastService Lifecycle
- Foreground Notification
- Activity Rebinding
- Screen Off Behavior
- Permission Revocation
- Session Cleanup
- Rapid Start / Stop Race Conditions

要求：

- Activity 結束不應無條件停止背景直播。
- 不重複啟動直播 Session。
- 不洩漏 Wake Lock。
- 手動停止後不觸發自動恢復。
- 遵守不同 Android 版本的背景相機、麥克風及前景服務限制。

【23】認證與安全強化

保留：

- Android Keystore。
- 加密 Token 儲存。
- Publish / Subscribe Token 分離。
- 診斷資訊遮蔽。

強化：

- Token 權限檢查。
- TLS Validation。
- Sensitive URL Redaction。
- Diagnostic Export Safety。
- Token Clearing。
- Credential Exposure Prevention。
- Invalid Token Handling。
- Authorization Error Classification。

禁止讓 Publish Token 出現在一般觀看連結中。

【24】OBS 連接體驗

針對不同 Relay 提供對應的 OBS 連線資訊。

支援：

- Copy Relay URL
- Copy Broadcast Name
- Copy Subscribe URL
- QR Code
- Browser Source Guide
- Native MoQ Source Guide
- Receive Compatibility Warning
- Remote Decode Test
- Connection Troubleshooting

確認實際 OBS 接收能力後再顯示相容性。

========================================
P2：專業直播進階能力
========================================

P2 的功能必須模組化，不能因新增功能破壞 P0／P1 的穩定性。

【25】本機備援錄影

新增 LocalRecordingManager。

需求：

- Stream Only
- Record Only
- Stream + Record
- 網路中斷持續錄影。
- 優先重複使用現有編碼影像。
- 必要時將 PCM 額外編碼 AAC 供 MP4 使用。
- MediaMuxer / 相容封裝方案。
- 分段錄影。
- 儲存空間警告。
- 錄影失敗不停止 MoQ。
- 正確處理錄影檔完成及異常關閉。
- 音畫同步。

【26】多實體鏡頭與防手震

支援可用的：

- Ultra-wide Camera
- Main Camera
- Telephoto Camera
- Front Camera
- Optical / Logical Camera Selection
- Stabilization
- Focus Lock
- Exposure Lock
- Zoom

要求：

- 裝置能力檢查。
- 切換鏡頭時保持 Broadcast Session。
- 正確更新解析度與時間戳。
- OBS 可以繼續解碼。
- 不強制啟用不支援的硬體功能。

【27】遠端監控與控制

設計安全的遠端管理介面。

支援：

- 查看直播狀態。
- 查看 FPS / Bitrate / RTT。
- 查看電池和溫度。
- 查看警報。
- Remote Mute。
- Remote Bitrate Adjustment。
- Remote Verification。
- 可選遠端鏡頭切換。
- 經授權的 Remote Stop。

要求：

- 安全配對。
- 可撤銷授權。
- 不暴露 Publish Token。
- 監控失敗不影響直播。
- 不必為此要求使用者自行部署伺服器；優先設計可選、低維運成本的方案。

【28】Android 螢幕直播

新增 ScreenCaptureEngine。

使用 MediaProjection。

支援：

- Camera / Screen Source Selection
- Screen Video Capture
- Microphone Audio
- Android 支援時的 Playback Audio Capture
- Screen Rotation
- Resolution Changes
- Permission Revocation
- Foreground Service Compliance

沿用現有編碼和 MoQ 管線。

【29】直播疊圖

新增 GPU Overlay 系統。

支援：

- Logo
- Watermark
- Text
- Clock
- Stream Timer
- Position
- Scale
- Opacity
- Visibility Toggle

要求：

- 優先沿用既有 GPU Renderer。
- 不為每個影格重建 Bitmap。
- 不阻塞相機擷取。
- 預覽與實際輸出一致。
- 直播中安全開關。

【30】RTMP / SRT 備援

保留 MoQ 為主要輸出。

優先研究並整合已有的 MoQ → RTMP / SRT Gateway 方案。

若需 Android 原生多協定推流，才建立額外 Publisher。

要求：

- 每個輸出獨立連線管理。
- 獨立佇列。
- 獨立錯誤處理。
- 避免重複編碼。
- 避免次要輸出阻塞 MoQ。
- 顯示總上傳頻寬。
- 不增加不必要的雲端或自架伺服器依賴。

【31】直播輸出方向與進階相機控制

區分：

- UI Orientation
- Preview Orientation
- Encoded Video Orientation

保留固定輸出方向功能。

額外支援可選擇的直播中輸出方向調整。

要求：

- GPU Geometry Update
- Encoder Reconfiguration
- Track Metadata Update
- Keyframe Handling
- OBS Compatibility
- Camera Capability Checking
- Front Camera Mirror Handling
- Focus / Exposure Controls

不能因手機單純旋轉就無預警破壞 OBS 畫面。

========================================
三、架構與工程品質要求
========================================

請依實際程式碼決定最合理的模組分層。

建議考慮：

Capture Layer
Encoding Layer
Media Timeline Layer
MoQ Transport Layer
Broadcast Session Layer
Recovery Layer
Health Monitoring Layer
Diagnostics Layer
Settings Layer
UI Layer

但不要為了符合這份建議而強迫重構整個專案。

所有跨執行緒狀態必須安全。

建議使用 Kotlin Coroutines、Flow / StateFlow 或現有專案一致的架構。

避免：

- GlobalScope
- 無限制重試
- 無上限 Queue
- Blocking Main Thread
- 不必要的高頻 Polling
- 重複 MediaCodec
- 重複 AudioRecord
- 重複 Camera Session
- 重複 MoQ Publisher
- 資源釋放競態
- 過期 Callback 更新新 Session
- 靜默吞掉例外
- 敏感 Token 寫入 Log

直播開始、停止、重連和修復必須具有清楚的所有權與同步機制。

所有額外功能應遵守「主要直播優先」原則。

========================================
四、UI / UX 要求
========================================

保留目前的設計風格及主要操作流程。

新功能要整合到合理的位置，不要無限制把所有設定堆進首頁。

建議：

主直播畫面：
- 相機預覽
- 直播狀態
- 開始／停止
- 靜音
- 音量表
- FPS
- Bitrate
- 網路健康
- 重要警報

進階監控：
- 詳細傳輸數據
- 編碼器資訊
- Queue / RTT
- 溫度與電池
- 遠端驗證
- 診斷匯出

設定：
- Relay Profile
- Video Preset
- Audio Input
- ABR Settings
- Alerts
- Recording
- Advanced Camera
- Security

要求：

- 繁體中文與英文完整支援。
- 深色／淺色模式正常。
- 直式／橫式版面正常。
- 適當的無障礙標籤。
- UI 更新不影響編碼效能。
- 不依賴大量重組或高頻畫面重繪。
- 對不支援的功能提供清楚說明。

========================================
五、測試、驗收與品質門檻
========================================

每一項功能都必須至少完成：

1. 程式碼實作。
2. 相關測試。
3. 錯誤情境處理。
4. 資源釋放檢查。
5. UI／狀態整合。
6. 診斷資訊整合。
7. 文件更新。

每個開發批次後執行適用的：

- Gradle Build
- Unit Tests
- Android Lint
- Kotlin Compilation
- Existing Regression Tests
- New Tests

不能執行的測試請標記原因。

禁止將以下情況標示為完成端對端驗證：

- 只有編譯成功。
- 只有 Mock 測試成功。
- 只有 Relay Connected。
- 只有收到壓縮媒體，沒有成功解碼。
- 只有手機預覽正常。
- 沒有實際測試 OBS 接收。

對需要手機、OBS、外部 Relay、正式簽章金鑰或真實服務帳號的項目，實作可驗證部分，並提供明確人工驗收程序。

========================================
六、執行順序
========================================

請依以下批次實作：

Batch 1：
01～03
測試基線、直播狀態機、Watchdog。

Batch 2：
04～05
媒體自動恢復、網路重連強化。

Batch 3：
06～10
ABR、同步、遠端驗證、警報、穩定性測試。

Batch 4：
11～16
開播前檢查、音訊控制、音量表、設備選擇、硬體檢查、直播中畫質調整。

Batch 5：
17～20
儀表板、裝置健康、Preset、完整離線 APK。

Batch 6：
21～24
發行安全、生命週期、認證、OBS 整合。

Batch 7：
25～31
本機錄影、多鏡頭、遠端控制、螢幕直播、疊圖、備援協定、動態方向。

優先順序：

P0 > P1 > P2

如果某個項目依賴尚未完成的底層功能，可以先完成必要依賴，但必須清楚記錄原因。

每個批次完成後：
- 執行可用測試。
- 更新 IMPLEMENTATION_TRACKER.md。
- 更新相關文件。
- 整理修改內容。
- 建立獨立 Git Commit。

不要自動 Push、Force Push、改寫既有 Git 歷史或建立 Release，除非目前工作環境已有明確授權。

如果目前執行環境無法在一次工作期間完成全部 31 項：

- 優先交付真正可用、經過測試的 P0。
- 不要只做每項功能的空殼。
- 不要以部分實作冒充完成。
- 清楚保留下一階段待辦。
- 最終回報尚未完成的項目。
- 不要為了追求需求完成數量犧牲直播核心的可靠性。

========================================
七、最終回報格式
========================================

完成後以繁體中文回報：

1. 專案目前實際架構與重要發現。
2. 已完成項目，依 01～31 編號排列。
3. 已有功能而保留不修改的項目。
4. 主要新增及修改的檔案。
5. 各項功能的實作方式。
6. 建置結果。
7. 單元測試結果。
8. Android / OBS / Relay 實機驗證結果。
9. 無法驗證的部分。
10. 已知問題及限制。
11. Git Commit 清單。
12. 尚未完成的項目及下一步。

禁止聲稱未經測試的功能已在真實 Android 手機或 OBS 正常運作。

現在請先檢查專案的最新實際原始碼，確認每個需求與現有功能的差異，建立追蹤文件，然後直接開始 Batch 1，並依序持續完成後續批次。

目標不是產生一份看起來完整的規劃，而是持續交付真正可建置、可測試、可維護，並最終能夠長時間穩定直播的 MoQPublisher。

========================================
八、待辦事項使用方法
========================================
當你確定完成一個工作後，把那個待辦事項的內容編輯為 "此待辦已完成！請做其他項"
# 待辦事項結束

# MoQ Publisher Agent 工作規範

## 每次工作結束前必做

1. 先檢查 `git status`、目前版本與遠端分支，避免覆蓋未提交的修改。
2. 搜尋本次修改涉及的文字資源，確認 UTF-8、XML 與繁體中文沒有亂碼或替代字元。
3. 執行與修改相關的單元測試；Android 行為或資源修改要至少建置 `assembleRelease`。
4. 若修改版本、APK、更新流程或發佈流程，確認 `versionCode`、`versionName`、README 與 GitHub Release 標籤一致。
5. 在本檔案的「工作紀錄」加入日期、完成事項、驗證結果與尚未能在實機驗證的項目。
6. 建立清楚的 Git commit，並將 commit push 到 `origin/main`。
7. 修改 App 更新時，確認 APK 與診斷 FileProvider 使用不同元件類別；保留 URI 讀取授權、未知來源安裝導引、版本／包名／簽章驗證與直播期間安裝限制。

## 發佈規則

- 主程式版本使用 `v<versionName>` Git tag，例如 `v1.0.17`。
- `publish-release.ps1` 在本機建置 signed release APK、執行 JVM 測試、產生 SHA-256 sidecar，並用 `gh release create` 建立 GitHub Release；不要依賴 GitHub Actions runner。
- Release APK 檔名固定為 `MoQPublisher-<versionName>-release.apk`；App 內更新器只接受此命名、官方 repository 路徑與 SHA-256 驗證通過的檔案。
- release APK 使用本機 debug signing key，以便覆蓋既有測試安裝；正式商店發佈前必須替換成正式私鑰。

## 工作紀錄

### 2026-11-17

- 將 adaptive launcher artwork 縮入圓形遮罩的安全區，保留箭頭與 MoQ 字樣完整。
- 新增 GitHub Releases 主程式更新器：啟動與回到前景時檢查、使用者停止直播後下載、驗證 SHA-256，最後交給 Android Package Installer 安裝。
- 新增本機 `publish-release.ps1` 發佈腳本、更新資源、FileProvider 路徑與更新器單元測試。
- 驗證：`assembleCompact`、`testDebugUnitTest`；尚未在實機確認不同廠牌 launcher 的實際圓形遮罩與安裝提示。
- 已以本機建置並上傳 `v1.0.16` GitHub Release；APK SHA-256 為 `fa217facdd0ece53f3c07b979cf3a3525f3dcabf4db69464d87ec9f3f10992c2`。
- 將 Release 發佈改為真正的 `assembleRelease`，並把 App 更新與 MoQ 核心更新移到設定頁最底部的「關於」區塊；本次版本為 `v1.0.17`。
- 將 relay session 狀態監控改為 250 ms 輪詢，只有狀態或 epoch 變更時寫入診斷事件，避免直播期間忙碌迴圈消耗 CPU、電量與診斷儲存；同步將版本升至 `1.0.18`，README 安裝建議改為 signed release APK。
- 驗證：`assembleRelease`、`testDebugUnitTest`、UTF-8/XML 掃描、APK 簽章驗證；尚未在實機量測不同手機的長時間編碼與網路切換表現。
- `v1.0.19`：啟動與回到前景時，有 App 或 MoQ 核心更新就顯示提醒視窗；支援兩種更新合併提示、APK 下載完成再次提醒安裝、核心延後下載／下次啟動套用／需先更新 App 的說明。「查看更新」直接定位設定頁最底部的「關於」區塊；同一前景階段關閉後不反覆彈出。
- 修復更新 APK 與診斷分享共用同一 FileProvider 元件造成 URI 路徑衝突的問題，拆成獨立元件；安裝 Intent 加入 ClipData 讀取授權，未允許未知來源時開啟系統設定，授權返回後接續安裝。
- 安裝前在 IO 執行緒重新驗證長度、SHA-256、Android 套件解析、包名、版本名稱、遞增版本代碼、最低 Android 版本與簽章，並試開實際 content URI；損毀檔案清除後可重新下載。先前版本若仍報「剖析套件時發生錯誤」，需用瀏覽器下載新版 APK 覆蓋安裝一次。
- 驗證：舊版 Provider 元件重複的回歸測試曾失敗，分離後通過；`testDebugUnitTest` 共 161 項通過、`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。Robolectric 在 Windows 無法使用 Android 的路徑分隔方式進行真實 URI 開檔，因此另加 `UpdateFileProviderTest` 實機測試並已編譯；本次沒有連接 Android 裝置，未執行實機 URI 與廠牌安裝器流程。

### 2026-11-18

- Cloudflare 推流名稱改為選填。留空時直接傳送空的 broadcast name，符合官方 `moq-pub` 只提供 connection token 的範例；填入名稱時接受合法 namespace，`.hang` 僅用於 moq.watch／OBS 資訊與 Hang 遠端驗證。
- 驗證：`testDebugUnitTest` 共 162 項通過、`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過；尚未連接 Android 裝置執行 Cloudflare 實際推流與空 namespace 端對端驗證。
- `v1.0.21`：將 Cloudflare 發布路徑集中為 provider 的 publication path。名稱留空時保留空字串並傳入 `createBroadcast("")`，不回退到 moq.pro 路徑；同步修正空白名稱的驗證文案並加入回歸測試。
- 驗證：`testDebugUnitTest` 175 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置、Cloudflare Relay 或 OBS，未執行實際空 namespace 推流與播放驗證。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.21` GitHub Release；檔案為 `MoQPublisher-1.0.21-release.apk`，SHA-256 為 `92790d60d56eb86bc6694216d3a59fb656bda891b7cb50b5b0b9cbf9ff55b385`。
- `v1.0.22`：新增按元件隔離的媒體故障恢復協調器。相機錯誤可重綁 CameraX，影片錯誤可重建 EGL／MediaCodec／發布 worker，AudioRecord 可重啟，所有恢復使用有限次數與指數退避，停止直播時取消恢復。影片佇列新增媒體年齡健康門檻，音訊恢復使用新的 track 名稱與連續直播時間戳。
- 驗證：`testDebugUnitTest` 180 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置、Cloudflare Relay 或 OBS，未執行實際故障注入與長時間恢復驗證。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.22` GitHub Release；檔案為 `MoQPublisher-1.0.22-release.apk`，SHA-256 為 `408799022784dc8f3509b4a05a3c0273703e4fe8443991a0392f1a6691a145ec`。
- `v1.0.23`：新增 `BroadcastPreflight`，在開播前統一檢查相機／麥克風／通知權限、相機與音訊可用性、硬體編碼器、解析度／FPS、Relay／Publish Token、網路、MoQ Core、電量與熱度，並分級為 PASS／WARNING／ERROR。ERROR 會阻擋開播，WARNING 允許直播並寫入 diagnostics。
- 驗證：`testDebugUnitTest` 184 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置執行真實設備能力與熱度驗證。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.23` GitHub Release；檔案為 `MoQPublisher-1.0.23-release.apk`，SHA-256 為 `a589baeabda30612b0b5baab69959e3852bb1a13406c14b10ed3d9db35d2aa6f`。
- `v1.0.24`：完成直播中音訊控制與音量表。增益變更以每個音訊 frame 平滑 ramp，靜音同樣保留合法 Opus 時間線並更新背景通知；音量表補上 RMS、peak hold、dBFS、左右聲道峰值、削波與靜音資訊，診斷樣本同步保存並以採樣視窗重置。
- 驗證：`testDebugUnitTest` 186 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置實測增益切換、藍牙／USB 聲道與通知呈現。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.24` GitHub Release；檔案為 `MoQPublisher-1.0.24-release.apk`，SHA-256 為 `eb5aa033bf73035558f7944973d4f035aaa1172d415cc0811cd8d3e207e89302`。
- `v1.0.25`：新增 AudioManager 輸入設備選擇。設定頁可選系統預設、內建、耳機、USB 與 Bluetooth 麥克風，直播狀態顯示實際路由；AudioDeviceCallback 偵測拔除後清除偏好、回退系統路由並重建 AudioRecord／Opus track。
- 驗證：`testDebugUnitTest` 188 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置實測 USB／Bluetooth 路由、拔除回退與廠牌 AudioRecord 行為。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.25` GitHub Release；檔案為 `MoQPublisher-1.0.25-release.apk`，SHA-256 為 `a82d91e7b0de44df253ed8f9752f52483380c2f3a3c6bc652bf62566fd8f3b57`。
- `v1.0.26`：直播狀態與儀表板顯示實際選用的硬體 MediaCodec 名稱，diagnostics 每秒保存 encoder name；保留 codec 不支援動態碼率時停用 ABR 的安全降級。
- 驗證：`testDebugUnitTest` 188 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置比較不同廠牌 encoder 名稱與動態碼率行為。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.26` GitHub Release；檔案為 `MoQPublisher-1.0.26-release.apk`，SHA-256 為 `ba5b17ed3370a838bf0402be567b4c44f4147f9f07ea05dc013bd754760e2cf4`。
- `v1.0.27`：支援直播中修改 target bitrate 與 ABR 開關。ABR ceiling 會即時更新，關閉 ABR 時嘗試套用 target，codec 拒絕 runtime bitrate 變更時保留最後有效值並寫入 diagnostics；儀表板新增 encoder bitrate。
- 驗證：`testDebugUnitTest` 189 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置實測各廠牌 MediaCodec runtime bitrate 行為。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.27` GitHub Release；檔案為 `MoQPublisher-1.0.27-release.apk`，SHA-256 為 `a23469f31d54a77d24cfffb7174c1437b4b175e602b162265abc977eccaf199d`。
- `v1.0.28`：直播儀表板新增視訊佇列深度與 media age、encoder bitrate、packet loss；保留 publisher 狀態與遠端解碼驗證分離，不顯示虛構 OBS 數據。
- 驗證：`testDebugUnitTest` 189 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未連接 Android 裝置或 OBS 驗證實際接收端數值。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.28` GitHub Release；檔案為 `MoQPublisher-1.0.28-release.apk`，SHA-256 為 `5c33fb6932f9b6d5331543bc4a4114a6a87d6388321e58cde9bce32ebe775006`。
- `v1.0.29`：新增 DeviceHealthMonitor，監測電量、Power Save、Android thermal status 與可用記憶體；低電量、熱度與記憶體壓力會進入 StreamHealth、StreamAlertManager、主畫面與背景通知，未自動改變使用者選定畫質。
- 驗證：`testDebugUnitTest` 191 項通過；release APK、Android 測試編譯、lint、UTF-8/XML、簽章與 zipalign 將在本版本發布前重新確認。尚未連接 Android 裝置進行熱度、低電量與記憶體壓力實測。
- `v1.0.30`：修復 BroadcastPreflight 將可用 H.264 surface encoder 誤判為不支援的問題。Preflight 現在使用與 `SurfaceVideoEncoder`／`HardwareVideoEncoder` 相同的色彩格式、profile、latency、B-frame、priority、bitrate mode 與 `isFormatSupported` 檢查，避免畫面出現錯誤並阻擋正常開播。
- 驗證：`testDebugUnitTest` 191 項通過；`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描、APK v2 簽章與 zipalign 檢查通過。尚未在原始截圖裝置執行實機開播回歸。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.30` GitHub Release；檔案為 `MoQPublisher-1.0.30-release.apk`，SHA-256 為 `6f2adab8b26109e8606805ea209838f978a8bb9ac96c0812d2dc5e49fdf49345`。

### 2026-11-19

- `v1.0.31`：修復 `v1.0.30` 對 H.264／H.265 的硬體能力誤判。預檢不再以含 optional MediaCodec keys 的 `isFormatSupported()` 探測格式，也不以相機能力清單阻擋解析度／幀率，保留使用者選定的 codec。`SurfaceVideoEncoder` 與 `HardwareVideoEncoder` 改由實際 `MediaCodec.configure()` 作為最後判斷，讓裝置可使用原本可用的 H.264 與 H.265 組合。
- 驗證：`testDebugUnitTest`、`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease` 通過；尚未連接 Android 裝置確認原截圖裝置的 H.264／H.265 開播與任意解析度／幀率組合。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.31` GitHub Release；檔案為 `MoQPublisher-1.0.31-release.apk`，SHA-256 為 `420cfa53d0eb8e26ee72ac700e7ee3e8455c60fd72838134ffef8777baf21c83`。

### 2026-11-19

- `v1.0.32`：完成第 19 項直播畫質 Preset。新增 Data Saver、Mobile Streaming、High Quality、High FPS 內建 Preset，自訂 Preset 的命名、儲存、更新、刪除與重設，並將視訊／音訊設定分開於 Relay Profile 持久化。套用前檢查 Preset 格式與硬體 surface encoder，套用時同步 MediaCodec、CameraX、碼率、ABR、麥克風開關、聲道與音訊品質。
- 驗證：`testDebugUnitTest` 194 項通過、`assembleRelease`、`assembleDebugAndroidTest`、`lintRelease`（0 error）、UTF-8/XML 掃描；尚未連接 Android 裝置確認不同相機能力與 H.264／H.265 Preset 的實際開播。
- 已以本機 `publish-release.ps1` 建立並上傳 `v1.0.32` GitHub Release；檔案為 `MoQPublisher-1.0.32-release.apk`，SHA-256 為 `33e009cd93eecf80d8b7eeacd3c624fd33b743fe7ffe345e72b2bc034a785011`。
