# P0 健康監控驗證

## 自動化

- `BroadcastLifecycleTest` 驗證 announce、subscriber demand、reconnect、recovery、failed、cleanup 與非法轉換。
- `StreamHealthTest` 驗證沒有 subscriber 不會誤報、相機／編碼／發布停滯門檻、音訊間隔、低 FPS、queue pressure 與恢復遲滯。
- `StreamAlertManagerTest` 驗證冷卻、解除、歷史上限與重複警報去重。
- `StreamMetricsTest` 驗證 camera、encoded、published 與 audio activity age。

## 手動驗收

1. 開始直播但不啟用觀看端，確認畫面沒有把沒有 subscriber 當成故障。
2. 開啟觀看端後確認 `WAITING_FOR_SUBSCRIBER` 轉為 `PUBLISHING`，並在 diagnostics 看到 `broadcast_phase`。
3. 暫停網路，確認 UI 顯示重新連線／恢復，`stream_alerts` 有 network alert，恢復後警報解除。
4. 讓相機、encoder、音訊或發布管線停頓，確認分別出現對應健康訊息，並確認冷卻期間不重複轟炸。
5. 保存 diagnostics ZIP，檢查 `metrics` 包含 `last_camera_age_ms`、`last_encoded_age_ms`、`last_video_age_ms`、`last_audio_age_ms`、health level 與 alert history size。

目前沒有 Android 手機、真實 Relay 或 OBS，因此上述手動驗收尚未執行。自動化測試不能取代長時間直播與真實接收端驗證。
