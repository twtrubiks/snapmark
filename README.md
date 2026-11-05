# SnapMark

在圖片上做標註的 Android App：拍一張照、選一張照片，或開一張空白畫布，
畫上箭頭、文字、方框、印章、馬賽克，再存到相簿或分享出去。

- 三個入口：相機、照片、空白
- 標註過的圖留在「作品」分頁，可以重新打開繼續改
- 不連網、不需要帳號：App 沒有 `INTERNET` 權限
- Android 9 以上，Kotlin + Jetpack Compose

**目前只有骨架**：專案架構、資料模型與 CI 都放好了，標註的功能還沒開始做。進度看 [`CLAUDE.md`](CLAUDE.md) 的「目前的進度」。

## 下載

[最新版 APK](https://github.com/twtrubiks/snapmark/releases/latest/download/snapmark.apk)（還沒發第一版前這個連結是空的）

## 開發

在 [claude.ai/code](https://claude.ai/code) 雲端 session 開發，畫面靠 GitHub Actions 的模擬器錄影確認。

- 規格、架構、約定：[`CLAUDE.md`](CLAUDE.md)
- 雲端環境設定：[`docs/cloud-setup.md`](docs/cloud-setup.md)
- 發版金鑰：[`docs/release-key.md`](docs/release-key.md)
