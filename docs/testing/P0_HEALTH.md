# P0 健康監控驗證

## 自動化

- `BroadcastLifecycleTest` 驗證 announce、subscriber demand、reconnect、recovery、failed、cleanup 與非法轉換。
- `StreamHealthTest` 驗證缺少 subscriber、啟動緩衝期、相機／GPU／編碼／發布停滯、音訊擷取與發布、持續低 FPS、queue pressure 與恢復遲滯。
- `StreamAlertManagerTest` 驗證冷卻、解除、歷史上限與重複警報去重。
- `StreamMetricsTest` 驗證 camera、encoded、published 與 audio activity age。

## 手動驗收

1. 開始直播但不啟用觀看端，確認畫面沒有把沒有 subscriber 當成故障。
2. 開啟觀看端後確認 `WAITING_FOR_SUBSCRIBER` 轉為 `PUBLISHING`，並在 diagnostics 看到 `broadcast_phase`。
3. 暫停網路，確認 UI 顯示重新連線且遠端驗證失效。恢復後檢查 `relay_video_resumed` 與新關鍵影格，另以 OBS 確認解碼恢復。
4. 讓相機、encoder、音訊或發布管線停頓，確認分別出現對應健康訊息，並確認冷卻期間不重複轟炸。
5. 保存 diagnostics ZIP，檢查 `metrics` 的相機、GPU、編碼與影音活動時間。音訊擷取使用 `last_audio_capture_age_ms`，音訊發布使用 `last_audio_age_ms`。

目前沒有 Android 手機、真實 Relay 或 OBS，因此上述手動驗收尚未執行。自動化測試不能取代長時間直播與真實接收端驗證。
