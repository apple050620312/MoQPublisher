# 直播端對端驗收流程

這份流程可由有 Android 手機、Cloudflare 或 moq.pro Relay、OBS 與測試憑證的人重複執行。Repository 目前只能完成自動化規則與 APK 建置，沒有真實手機、OBS 或 Relay 帳號，因此本文件不代表端對端已通過。

## 準備

1. 安裝同一簽章的 release APK，記錄手機型號、Android 版本、App 版本、MoQ core 版本、CameraX 可用解析度與網路類型。
2. 建立相同 Relay 的 publish＋subscribe Token。不要把 Token 放入 Git、聊天、截圖或診斷備註。
3. 在 OBS 建立 Browser Source 或可用的原生 MoQ Source，記錄 source 版本、解碼器、畫面尺寸與音訊輸出。
4. 打開 App diagnostics，記錄開始直播、首次收到 OBS 畫面、首次收到音訊、斷線與恢復時間。

## Cloudflare

1. 選 Cloudflare MoQ，填入 publish token。需要 `moq.watch` 或 Hang 驗證時，另填 `.hang` broadcast name。官方只使用 connection token 的 publisher 也可把名稱留空。
2. 開始直播後確認 App 顯示 Connected、Published 與實際 Published FPS。
3. 停止後查看 diagnostics 的 `broadcast_advertised`、`metrics`、`relay_status`、`video_used`、`published_fps` 與音訊樣本。
4. 使用同 Relay 的 subscribe token 執行 App 內遠端驗證，再用 OBS 或瀏覽器獨立確認。遠端驗證通過不等於 OBS 已顯示。

## moq.pro

1. 選 moq.pro，填入 HTTPS／WSS relay URL、broadcast path 與 JWT。確認設定頁遮罩 JWT。
2. 使用 OBS 的對應 MoQ source，記錄收到畫面與音訊的時間。
3. 重新啟動網路、切換 Wi-Fi／行動網路、暫停網路後恢復。記錄重連次數、恢復畫面時間、關鍵影格與音畫同步。

## 長時間回歸

執行 720p30 至少 2 小時，設備支援時再執行 1080p30 與 1080p60。另測試切換前後鏡頭、背景直播、關閉螢幕、停止後立即重開、斷網恢復、音訊拔除與裝置過熱。每個案例保存 diagnostics ZIP、App／OBS／Relay 版本、開始與恢復時間、CPU／memory／thermal、queue depth、FPS、bitrate、RTT、丟幀與 MediaCodec 資源狀態。

## 未驗證項目

本機沒有 Android 裝置、OBS、有效的 Cloudflare／moq.pro 憑證，所以目前只完成 JVM、APK、lint、簽章與可操作文件。端到端延遲必須從 OBS 或接收端實際量測，不能以 RTT 代替。
