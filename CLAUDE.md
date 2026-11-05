# SnapMark

在圖片上做標註的 Android App：拍一張照、選一張照片，或開一張空白畫布 → 畫箭頭、文字、方框、印章、馬賽克 → 存到相簿或分享出去。
三個入口：**相機、照片、空白**，另外有一個存在手機裡的「作品」分頁，標註過的圖可以重新打開繼續改。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

## 目前的進度

**只有骨架，而且還沒有 build 過**：2026-11-05 在使用者的電腦上建立，使用者要求只建架構、不執行任何程式，
所以 Gradle、JVM 測試、CI 都還沒跑過，程式碼還沒有經過編譯器檢查。

骨架裡有的：

- Gradle 設定、wrapper、版本（2026-10 的穩定版組合）
- 資料模型 `Markup`（標註的 JSON）、`WorkStore`（作品的資料表與檔案位置），各有 JVM 測試（`MarkupTest`、`WorkStoreTest`）
- 畫面：`HomeScreen`（四個分頁切得動；「空白」會開一張作品進畫布、「作品」用文字列出來；相機、照片的按鈕停用）、
  `CanvasScreen`（只照比例畫一塊白色的畫布，沒有任何工具）
- 模擬器：`DeviceTest`（`WorkStore` 在 Android 9 的 SQLite 上）、`DemoRecordingTest`（切分頁 → 空白 → 畫布 → 作品）、
  release 冒煙測試（打得開、看得到分頁列）
- 還沒有 GitHub repo（本機只有 `git init`）。文件裡的連結先假設是 `twtrubiks/snapmark`

### 第一次 session

1. 先跑「在 VM 內驗證」那一行，把骨架修到 build 得過、JVM 測試通過（版本相容問題以錯誤訊息為準，改完更新「專案約定」的版本）
2. push 之後等 CI 錄影跑完，照「錄影流程」看 frames，確認骨架在模擬器上跑得起來
3. 把這一節改成實際的進度，再照「建議的實作順序」開始做

## 規格

### 首頁

- 一頁一個入口，**左右滑換頁，分頁列在畫面最下面**。四個分頁：**相機、照片、空白、作品**
- 相機、空白這兩頁的樣子：上面一句大標、一句說明，中間一張示意圖，下面一顆大的圓形按鈕
- 首頁右上角：深色模式（跟系統／淺色／深色，`ThemeMode`，存在 SharedPreferences `settings`）。其他設定先不做

### 相機

- 按鈕 → 用**系統的相機 App** 拍（`ActivityResultContracts.TakePicture`），拍完直接進畫布。不自己做相機畫面
- 照片先寫到 App 的 `cacheDir/camera/`，用 `FileProvider` 把 URI 交給相機 App（`AndroidManifest.xml` 要加 `<provider>` 與 `res/xml/file_paths.xml`）
- **不要宣告 `CAMERA` 權限**：用系統相機不需要；一旦宣告了，沒有授權時反而開不了系統相機
- 使用者取消（沒拍）就留在首頁，暫存檔刪掉
- 手機上沒有相機 App（`ActivityNotFoundException`）：提示「找不到相機 App」

### 照片

- 第一版用**系統的照片挑選器**（`ActivityResultContracts.PickVisualMedia`，只選圖片）：不需要任何權限。
  Android 9、10 沒有挑選器時會自動退回系統的檔案畫面
- 另一種做法是直接在頁面裡顯示相簿的縮圖格線，右上角一顆按鈕開系統相簿。要做成那樣得要「讀取照片」的權限
  （Android 13 以上 `READ_MEDIA_IMAGES`、12 以下 `READ_EXTERNAL_STORAGE`，Android 14 還有「只允許部分照片」），見「待討論」

### 底圖（相機拍的、照片選的）

- 複製進 App 自己的空間 `filesDir/works/<id>/base.jpg`（`WorkStore.baseFile`）：依 EXIF 轉正、長邊縮到 4096px 以內、JPEG 品質 92，HEIC 一樣轉成 JPEG。
  不留原圖的 URI（挑選器給的讀取權限是暫時的），也不動原圖
- EXIF 不帶進去（尤其是 GPS）
- 另外存一張 `thumb.jpg`（長邊 512px 左右，**畫上標註之後**的樣子）給作品分頁用，每次存標註時更新

### 空白

- 按鈕 → 開一張白色的畫布進畫布頁。大小固定 1440×1920（直向 3:4，`SnapMarkApp` 的 `BLANK_WIDTH`／`BLANK_HEIGHT`），
  同一張作品在每支手機上都一樣大（不照螢幕大小）

### 畫布

圖放在中間，四個角落各有浮在圖上面的控制項：

```
┌──────────────────────────────┐
│ ← 返回            分享   ⋮   │  上面一排：⋮ 裡面是裁切、清除標註、向右轉、向左轉
├──────────────────────────────┤
│ [復原][重做]          [刪除] │  有東西可以復原／重做、有選到物件時才出現
│                              │
│            （圖）            │  圖外面是深色的細條紋底
│                              │
│ (●顏色)              (工具↗) │  兩顆圓鈕，點了往上展開成一排
└──────────────────────────────┘
```

- **右下角：工具**。圓鈕顯示目前的工具，點一下往上展開，由下到上：
  箭頭、文字、形狀、筆、印章、馬賽克、平移。選了就收起來。有子選項的再往左展開一排：
  - 形狀：方框、圓角方框、直線、橢圓
  - 筆：麥克筆、螢光筆（半透明、比較粗）
  - 印章：打勾、問號、驚嘆號、愛心、叉叉
- **左下角：顏色與粗細**。圓鈕顯示目前的顏色，大小跟著目前的粗細。點一下往上展開八個顏色：
  紅 `#D63410`、橘 `#E28520`、黃 `#FCF121`、綠 `#6AD919`、藍 `#3C78FC`、粉紅 `#FF4678`、黑 `#444444`、白 `#FFFFFF`
  （`MarkupColor`，**預設粉紅**）；最下面那顆再往右展開五種粗細：特小、小、中、大、特大（`StrokeSize`，預設中）
- **預設工具是箭頭**。手指拖一下就是一個物件：
  - 箭頭：從起點拉到終點，箭頭在終點。箭頭是尾巴細、頭粗的實心形狀，不是一條線加三角形
  - 形狀：拉出外框（只有框線，不填色）；直線就是一條線
  - 筆、螢光筆：照手指的軌跡畫
  - 文字：點一下放一個文字框、跳出鍵盤，打完按上面的「完成」。有兩種字：卡通（有白邊，預設）與純色，
    選到文字時在它上面的小泡泡切換
  - 印章：點一下蓋一個。選到印章時小泡泡有兩顆：加尾巴（一條指向某個位置的線）、加文字
  - 馬賽克：拉一個框，把底圖那一塊打碎。跟顏色、粗細無關
  - 平移：一指拖動、兩指縮放整張圖。**其他工具時兩指一樣可以縮放平移**（手機上沒有這個很難用）
- **選取**：任何工具下，點一下已經畫上去的物件就選到它（手指沒有拖動才算點）：出現外框與控制點，可以拖著移動、拉控制點改大小／方向；
  右上角出現「刪除」；這時候改顏色、粗細是改它。點空白的地方取消選取。文字點兩下重新編輯
- **復原／重做**（左上角）：新增、移動、改大小、改顏色、刪除、清除、裁切、旋轉都可以復原。
  只記這一次打開的期間，離開畫布就清掉（不存進資料庫）
- **上面一排**：返回、分享（見「存檔與分享」）、選單：
  - 裁切：拉一個框，上面變成「完成」與取消。裁切、旋轉的是整張作品（底圖＋標註一起）
  - 清除標註：全部清掉（可以復原），裁切與旋轉不動
  - 向右轉、向左轉：90 度
- **自動存檔**：每改一次就存（`WorkStore.saveMarkup`，在 `Dispatchers.IO`；連續拖動時不要每一格都存），離開畫布不用問。
  不跳「放棄這些標註嗎？」這種對話框：有作品分頁，隨時可以回來改。
  例外：**剛開的作品什麼都沒畫就離開，直接刪掉**（空白畫布一定刪；相機、照片的也刪——使用者只是點進來看一下）
- 轉螢幕不能丟東西：畫到一半的、選到的工具與顏色、打到一半的文字、縮放的位置

### 存檔與分享

- 成品是把標註畫在底圖上的一張圖，**大小跟底圖一樣**（不是螢幕截圖）：有底圖的存 JPEG（品質 92）、空白畫布存 PNG
- 畫成品圖的程式跟畫面上畫標註的要是同一份（`android.graphics.Canvas` 或 Compose 的 `DrawScope` 選一個，另一邊轉接），
  不然畫面上看到的跟存出來的會不一樣
- 畫布上面的「分享」按下去是一個選單：
  - **存到相簿**：`MediaStore`，放在 `Pictures/SnapMark/`，檔名 `SnapMark-<YYYYMMDD-HHmmss>.<jpg|png>`，存完提示「已存到相簿」。
    Android 10 以上不需要權限；Android 9 要 `WRITE_EXTERNAL_STORAGE`（manifest 寫 `android:maxSdkVersion="28"`，按的時候才問）
  - **分享**：成品圖寫到 `cacheDir/share/`，用 `FileProvider` ＋ `ACTION_SEND` 交給系統的分享選單
- 存到相簿、分享都不會動作品：作品留著，之後還可以改了再存一次

### 作品

- 「作品」分頁：縮圖的格線（`thumb.jpg`），最近改過的在前面。點一張打開繼續改；長按 → 刪除（要確認），資料夾整個刪掉
- 空的時候說明：標註過的圖都會在這裡
- 作品只存在這支手機的 App 私有空間：移除 App 就沒了（`allowBackup` 關著）。要留著的圖請「存到相簿」

### 別的 App 分享圖片進來（放在後面做）

- `ACTION_SEND`、`image/*`（相簿、瀏覽器、截圖的「分享」）：當成選了一張照片，直接開一張作品進畫布。
  `MainActivity` 已經是 `singleTask`，App 開著時用 `onNewIntent` 接
- `ACTION_VIEW`、`ACTION_EDIT`（被別的 App 叫來編輯、把結果傳回去）先不做

### 不做

帳號、登入、雲端同步、地圖、網頁截圖、PDF、付費與試用期、使用分析（analytics）、當機回報、評分提示、教學動畫。
**App 不連網，不要加 `INTERNET` 權限**：裝置 ID、使用行為、圖片什麼都不往外送，這是這個 App 的賣點。

### 先不做（要做之前先問使用者）

作品的匯出／匯入與備份、資料夾或標籤、搜尋、多選刪除、顏色自訂（八色以外）、字型選擇、圖層順序調整、
平板的橫向版面、形狀辨識（手畫的圓自動變成橢圓）、自己做相機畫面（CameraX）。

### 待討論（骨架先照「目前的做法」，動工到那裡時跟使用者確認）

| 題目 | 目前的做法 | 另一個選擇 |
| --- | --- | --- |
| 照片分頁 | 系統的照片挑選器，不要權限 | 在頁面裡直接顯示相簿格線：要「讀取照片」權限，Android 13、14 各有不同的授權畫面要處理 |
| 成品的格式 | 有底圖 JPEG 92、空白 PNG | 一律 PNG（不失真，但 1200 萬畫素的照片一張會有 20 MB 以上） |
| 底圖的大小 | 長邊縮到 4096px | 保留原始大小（成品最清楚，但大圖的記憶體與馬賽克的速度要另外處理） |
| 作品要不要備份 | `allowBackup="false"`，移除 App 就沒了 | 開系統備份（上限 25 MB，放不了幾張），或做匯出／匯入 |
| 裁切、旋轉怎麼存 | 還沒定（`Markup` 目前只有 `nodes`） | A：直接改底圖與全部標註的座標（簡單，但裁掉的拿不回來）；B：存成一個檢視範圍（可以反悔，畫的時候多一層轉換） |
| 深淺色 | 跟系統，Material 3（Android 12 以上桌布取色） | 固定淺色＋粉紅（`#FF4678`）當主色 |

### 建議的實作順序

每一步都是：先寫 JVM 測試 → VM 內 build 通過 → 更新 `DemoRecordingTest` → push 看錄影。

1. **畫布能畫**：顯示作品、兩指縮放平移、箭頭工具、顏色與粗細、自動存檔、復原／重做。從空白畫布就能整個測
2. **其他工具**：形狀、筆與螢光筆、文字、印章
3. **選取**：移動、改大小、改顏色、刪除
4. **照片與相機**：挑選器、底圖轉正縮小、系統相機；馬賽克（要有底圖才看得出來）
5. **輸出**：畫成品圖、存到相簿、分享；作品分頁的縮圖格線與刪除
6. **裁切、旋轉、清除標註**；別的 App 分享圖片進來

## 程式的分層

- **規則都在純 Kotlin、有 JVM 測試**，不 import `android.*`、`androidx.compose.*`：
  - `Markup`（已有）：標註的資料模型，`kotlinx.serialization` 存成 JSON。**座標一律是底圖的像素**，跟螢幕大小、縮放無關
  - `WorkStore`（已有）：作品的資料表與檔案位置
  - 之後要加的（名字只是建議）：幾何（點到物件的距離、選取的命中判斷、控制點、箭頭的外形、螢幕座標與底圖座標的轉換）、
    編輯的狀態機（目前的工具、選到誰、手指按下／移動／放開 → 產生或修改哪個 `Node`）、復原／重做的堆疊。
    這些是這個 App 最容易出錯的地方，全部要能在 JVM 測，不要寫在 Composable 裡
- `WorkStore` 透過 `Sql` 介面存取資料庫：手機上是 `AndroidSql`（`databases/snapmark.db`），
  JVM 測試是 `JdbcSql`（sqlite-jdbc 的記憶體資料庫，SQL 跑在真的 SQLite 上）。
  **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`VACUUM INTO`、`RETURNING` 這些新語法，
  JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑
- 資料表 `works`：主鍵是 UUID（也是 `filesDir/works/` 底下資料夾的名字）、`has_base`、`width`、`height`、`markup`（JSON）、
  `created_at`、`updated_at`（epoch 毫秒）。改 schema 時用 `ALTER TABLE ... ADD COLUMN` 或在 `WorkStore` 的 init 裡判斷，手機上已經有的資料不能丟
- **`Markup` 的 JSON 發版之後就是要長期相容的格式**：加欄位一律給預設值（舊的 JSON 讀得回來），不改名字、不改意思；
  `Node` 的 `@SerialName` 不能改。第一次發版之前可以隨便改
- 要碰 Android 的放在薄薄一層：`AndroidSql`、底圖的轉正縮小與畫成品圖（`Bitmap`，`DeviceTest` 測）、畫面 `*Screen.kt`、
  整個 App 的導覽在 `SnapMarkApp`（sealed interface 的 Route ＋ `rememberSaveable` 的 stack，不另外引入 Navigation 套件），
  共用的東西在 `AppContainer`（`SnapMarkApplication.container`）
- 資料庫與檔案一律在 `Dispatchers.IO`，不要在組畫面時讀資料庫、解圖片
- 顯示圖片用 Coil（只有 `coil-compose`，讀本機檔案與 `content://`，沒有接網路層）

## 專案約定

- Gradle 專案根目錄就是 repo 根目錄，`./gradlew` 在這裡跑
- applicationId / namespace：`com.twtrubiks.snapmark`（`scripts/ci/record.sh` 依賴這個值，改了要一起改）。
  發過版之後不要改：applicationId 一變，手機會當成另一個 App，原本的作品就接不回來
- 單一 module `app`，主畫面 `MainActivity`
- **minSdk 28（Android 9）**，compileSdk 37，targetSdk 36，Java / Kotlin target 17
  （`java.time` 在 API 26 就內建，不需要 core library desugaring；新增 API 29+ 的 API 前先跑 `./gradlew lintDebug` 看 NewApi）
- 依賴版本集中在 `gradle/libs.versions.toml`
- AGP 9.x 使用 built-in Kotlin：**不要**套 `org.jetbrains.kotlin.android`；Compose 套 `org.jetbrains.kotlin.plugin.compose`，
  JSON 套 `org.jetbrains.kotlin.plugin.serialization`，版本都跟 Kotlin 一樣
- 版本起點（2026-10 查的穩定版；**這個 repo 還沒 build 過**）：
  AGP 9.4.1、Kotlin 2.4.21、Gradle wrapper 9.8.2、Compose BOM 2026.09.00、activity-compose 1.13.0、core-ktx 1.19.1、
  lifecycle-runtime-ktx 2.11.0、Coil 3.6.3、kotlinx-serialization-json 1.12.0、exifinterface 1.4.2、
  sqlite-jdbc 3.53.4.0（只給 JVM 測試）、androidx.test runner 1.7.0、core 1.7.0、ext:junit 1.3.0、uiautomator 2.4.0。
  若 build 報版本相容問題，以錯誤訊息為準調整，並更新這段
- 圖示不引入 `material-icons` 套件：要用的圖示一個一個放成 `res/drawable/ic_*.xml`（vector）
- Coil、kotlinx.serialization 都自帶 R8 規則，`app/proguard-rules.pro` 目前是空的
- `android:allowBackup="false"`：作品是使用者自己的照片，檔案大也比較私人（見「待討論」）
- 畫面用 Material 3，Android 12 以上用桌布取色（`Theme.kt`）。深淺預設跟系統（`ThemeMode`，切換的按鈕還沒做）。
  啟動那一瞬間的視窗底色（`values-night`）跟系統
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `SNAPMARK_KEYSTORE`、`SNAPMARK_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `snapmark`）；沒有就用這台機器的 debug 金鑰（錄影的 CI、自己 build）
- **給別人（包括使用者）裝的 APK 一律從 GitHub Release 來**，不要把 VM 裡 build 的 APK 傳給使用者：
  VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 不能直接更新，只能移除重裝——**移除就會刪掉手機上的作品**
  - 發版：`.github/workflows/release.yml`。把 `release-version` 改成新版本（例如 `2026.11.05`，同一天第二版寫 `2026.11.05.2`）推上去，
    就會 build、簽名、建 `v<版本>` 的 tag 並發 Release；那個版本已經發過的話跳過。
    雲端 session 的 git 只能推自己的 branch，推 tag 會斷線，所以用版本檔
  - **還沒有發版金鑰**：第一次發版前，使用者要在自己的電腦照 `docs/release-key.md` 產生金鑰、設 Secrets、
    把憑證 SHA-256 填進 `release.yml` 的 `EXPECTED_CERT`，再新增 `release-version`。不要在 VM 裡產生金鑰
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來），不管從哪個 branch 發都只會變大
  - README 的下載連結是 `releases/latest/download/snapmark.apk`
- commit 訊息用繁體中文的 Conventional Commits（`feat:`、`fix:`、`test:`、`docs:`、`ci:`），不加 Co-Authored-By

## 雲端 VM 環境

- Android SDK 由環境的 setup script（`scripts/cloud-setup.sh`）裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供。
  已經有其他 Android 專案用的雲端環境（裝同樣的 SDK）可以直接沿用
- SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central 回 `429 Too Many Requests`（共用出口 IP 被限流），
  隔 20 秒左右重跑 `./gradlew` 即可，依賴進快取後就不會再遇到；不要為此改 repository 設定（CI 不受影響）。
  錯誤訊息通常不會直接寫 429，而是 `Plugin [id: '...'] was not found in any of the following sources`、
  `Could not resolve <group>:<artifact>:<version>` 或 `Configuration cache state could not be cached`，
  加 `--info` 才看得到底下的 `HTTP 429`。新 session 第一次 build 看到這幾種，先重跑，不要去改版本
- VM 的網路只放行套件庫。App 本身不連網，不受影響

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest
```

`assembleRelease` 會跑 R8，可以先抓到缺 class 之類的錯誤；執行時才會出現的問題要看 CI 的 release 冒煙測試。
和 Android 無關的邏輯（幾何、編輯的狀態機、復原、JSON）寫成純 Kotlin，放在 JVM 單元測試裡測，幾秒就有結果。
CI 錄影前也會跑 `testDebugUnitTest`，沒過就不會錄影（失敗原因在 `gradle-build.txt`）。

## 錄影測試：DemoRecordingTest

`app/src/androidTest/java/com/twtrubiks/snapmark/DemoRecordingTest.kt`，CI 只錄這個 class，
`record.sh` 在它執行期間用 `screenrecord` 錄整個螢幕。找元件、輪詢、截圖這些放在 `UiTest`。

- 用 UiAutomator 操作（真實時間驅動，動畫會正常播放），不要用 Compose test rule
- 不要用 `ActivityScenario` 開 App，用 `UiTest.launchApp()`（`context.startActivity`）；找元件用 `waitFor` 每 0.1 秒輪詢，
  不要用 `device.wait(Until...)` 或 `device.waitForIdle()`。元件找到之後畫面又重組了，按之前要重新找一次，否則會拿到 `StaleObjectException`
- 一個 `@Test` 跑完整的展示流程，每個操作之間停 1～1.5 秒讓影片看得清楚；全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- 用 `By.text(...)` 找元件；要用 testTag 時用 `By.res(...)`（`SnapMarkApp` 的 root 已經開了 `testTagsAsResourceId`）。
  目前的 testTag：分頁 `tab_camera`／`tab_photos`／`tab_blank`／`tab_works`、按鈕 `camera_button`／`photos_button`／`blank_button`、
  `work_list`、`canvas`、`canvas_back`
- **在畫布上畫東西用座標**：`device.swipe(x1, y1, x2, y2, steps)` 拉箭頭、`device.click(x, y)` 蓋印章；
  座標從 `canvas` 那個元件的 `visibleBounds` 算，不要寫死。兩指縮放用 `UiObject2.pinchOpen`／`pinchClose`
- 畫出來對不對 UiAutomator 讀不到：用 `screenshot("名稱")` 存截圖給人（Claude）看；要自動判斷的話，
  讀 `container.works` 裡存下來的 `Markup`（例如拉一下之後多了一個 `Node.Arrow`）
- BottomSheet、Dialog、DropdownMenu 是另一個視窗，App 根部開的 `testTagsAsResourceId` 管不到：要在裡面再開一次，或用 `By.text` 找
- 開始前清空作品（`clearWorks()`），要用到的資料都在測試裡建
- 鍵盤開著才按返回鍵收起——沒開的時候按返回鍵會離開那一頁（做到文字工具時在 `UiTest` 加 `hideKeyboard()`：`dumpsys input_method` 有 `mInputShown=true` 才按返回鍵）

## 模擬器上的測試：DeviceTest

`app/src/androidTest/java/com/twtrubiks/snapmark/DeviceTest.kt`，`record.sh` 在錄影前先跑、不錄影，輸出在 `device-test.txt`。
只放模擬器才測得到的事：`WorkStore` 在 Android 9 的 SQLite 上跑一輪（已有）。之後要加：

- 底圖用真的 Bitmap 與 ExifInterface 縮圖轉正（直拍的 JPEG、手機拍的 HEIC；只看長寬的話轉錯方向也會過，
  要用上下兩色的圖核對轉正後顏色的位置）
- 畫成品圖：在一張已知顏色的底圖上畫一個箭頭、一塊馬賽克，核對幾個點的像素顏色與輸出的大小

## 系統畫面的測試：SystemUiTest（還沒有）

做到相機、照片、存到相簿、分享時加：繼承 `UiTest`，`record.sh` 在錄影結束、截圖 pull 回來之後先 `pm clear` 再跑，不錄影。
只放不是 App 畫的畫面，每個 Android 版本長得不一樣，只能點一次才知道接不接得上：

- 系統的照片挑選器：Android 9、10 是檔案畫面（找得到檔名），Android 11 以後是照片挑選器（只有縮圖，挑第一張）。
  照片先放進相簿：Android 10 以後 App 直接寫 MediaStore，Android 9 要權限，交給 shell 複製再廣播掃描
- 系統相機：`record.yml` 的模擬器開了 `-camera-back emulated`（假的鏡頭畫面）。AOSP 映像檔的相機 App 第一次打開會有幾個提示要點掉，
  快門、確認的按鈕每個版本不一樣；真的接不起來就只測「拍完之後」那一段（測試自己放一張圖到那個暫存檔）
- 存到相簿：存完從 MediaStore 查得到 `Pictures/SnapMark/` 底下那張圖，大小跟底圖一樣

## release 冒煙測試

`record.sh` 在錄影之後覆蓋安裝 release 版（同一把 debug 金鑰，可以直接覆蓋），打開 App 截圖（`screenshots/release-1-launch.png`），
用 `uiautomator dump` 確認看得到首頁的分頁列（`tab_blank`）。`logcat-release.txt` 有 `FATAL EXCEPTION` 或看不到分頁列時 job 會失敗。
R8 壓過的程式只有 release 版跑得到：之後每做一個會用到函式庫的功能（Coil 讀照片、kotlinx.serialization 存 JSON、畫成品圖），
就在這裡真的走一次（用 `record.sh` 的 `ui_dump` 找元件、從 `bounds` 算出中心座標、`adb shell input tap` 點）。

## 錄影流程（GitHub Actions）

1. push 到目前的 branch；改到 `app/**`、`gradle/**`、`*.gradle.kts`、`gradle.properties`、`scripts/ci/**`、`record.yml` 會自動觸發。
   只改文件（`CLAUDE.md`、`README.md`、`docs/**`）不會觸發。要手動觸發：
   `gh api -X POST 'repos/{owner}/{repo}/actions/workflows/record.yml/dispatches' -f ref=<branch>`
   錄影一律用 API 28（最低支援的 Android 9）的模擬器；使用者明確要求其他版本時才加 `-f "inputs[api-level]=<level>"`（例如 Android 14 是 34），
   不同 API level 都寫到同一個 `media` branch，用 `info.txt` 的 `api level` 分辨
2. 查進度：`gh api "repos/{owner}/{repo}/actions/runs?branch=<branch>&per_page=1" --jq '.workflow_runs[0] | {id, status, conclusion, head_sha}'`
   （雲端 session 的 GitHub proxy 擋 GraphQL，`gh pr` / `gh issue` 不能用，一律用 `gh api` 打 REST）
3. 看哪一步失敗：`gh api 'repos/{owner}/{repo}/actions/runs/<id>/jobs' --jq '.jobs[] | {name, conclusion, steps: [.steps[] | select(.conclusion=="failure") | .name]}'`
4. 不論成功或失敗，run 結束後都讀 `media` branch（每次會被覆蓋，先核對 `info.txt` 的 commit 是不是自己這次的）：

   ```bash
   git fetch origin media
   rm -rf /tmp/media && mkdir -p /tmp/media
   git archive origin/media | tar -x -C /tmp/media
   ```

   - `/tmp/media/frames/*.png`：每 2 秒一張影格，用 Read 看圖確認畫面
   - `/tmp/media/screenshots/*.png`：測試在關鍵畫面存的截圖（半解析度），`release-*.png` 是 release 冒煙測試的截圖
   - `/tmp/media/info.txt`：branch、commit、api level、run 網址、job status
   - `/tmp/media/progress.txt`：`record.sh` 每一段開始的時間，卡住（job 逾時、被取消）時看卡在哪一段
   - `/tmp/media/gradle-build.txt`：CI 的 Gradle build 輸出
   - `/tmp/media/device-test.txt`：DeviceTest 的輸出
   - `/tmp/media/instrument.txt`、`logcat.txt`：DemoRecordingTest 的輸出與 log
   - `/tmp/media/release-smoke.txt`、`logcat-release.txt`：release 冒煙測試的啟動時間與 log
   - `/tmp/media/demo.mp4`：壓縮後的影片（給使用者看）

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。傳之前先核對 `info.txt` 的 commit 是這次的。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
