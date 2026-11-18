# RTMP／SRT 備援

在 OBS 所在的電腦執行閘道。Android 照常將一組影音送往 MoQ Relay，電腦透過訂閱接收。RTMP 使用上游 MoQ CLI 的 Enhanced RTMP 輸出。SRT 由 MoQ CLI 匯出 MPEG-TS，再交給啟用 libsrt 的 FFmpeg 傳送。兩路各自執行，保留原影音編碼。

## 操作

1. 安裝 MoQ CLI 0.14.17 與支援 libsrt 的 FFmpeg，加入 PATH 或透過 `--moq`、`--ffmpeg` 指定執行檔。
2. 開播後執行下列指令。替換直播名稱，並在隱藏輸入提示貼上訂閱端點。Cloudflare 使用 Subscribe-only Token。moq.pro 可從 App 的觀看設定複製訂閱端點。

```powershell
python tools/moq-backup-gateway.py --broadcast live.hang --version moq-transport-18
```

Cloudflare 發布時留空的名稱同樣以空名稱訂閱。

```powershell
python tools/moq-backup-gateway.py --broadcast= --version moq-transport-18
```

空名稱的 RTMP 會將已訂閱的影音重新封裝到同一電腦上的 MoQ CLI，供需要具名來源的 RTMP 監聽器使用。

3. 在同一台電腦的接收端開啟閘道印出的本機網址。RTMP 客戶端須支援 Enhanced RTMP 與實際的視訊／Opus 音訊。以下命令可檢查影音解碼。

```powershell
ffmpeg -rtmp_enhanced_codecs hvc1,Opus -i 'rtmp://127.0.0.1:1935/live/backup' -map 0:v:0 -map 0:a:0 -t 2 -f null -
ffmpeg -i 'srt://127.0.0.1:9000?mode=caller' -map 0:v:0 -map 0:a:0 -t 2 -f null -
```

4. 可用 `--protocol rtmp` 或 `--protocol srt` 只啟用一路。發生斷線時各自重新連線。用 Ctrl+C 結束同一閘道的本機程序。App 儀表板會顯示 Android 實際送往 Relay 的總上傳速率。

## 驗證紀錄

Windows 上使用 MoQ CLI 0.14.17、MoQ Relay 0.17.17 與 FFmpeg 9.0.2。以本機 Draft 18 Relay、FFmpeg 測試影像與 Opus 音訊完成以下解碼，每組均驗證 320×180、30 FPS 的 60 個畫格和音軌。

下列命令可重跑四組本機用例。將三個執行檔加入 PATH，或透過 `--relay`、`--moq`、`--ffmpeg` 指定路徑。結果寫入 `build/gateway-regression`。

```powershell
python docs/testing/verify_backup_gateway.py
```

| 電腦輸出 | H.264＋Opus | H.265＋Opus |
|---|---|---|
| Enhanced RTMP | 通過 | 通過 |
| SRT | 通過 | 通過 |

上表包含具名 `test.hang` 與空名稱兩種 Draft 18 案例。

| 實際 OBS 接收方式 | H.264＋Opus | H.265＋Opus |
|---|---|---|
| OBS 32.1.2 Media Source → SRT | 取得連續影格及音訊電平 | 取得連續影格及音訊電平 |
| OBS 32.1.2 Media Source → Enhanced RTMP | 內建 FFmpeg 7.1.1 拒絕 Opus FourCC | 內建 FFmpeg 7.1.1 拒絕 Opus FourCC |

在 OBS 32.1.2 使用 Media Source 接收時，取消勾選本機檔案並填入閘道顯示的 SRT 網址。Android 發布、外部 Relay 和長時間斷線重連尚待驗收。

[MoQ CLI 與 Relay 發佈](https://github.com/moq-dev/moq/releases) · [FFmpeg Windows 發佈](https://github.com/GyanD/codexffmpeg/releases)
