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
