# 發版金鑰

給別人裝的 APK 一律從 GitHub Release 來，每一版都要用**同一把**金鑰簽：
換了金鑰，手機上的舊版就不能直接更新，只能移除重裝，作品會一起刪掉。

## 現在的狀態

- 金鑰已經產生（2026-11-06，PKCS12，alias `snapmark`，RSA 4096，`CN=SnapMark`），金鑰檔 `snapmark-release.p12` 與密碼在 repo 的擁有者手上。
  **要備份**（密碼管理器、另一顆硬碟）：弄丟了就再也發不出能直接更新的版本
- 憑證 SHA-256：`96941b2eeef9679e0a6eefae72c427dec774611715a353068c9d0a61b885027f`，已經填進 `.github/workflows/release.yml` 的 `EXPECTED_CERT`
- GitHub Secrets `ANDROID_SIGNING_KEYSTORE`、`ANDROID_SIGNING_PASSWORD` 已經設好

所以平常只要做一件事：[發版](#發版)。

## 發版

版本號是語意化版本 `MAJOR.MINOR.PATCH`（第一版 `1.0.0`）：修 bug 加 PATCH、加功能加 MINOR、大改才加 MAJOR。
兩種發法都會 build、簽名、發 `v<版本>` 的 Release，那個版本已經發過的話跳過：

- 推 tag：`git tag -a v1.0.1 -m "SnapMark 1.0.1" && git push origin v1.0.1`
- 推不了 tag 的時候（Claude 雲端 session）：repo 根目錄的 `release-version` 寫上版本（`1.0.1`，不加 `v`）推上去，
  workflow 會在那個 commit 上建 tag。這個檔案第一次用時新增

## 金鑰不見了、只能換一把的時候

換了金鑰，已經裝了舊版的手機要移除重裝（作品會一起刪掉），不到不得已不要做。

### 1. 產生 PKCS12 金鑰

在自己的電腦上（需要 JDK 的 `keytool`），放在 repo 外面：

```bash
cd ~/work/experiments
keytool -genkeypair -v -storetype PKCS12 -keystore snapmark-release.p12 \
  -alias snapmark -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=SnapMark"
```

密碼問一次就好（PKCS12 的金鑰檔與金鑰共用同一個密碼）。
`snapmark-release.p12` 與密碼要備份（密碼管理器、另一顆硬碟），弄丟了就再也發不出能直接更新的版本。
`.gitignore` 已經擋掉 `*.p12`，但還是不要放進 repo 的資料夾裡。

### 2. 設定 GitHub Secrets

有裝 `gh` 的話（Linux 的 `base64` 要加 `-w0` 才不會換行；Mac 的 `base64` 本來就不換行，不要加 `-w0`）：

```bash
base64 -w0 snapmark-release.p12 | gh secret set ANDROID_SIGNING_KEYSTORE --repo twtrubiks/snapmark
gh secret set ANDROID_SIGNING_PASSWORD --repo twtrubiks/snapmark   # 貼上密碼
```

或在網頁上設：repo 的 Settings → Secrets and variables → Actions → New repository secret，
`ANDROID_SIGNING_KEYSTORE` 貼上金鑰檔的 base64（Mac：`base64 -i snapmark-release.p12 | pbcopy` 會直接複製起來），
`ANDROID_SIGNING_PASSWORD` 貼上密碼。

### 3. 把憑證的 SHA-256 填進 release.yml

```bash
keytool -list -v -keystore snapmark-release.p12 -alias snapmark \
  | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
```

印出來的 64 碼填進 `.github/workflows/release.yml` 的 `EXPECTED_CERT`（或貼給 Claude 填；這是憑證的指紋，公開也沒關係，金鑰檔與密碼才不能給）。
workflow 簽完會核對這個值，Secret 被換成別把金鑰的話就不發版。
