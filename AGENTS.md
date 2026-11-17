# MoQ Publisher Agent 工作規範

## 每次工作結束前必做

1. 先檢查 `git status`、目前版本與遠端分支，避免覆蓋未提交的修改。
2. 搜尋本次修改涉及的文字資源，確認 UTF-8、XML 與繁體中文沒有亂碼或替代字元。
3. 執行與修改相關的單元測試；Android 行為或資源修改要至少建置 `assembleRelease`。
4. 若修改版本、APK、更新流程或發佈流程，確認 `versionCode`、`versionName`、README 與 GitHub Release 標籤一致。
5. 在本檔案的「工作紀錄」加入日期、完成事項、驗證結果與尚未能在實機驗證的項目。
6. 建立清楚的 Git commit，並將 commit push 到 `origin/main`。

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
