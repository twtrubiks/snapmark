# SnapMark

在圖片上做標註的 Android App：拍一張照、選一張照片，或開一張空白畫布 → 畫箭頭、文字、方框、印章、馬賽克 → 存到相簿或分享出去。
三個入口：**相機、照片、空白**，另外有一個存在手機裡的「作品」分頁，標註過的圖可以重新打開繼續改。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

## 目前的狀態

- **規格裡的功能都做完了**（下面「規格」寫的就是現在的樣子）：三個入口、畫布的七種工具、選取、復原／重做、自動存檔、
  裁切與旋轉、存到相簿與分享、作品分頁、深色模式、別的 App 分享圖片進來
- **只在 Android 9（API 28）的模擬器上跑過**，`DeviceTest`、`DemoRecordingTest`、`SystemUiTest`、release 冒煙測試都通過。還沒試過的：
  - Android 10 以上（存到相簿、照片挑選器、相機的畫面都不一樣）
  - 真的手機（畫的時候的效能、觸控的手感）
  - 有好幾個相簿 App 的手機上的「用其他 App 選」（AOSP 只有系統的檔案畫面會選圖，沒出現「選擇 App」）
  - Android 12 以上的桌布取色（Android 9 是 Material 3 預設的紫色）
- **第一版是 `1.0.0`**（2026-11-06 推 tag `v1.0.0` 發的）。之後發版見「專案約定」的發版
- **知道但還沒改的**：
  - 匯入照片的那一兩秒轉螢幕：匯入跑在 `rememberCoroutineScope`，被取消，相機剛拍的暫存檔也刪掉了（`SnapMarkApp`）。
    「空白」連點兩下會開兩張作品也是同一段
  - 正在打印章的字時改顏色、粗細：輸入框會變，按「完成」又變回原樣（`Editor.withColor`／`withSize`）
  - 復原、重做換回另一張底圖時讀檔失敗沒有接住，會閃退或變成白底（`CanvasSession.step`）
  - 很小的框按控制點抓的是第一個碰到的、不是最近的（`Editor.press`；`Crop` 用的是最近的）
  - 點在底圖右邊、下面外側開始打字，字整段在底圖外（`Editor.startText`）
  - 沒人用的：`Point.times`、`WorkStore.saveMarkup`（App 一律用 `saveSheet`，只剩測試在用）、`UiTest.find`（`waitFor` 的別名）

## 規格

### 首頁

- 一頁一個入口，**左右滑換頁，分頁列在畫面最下面**。四個分頁：**相機、照片、空白、作品**
- 相機、照片、空白三頁的樣子：上面一句大標、一句說明，中間一張示意圖，下面一顆大的圓形按鈕（字在圓的下面）。
  照片頁的圓下面多一顆「用其他 App 選」（那一格每頁都留著，左右滑時圓形按鈕不會跳）。
  橫的時候示意圖在左、字和按鈕在右。示意圖（`StartArt`）的背景用 Compose 畫，上面的標註是真的 `Node`，交給 `MarkupPainter.drawNodes` 畫：
  相機是觀景窗框著風景照＋箭頭＋「看這裡」、照片是一疊照片＋橢圓框＋打勾、空白是一張白紙上的燈泡＋「好點子！」＋愛心
- 最上面一排：左邊 App 的名字，右上角深色模式（跟系統／淺色／深色，`ThemeMode`，存在 SharedPreferences `settings`）。
  圖示是目前的設定，點了是下拉選單，選了整個 App 馬上換。其他設定先不做
- 啟動那一瞬間的視窗底色跟系統（`values-night`）：App 設的跟系統不一樣時，啟動畫面會先是系統的顏色。
  Android 12 以上可以用 `UiModeManager.setApplicationNightMode` 讓啟動畫面也跟著 App，要在 Android 12 以上的模擬器確認過再加

### 相機

- 按鈕 → 用**系統的相機 App** 拍（`ActivityResultContracts.TakePicture`），拍完直接進畫布。不自己做相機畫面
- 照片先寫到 App 的 `cacheDir/camera/`，用 `FileProvider` 把 URI 交給相機 App（`AndroidManifest.xml` 要加 `<provider>` 與 `res/xml/file_paths.xml`）
- **不要宣告 `CAMERA` 權限**：用系統相機不需要；一旦宣告了，沒有授權時反而開不了系統相機
- 使用者取消（沒拍）就留在首頁，暫存檔刪掉
- 手機上沒有相機 App（`ActivityNotFoundException`）：提示「找不到相機 App」

### 照片

- 用**系統的照片挑選器**（`ActivityResultContracts.PickVisualMedia`，只選圖片）：不需要任何權限。
  Android 9、10 沒有挑選器時會自動退回系統的檔案畫面（使用者確認用這個做法）
- 「選照片」下面還有一顆「用其他 App 選」（使用者想用自己慣用的相簿 App 選）：
  `ACTION_GET_CONTENT`（`image/*`）包在系統的「選擇 App」（`Intent.createChooser`，標題「用哪個 App 選照片？」）裡，
  列出手機上所有會選圖的 App（相簿 App、Google 相簿、檔案管理…），每次都問。一樣不需要權限，選到的跟挑選器走同一段匯入。
  只有一個 App 會選圖時，系統可能直接打開它
- 沒選的另一種做法：直接在頁面裡顯示相簿的縮圖格線，右上角一顆按鈕開系統相簿。要做成那樣得要「讀取照片」的權限
  （Android 13 以上 `READ_MEDIA_IMAGES`、12 以下 `READ_EXTERNAL_STORAGE`，Android 14 還有「只允許部分照片」）。要改之前先問使用者
- 讀照片要一兩秒：蓋一層「正在讀取照片…」（testTag `importing`）；讀不了（不是圖、壞掉的檔案）提示「讀不了這張照片」

### 底圖（相機拍的、照片選的）

- 複製進 App 自己的空間 `filesDir/works/<id>/base.jpg`（`WorkStore.baseFile`）：依 EXIF 轉正、長邊縮到 4096px 以內
  （使用者確認，不保留原始大小）、JPEG 品質 92，HEIC 一樣轉成 JPEG，有透明的圖墊白色。
  不留原圖的 URI（挑選器給的讀取權限是暫時的），也不動原圖
- EXIF 不帶進去（尤其是 GPS）
- 另外存一張 `thumb.jpg`（長邊 512px，**畫上標註之後**的樣子，JPEG 85）給作品分頁用：匯入時寫一次，之後每次存標註時重畫
  （`CanvasSession` 先寫縮圖再存標註，首頁重讀時縮圖已經是新的）。空白畫布第一次存標註時才有

### 空白

- 按鈕 → 開一張白色的畫布進畫布頁。大小固定 1440×1920（直向 3:4，`SnapMarkApp` 的 `BLANK_WIDTH`／`BLANK_HEIGHT`），
  同一張作品在每支手機上都一樣大（不照螢幕大小）

### 畫布

圖放在中間，四個角落各有浮在圖上面的控制項：

```
┌──────────────────────────────┐
│ ← 返回    存到相簿  分享 ⋮   │  上面一排：⋮ 裡面是裁切、清除標註、向右轉、向左轉
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
- **打開畫布時是上次用的工具、顏色、粗細**，第一次用是**筆**（麥克筆、粉紅、中）。
  選了工具、子選項、顏色、粗細就記下來（`ToolMemory`，存在 SharedPreferences `settings`）。平移不記（記的是平移之前用來畫的）；
  上次是馬賽克、這次打開的是空白畫布（沒有馬賽克）就用筆。選單的順序不變（箭頭還是最下面那一個）
- 手指拖一下就是一個物件：
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
- **上面一排**：返回、存到相簿、分享（見「存檔與分享」）、⋮ 選單（裁切、向右轉、向左轉、清除標註）：
  - 裁切：一開始框住整張，上面變成「取消」、「拉四個角裁切」、「完成」，四個角落的控制項先藏起來。
    拉四個角改大小、按在框裡拖著走、按在框外面重新拉一個；框外面調暗、有三等分的細線。框不會超出底圖，
    放開時比 16 像素小就退回去（`Crop`）。兩指一樣縮放平移。返回鍵是取消。裁切、旋轉的是整張作品（底圖＋標註一起）
  - 清除標註：全部清掉（可以復原），裁切與旋轉不動；沒有標註時不能按
  - 向右轉、向左轉：90 度（剛好對齊像素，底圖不會模糊）
  - 裁切、旋轉要零點幾秒（寫一張新的底圖）：蓋一層「正在處理圖片…」（testTag `transforming`），做完縮放回到剛好放進畫面
  - 怎麼存（使用者確認）：直接改底圖與每個標註的座標，裁掉的拿不回來——只有這一次打開畫布的期間可以按復原
    （復原時換回舊的底圖檔，離開畫布時刪掉）。之前畫的標註裁切之後維持原本的像素大小（`Node.scale`），
    新畫的照裁切後的長邊算；馬賽克的格子也維持原本的大小（不會變細到看得出底下的字）。整個在框外面的標註拿掉
- **自動存檔**：每改一次就存（`WorkStore.saveSheet`，在 `Dispatchers.IO`；連續拖動時不要每一格都存），離開畫布不用問。
  不跳「放棄這些標註嗎？」這種對話框：有作品分頁，隨時可以回來改。
  例外：**剛開的作品什麼都沒畫就離開，直接刪掉**（空白畫布一定刪；相機、照片的也刪——使用者只是點進來看一下）
- 轉螢幕不能丟東西：畫到一半的、選到的工具與顏色、打到一半的文字、縮放的位置

### 存檔與分享

- 成品是把標註畫在底圖上的一張圖，**大小跟底圖一樣**（不是螢幕截圖）：有底圖的存 JPEG（品質 92）、空白畫布存 PNG
  （使用者確認；一律 PNG 的話 1200 萬畫素的照片一張會有 20 MB 以上）
- 畫成品圖的程式跟畫面上畫標註的要是同一份（`android.graphics.Canvas` 或 Compose 的 `DrawScope` 選一個，另一邊轉接），
  不然畫面上看到的跟存出來的會不一樣
- 畫布右上角「存到相簿」、「分享」各一顆，圖示加字（不收進選單裡：收起來使用者找不到）：
  - **存到相簿**：`MediaStore`，放在 `Pictures/SnapMark/`，檔名 `SnapMark-<YYYYMMDD-HHmmss>.<jpg|png>`。
    **存好就離開畫布**（留在畫布的話提示一閃就過、不確定存了沒，存完再改相簿那張也不會變）：
    回到首頁的「作品」分頁，剛存的那張排在最前面（格線捲到最上面），下面的 Snackbar 提示「已存到相簿」（testTag `saved_snackbar`，約 10 秒）
    與「繼續改」（打開同一張，復原紀錄是新的）。存失敗留在畫布可以再按；剛開的作品沒畫東西就存到相簿也不刪（`CanvasSession.finish`）。
    Android 10 以上不需要權限（寫的時候先標 `IS_PENDING`）；Android 9 要 `WRITE_EXTERNAL_STORAGE`（manifest 寫 `android:maxSdkVersion="28"`，
    按的時候才問，不允許就提示「沒有允許存取相片，存不進相簿」），直接寫檔案再請系統掃描
  - **分享**：留在畫布（分享選單常常取消，回來還在畫布上）。成品圖寫到 `cacheDir/share/`，用 `FileProvider` ＋ `ACTION_SEND`（`ClipData` 也帶上，分享選單的預覽圖才讀得到）交給系統的分享選單。
    一小時以前分享的檔案在下次分享時刪掉（收到的 App 可能還在讀剛剛那張）
  - 畫成品圖要零點幾秒到一兩秒：蓋一層「正在存到相簿…」／「正在準備圖片…」（testTag `exporting`）
- 存到相簿、分享都不會動作品：作品留著，之後還可以改了再存一次。存到相簿時記下那張圖的網址（`gallery_images`），刪作品時可以選要不要一起刪（見「作品」）

### 作品

- 「作品」分頁：縮圖的格線（`thumb.jpg`，`LazyVerticalStaggeredGrid`，每張照原本的比例，太細長的裁到 1:2），最近改過的在前面。
  上面一句「點一下繼續改，長按可以刪除」。點一張打開繼續改；長按 → 刪除（要確認：「刪掉就找不回來了。」），資料夾整個刪掉。
  存到相簿過、相簿裡還在的話，確認裡多一格「一併刪除存到相簿的 N 張圖」，**預設不勾**（使用者要的），
  沒勾就說「存到相簿的圖會留著」。N 只算相簿裡還查得到的（使用者可能已經在相簿 App 刪掉了）。
  Android 10 以上 App 自己存的圖不用權限、系統也不再問就刪得掉；Android 9 用存的時候拿到的權限。
  刪不掉的（權限被收回去之類）提示「相簿裡有 N 張圖刪不掉，請到相簿 App 刪除」
- 空的時候說明：標註過的圖都會在這裡
- 作品只存在這支手機的 App 私有空間：移除 App 就沒了（`allowBackup` 關著）。要留著的圖請「存到相簿」

### 別的 App 分享圖片進來

- `ACTION_SEND`、`image/*`（相簿、瀏覽器、截圖的「分享」）：當成選了一張照片，直接開一張作品進畫布。
  `MainActivity` 是 `singleTask`，App 開著時用 `onNewIntent` 接（`MainActivity.receive` → `SnapMarkApp` 的 `sharedImage`）；
  轉螢幕、被系統收掉再回來時不會再開一次（`savedInstanceState` 不是 null 就不看 intent）。
  SnapMark 自己的分享選單裡不列 SnapMark（`EXTRA_EXCLUDE_COMPONENTS`）
- `ACTION_VIEW`、`ACTION_EDIT`（被別的 App 叫來編輯、把結果傳回去）先不做

### 不做

帳號、登入、雲端同步、地圖、網頁截圖、PDF、付費與試用期、使用分析（analytics）、當機回報、評分提示、教學動畫。
**App 不連網，不要加 `INTERNET` 權限**：裝置 ID、使用行為、圖片什麼都不往外送，這是這個 App 的賣點。

### 先不做（要做之前先問使用者）

作品的匯出／匯入與備份、資料夾或標籤、搜尋、多選刪除、顏色自訂（八色以外）、字型選擇、圖層順序調整、
平板的橫向版面、形狀辨識（手畫的圓自動變成橢圓）、自己做相機畫面（CameraX）。

### 還可以再討論的（要改之前先問使用者）

| 題目 | 目前的做法 | 另一個選擇 |
| --- | --- | --- |
| 作品要不要備份 | `allowBackup="false"`，移除 App 就沒了 | 開系統備份（上限 25 MB，放不了幾張），或做匯出／匯入 |
| 深淺色 | 跟系統，Material 3（Android 12 以上桌布取色） | 固定淺色＋粉紅（`#FF4678`）當主色 |

### 做新功能的順序

先寫 JVM 測試 → VM 內 build 通過 → 更新 `DemoRecordingTest` → push 看錄影。

## 程式的分層

- **規則都在純 Kotlin、有 JVM 測試**，不 import `android.*`、`androidx.compose.*`：
  - `Markup`（已有）：標註的資料模型，`kotlinx.serialization` 存成 JSON。**座標一律是底圖的像素**，跟螢幕大小、縮放無關。
    每個 `Node` 有 `scale`（預設 1，JSON 裡不寫）：粗細、字級、印章大小、馬賽克格子照長邊算完再乘它（裁切之後維持原本的像素大小）。
    **要知道幾個像素一律用 `Selection.kt` 的 `lineWidth`／`fontSize`／`radius`／`labelTextSize`／`cell`**，不要直接呼叫 `StrokeSize.width`
  - `Transform.kt`（`TransformTest`）：裁切（`Markup.cropped`：移到新的左上角、`scale` 乘上舊長邊／新長邊、框外的拿掉、馬賽克切成框裡那塊）、
    旋轉（`Markup.rotated`：向右轉 (x, y) → (h - y, x)；文字繞著自己的中心轉、保持正的）、裁切範圍 `IntBox`
  - `Crop.kt`（`CropTest`）：裁切畫面上的框（拉角、拖著走、重新拉、太小退回去、對齊整數像素的 `rect`）
  - `WorkStore`（已有）：作品的資料表與檔案位置
  - `Geometry.kt`（已有，`GeometryTest`）：箭頭的外形 `arrowOutline`、粗細的像素 `StrokeSize.width`（跟著底圖的長邊）、
    縮放平移：`Zoom`（「剛好放進畫面」的幾倍＋畫面正中間對到底圖哪一點，跟螢幕大小無關，轉螢幕留得住）、
    `CanvasFrame`（把圖放進畫面、兩指縮放平移、不能拖出畫面）、`Viewport`（螢幕座標 ↔ 底圖座標）、
    底圖縮小 `fitWithin`（`BASE_MAX_SIDE` 4096）、馬賽克的格子 `mosaicBlock`／`mosaicGrid`（長邊的 1/48，對齊整張底圖）
  - `Editor.kt`（已有，`EditorTest`）：編輯的狀態機 `Editor`（知道底圖大小；目前的工具與子選項 `shape`／`highlighter`／`stamp`、顏色、粗細，
    這幾個合起來是 `ToolChoice`：預設 `ToolChoice.DEFAULT` 是筆，`remembered` 平移不記、`opening` 空白畫布不用馬賽克）。
    手指按下／移動／放開 → 產生哪個 `Node`：`gesture` 記這次手勢有沒有拖動（超過 touch slop，換算成底圖像素傳進來），
    `draft` 是手指還沒放開的那一個，放開才算一步。箭頭、形狀、筆要拖動才算；印章按下就出現、拖著走、放開蓋上去；
    文字是點一下開始打字（`text: TextEdit`，按「完成」才加進 `Markup`）；馬賽克跟形狀一樣拉一個框，框拉不出底圖。
    復原堆疊 `History`（存每一步之前的整份 `Sheet`＝標註＋大小＋底圖是哪個檔 `base`，最多 100 步）；
    整張作品的 `clear`／`cropped`／`rotated`（底圖的新檔名由呼叫的人寫好再傳進來）
    選取：`selected` 是選到的 id；按在選到的東西上是 `Grab.Move`（拖著走）、按在控制點上是 `Grab.Handle`（改大小／方向），
    移動、改大小也先放在 `draft`，放開才寫進 `Markup`；點一下（`gesture.moved` 是 false）在 `tap` 裡決定選誰、取消選取、
    開始打字、蓋印章；點兩下看 `lastTap`（`release` 傳進來的時間）
  - `Selection.kt`（已有，`SelectionTest`）：點到哪一個（`Node.hit`、`topAt`，線條類的要點在線上，方框、橢圓的裡面不算）、
    範圍（`Node.bounds`）、控制點（`Node.handles`／`withHandle`）、移動（`moved`）、改顏色粗細（`recolored`／`resized`）、
    印章的字的位置（`labelAt`）。文字的大小要量字型：`TextMeasure` 介面，手機上是 `AndroidTextMeasure`（跟畫的同一套排版 `textLayout`），
    JVM 測試是粗略估計的 `TextMeasure.APPROX`
  - 這些是這個 App 最容易出錯的地方，全部要能在 JVM 測，不要寫在 Composable 裡
- `WorkStore` 透過 `Sql` 介面存取資料庫：手機上是 `AndroidSql`（`databases/snapmark.db`），
  JVM 測試是 `JdbcSql`（sqlite-jdbc 的記憶體資料庫，SQL 跑在真的 SQLite 上）。
  **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`VACUUM INTO`、`RETURNING` 這些新語法，
  JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑
- 資料表 `works`：主鍵是 UUID（也是 `filesDir/works/` 底下資料夾的名字）、`has_base`、`width`、`height`、`markup`（JSON）、
  `created_at`、`updated_at`（epoch 毫秒）、`base_name`（底圖的檔名，預設 `base.jpg`；後來才加的欄位，舊的表在 `WorkStore` 的 init 用
  `PRAGMA table_info` 判斷、`ALTER TABLE ... ADD COLUMN` 補上）。改 schema 時照這樣做，手機上已經有的資料不能丟。
  裁切、旋轉：先寫好新的底圖 `base-<uuid>.jpg`，再用 `saveSheet` 一句 UPDATE 把標註、大小、`base_name` 一起換掉（中途被關掉也不會對不上）；
  舊的底圖留給復原，離開畫布時 `removeUnusedBases`
- 資料表 `gallery_images`：`work_id`、`uri`（存到相簿那張的 MediaStore 網址）、`saved_at`。一張作品存幾次就幾筆，刪作品時一起刪（同一個 transaction）。
  新的表用 `CREATE TABLE IF NOT EXISTS`，舊的資料庫一樣建得起來
- **`Markup` 的 JSON 發版之後就是要長期相容的格式**：加欄位一律給預設值（舊的 JSON 讀得回來），不改名字、不改意思；
  `Node` 的 `@SerialName` 不能改（`MarkupTest` 有每一種寫死的 JSON，跟著改字串就是破壞相容）
- 要碰 Android 的放在薄薄一層：`AndroidSql`、底圖的轉正縮小與畫成品圖（`Bitmap`，`DeviceTest` 測）、畫面 `*Screen.kt`、
  整個 App 的導覽在 `SnapMarkApp`（sealed interface 的 Route ＋ `rememberSaveable` 的 stack，不另外引入 Navigation 套件），
  共用的東西在 `AppContainer`（`SnapMarkApplication.container`）
  - `BaseImage.kt`（`DeviceTest` 測）：`decodeBase` 用 ImageDecoder 讀圖（照 EXIF 轉正、`setTargetSize` 一邊讀一邊縮、sRGB、透明墊白）、
    `writeJpeg`（先寫暫存檔再改名；Bitmap.compress 寫出來的 JPEG 沒有 EXIF，GPS 不會帶過來）、
    `AppContainer.importPhoto`（相機、照片共用：讀圖 → 建作品 → 寫 `base.jpg`，寫不進去就刪掉作品）、
    `BaseLayer`（畫布上的底圖＋整張先打碎好的馬賽克，一格一個像素；格子大小不一樣（裁切前畫的）各打碎一份，`prepare` 在背景先算好）、
    `cropBitmap`／`rotateBitmap`（裁切、轉 90 度，回傳新的一張，舊的那張留給復原）
  - `Output.kt`（純 Kotlin，`OutputTest`）：成品圖的格式 `OutputFormat`（照片 JPEG、空白 PNG）、檔名 `outputName`（同一秒的加 `-2`）、
    縮圖的大小 `THUMB_MAX_SIDE`
  - `Render.kt`（`DeviceTest` 測）：`renderWork` 畫成品圖（跟底圖一樣大，用 `MarkupPainter`）、`Thumbnailer` 畫縮圖
    （底圖先用 `halveDownTo` 縮到比縮圖大一點，馬賽克借原圖的 `BaseLayer.mosaic`——格子是照原圖切的）
  - `Export.kt`：`saveToGallery`（Android 10 以上 MediaStore、Android 9 寫檔案＋`MediaScannerConnection`）、`writeShareFile`（`cacheDir/share/`）、
    `shareChooser`、刪作品時用的 `galleryImageExists`／`deleteFromGallery`（Android 9 MediaStore 那一筆刪掉之後檔案還在的話自己刪）。都在背景執行緒。
    `AppContainer.galleryImagesOf`（還在相簿裡的）、`deleteWork`（先刪相簿的再刪作品，回傳刪不掉的張數）
  - `PhotoSources.kt`：首頁兩個入口接系統的相機 App（`TakePicture`，照片寫到 `cacheDir/camera/`，`FileProvider` 的 authority 是
    `<applicationId>.files`，路徑在 `res/xml/file_paths.xml`）與照片挑選器（`PickVisualMedia`），還有「用其他 App 選」
    （`GetImageFromApp`：`GetContent` 包一層 `Intent.createChooser`）。**不宣告 `CAMERA` 權限**。
    不看相機 App 回傳的成功與否，暫存檔有內容就是拍到了
  - `MarkupPainter`：把作品畫到 `android.graphics.Canvas`（座標是底圖像素）。畫面上由 `CanvasScreen` 用 `drawIntoCanvas`
    套上 `Viewport` 再呼叫；畫成品圖、縮圖也用它（同一份程式，看到的跟存出來的才一樣）。印章的樣子在 `StampArt`（工具選單的圖示也用它）。
    首頁的示意圖（`HomeArt.kt`）用 `drawNodes`：只畫標註、不畫底圖。
    正在打的字不經過它：`CanvasScreen` 在文字的位置放一個 `BasicTextField`，按「完成」之後才由它畫成卡通字。
    影子：實心的（箭頭、印章）用 `setShadowLayer`；框線、筆畫用 `drawWithShadow`（幾圈越來越粗的半透明黑線，不模糊）——
    硬體繪圖替空心的框線做模糊會畫壞（Android 9 模擬器上看到的），要加新的框線類標註也照這樣畫。
    底圖與馬賽克：有 `BaseLayer` 就畫照片（不然是白色），馬賽克是在框裡貼上 `BaseLayer.mosaic` 放大回去的樣子，
    打碎的是底圖本身（蓋在之前畫的標註上面時標註也被蓋掉）。**Android 9 起 `Paint()` 預設會內插圖片**：
    要格子是方的（馬賽克）要明講 `isFilterBitmap = false`
  - `CanvasSession`：一次打開畫布的期間（`Editor`、`Zoom`、展開的選單、底圖 `base`——跟作品一起讀好才顯示，不會先白一下）。
    存到相簿、分享：`export(Export.SAVE / SHARE)` 在背景畫成品圖，好了從 `exported`（Channel，轉螢幕時新的畫面也收得到）通知畫面提示或開分享選單。
    裁切、旋轉：`transform` 在背景做出新的底圖、寫檔，再一起換掉 `editor` 與 `base`（作品現在的大小看 `editor.width`／`height`，不是 `work`）；
    復原、重做換到另一張底圖時先讀回來（`bases` 留著最近兩張）；`crop` 是裁切中的框。存檔排的是整份 `Sheet` 與它的 `Thumbnailer`。`CanvasScreen` 用 Compose 的 `retain` 拿著：
    轉螢幕留著、離開畫布就丟掉（所以復原只記這一次）。自動存檔用 `Channel.CONFLATED` 排隊，只有 `Markup` 真的變了才存（拖動時改的是 `draft`，不存）；
    按返回時 `finish()`：存完最後一次，`Route.Canvas.isNew` 的作品沒畫東西就刪掉
  - **畫的時候的效能**（`PerfTest` 量）：
    - 手指移動時 `editor` 每一格都換一個新的。`CanvasScreen` 不在組畫面時讀整個 `editor`，只讀 `CanvasUi`（`derivedStateOf`：
      按鈕、輸入框、泡泡要看的那幾樣），沒變就不重組；要畫的在 `DrawingSurface` 畫的階段才讀。要加新的按鈕時，把它要看的值加進 `CanvasUi`，
      不要在 `CanvasScreen` 直接讀 `session.editor`
    - `DrawingSurface` 分兩層（`Editor.drawLayers`）：底圖與手指移動時不會變的（正在畫、拖著走的那一個底下的，`CanvasSession.settledNodes`）
      放在 `CompositingStrategy.Offscreen` 的圖層，畫成一張圖留著；正在畫的那一個與蓋在它上面的、選取框、裁切框每一格重畫
      （`MarkupPainter.drawOnPage`，不畫底圖）。兩層合起來的順序跟 `visibleNodes` 一樣（`EditorTest`），預覽逐像素比對過沒有變
  - `AppContainer.worksDispatcher`：作品的讀寫一律排在這一條（一次一件、照順序），剛離開畫布馬上再打開同一張，讀到的一定是存完的；
    `background` 是離開畫面之後還要做完的事（最後一次存檔、刪除）；`worksRevision` 在 `WorkStore` 每次寫入後加一，首頁看著它重讀
    （只在首頁看得到的時候讀：在畫布上每畫一筆就存一次，那時候重讀每一張的標註是白做工，還跟存檔排在同一條上）。
    首頁的縮圖用 Coil（`AsyncImage`），`memoryCacheKey` 帶著 `updatedAt`：存過一次就換 key，不會拿到記憶體裡舊的那張
- 在 `canvas.withClip { }`、`withTranslation { }` 這種以 `Canvas` 為 receiver 的 lambda 裡，`width`／`height` 是 `Canvas.getWidth()`（畫面的大小），
  不是外面 class 的屬性：`MarkupPainter` 因此把底圖大小叫 `imageWidth`／`imageHeight`
- 資料庫與檔案一律在 `Dispatchers.IO`，不要在組畫面時讀資料庫、解圖片
- 顯示圖片用 Coil（只有 `coil-compose`，讀本機檔案與 `content://`，沒有接網路層）：目前只有作品分頁的縮圖
- 設定（`Settings.kt`）：`EnumSetting` 把 enum 的名字存進 SharedPreferences `settings`，`value` 是 Compose 的狀態（`SettingsTest`）。
  `ToolMemory`（`AppContainer.tools`）：上次用的工具、顏色、粗細，`CanvasSession` 打開畫布時套上、選了就 `remember`

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
- 目前的版本：
  AGP 9.4.1、Kotlin 2.4.21、Gradle wrapper 9.8.2、Compose BOM 2026.09.00、activity-compose 1.13.0、core-ktx 1.19.1、
  Coil 3.6.3、kotlinx-serialization-json 1.12.0、exifinterface 1.4.2、
  sqlite-jdbc 3.53.4.0（只給 JVM 測試）、androidx.test runner 1.7.0、core 1.7.0、ext:junit 1.3.0、uiautomator 2.4.0。
  若 build 報版本相容問題，以錯誤訊息為準調整，並更新這段
- 圖示不引入 `material-icons` 套件：要用的圖示一個一個放成 `res/drawable/ic_*.xml`（vector）
- Coil、kotlinx.serialization 都自帶 R8 規則，`app/proguard-rules.pro` 目前是空的
- `android:allowBackup="false"`：作品是使用者自己的照片，檔案大也比較私人（見「還可以再討論的」）
- 畫面用 Material 3，Android 12 以上用桌布取色（`Theme.kt`）。深淺預設跟系統，首頁右上角可以改（`ThemeMode`，`AppContainer.theme`）。
  啟動那一瞬間的視窗底色（`values-night`）跟系統
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `SNAPMARK_KEYSTORE`、`SNAPMARK_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `snapmark`）；沒有就用這台機器的 debug 金鑰（錄影的 CI、自己 build）
- **給別人（包括使用者）裝的 APK 一律從 GitHub Release 來**，不要把 VM 裡 build 的 APK 傳給使用者：
  VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 不能直接更新，只能移除重裝——**移除就會刪掉手機上的作品**
  - 例外：使用者要先拿測試版試：在 scratchpad 複製一份專案，複本的 `defaultConfig` 加
    `applicationIdSuffix = ".test"`、`app_name` 改成「SnapMark 測試」，`SNAPMARK_VERSION_NAME=test-<commit>` build release 版再傳。
    跟正式版是兩個 App（`com.twtrubiks.snapmark.test`），可以並存、不會擋到正式版的更新；作品各自分開，不會互通。repo 本身不改
  - 發版：`.github/workflows/release.yml`。版本號是語意化版本 `MAJOR.MINOR.PATCH`（使用者選的，第一版 `1.0.0`）：
    修 bug 加 PATCH（`1.0.1`）、加功能加 MINOR（`1.1.0`）、大改才加 MAJOR。要發哪一版、版本號是多少先問使用者
  - 兩種發法，都會 build、簽名、發 `v<版本>` 的 Release；那個版本已經發過的話跳過：
    - 使用者自己的電腦：推 tag `v<版本>`（`git tag -a v1.0.1 -m "SnapMark 1.0.1" && git push origin v1.0.1`）
    - 雲端 session：git 只能推自己的 branch，推 tag 會斷線，改用版本檔：`release-version` 寫新版本（`1.0.1`，不加 `v`）推上去，
      workflow 會在那個 commit 上建 tag。`1.0.0` 是推 tag 發的，repo 裡還沒有這個檔案，第一次用時新增
  - 發版金鑰在使用者那裡（憑證 SHA-256 在 `release.yml` 的 `EXPECTED_CERT`），GitHub Secrets
    `ANDROID_SIGNING_KEYSTORE`（base64）、`ANDROID_SIGNING_PASSWORD` 已經設好。**不要改 `EXPECTED_CERT`、不要再產生新的金鑰**：
    換了金鑰，裝了舊版的手機不能直接更新，只能移除重裝（作品會一起刪掉）
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
- Maven Central 偶爾會對共用出口 IP 回很長的 `retry-after`（例如 80 分鐘）：只能等，不要改用鏡像（權限檢查會擋「轉向別的下載來源」）。
  等的時候可以先把要新下載的依賴拿掉，用已經在快取裡的東西繼續做

## 畫面預覽（Robolectric，VM 內就看得到畫面）

雲端 VM 沒有模擬器：改了畫面就用 Robolectric 在 JVM 上把整個 App 跑起來、存成 PNG，用 Read 看。**選用的**，要加 `-Ppreview`：

```bash
./gradlew testDebugUnitTest -Ppreview --tests '*PreviewTest'
```

- 為什麼是選用的：Robolectric 畫圖用的 `nativeruntime-dist-compat` jar 有 150 MB，雲端 VM 的共用 IP 下載它常被 Maven Central 限流
  （遇過 `retry-after` 19 小時）。沒有 `-Ppreview` 時 build、JVM 測試、CI 都碰不到 Robolectric，不會被它卡住
- 預覽的測試放在 `app/src/preview/java`（只有 `-Ppreview` 才加進 test 的 source set），繼承 `PreviewTest`
  （它的 `@Before` 把工具設成箭頭，流程從箭頭開始畫；第一次是筆、記住上次的在 `ToolMemoryPreviewTest`）：
  `createAndroidComposeRule<MainActivity>()` 開 App，用 testTag 點（跟 `DemoRecordingTest` 同一套），
  `shot("名字")` 存到 `app/build/previews/<class>-<名字>.png`。螢幕是 Pixel 7 Pro 的大小（411×891 dp，xxhdpi），
  轉螢幕用 `RuntimeEnvironment.setQualifiers("+land")` ＋ `activityRule.scenario.recreate()`；存檔在背景，用 `waitForNodes(n)` 等
- 模擬器上的測試與預覽共用的測試資料放在 `app/src/sharedTest/java`（例如示範馬賽克的 `DemoPhoto`）
- Robolectric 的 ImageDecoder 讀不了檔案（`Only supported on Android`）：預覽裡匯入照片改給它 `ByteBuffer`（`PhotoPreviewTest`）；
  Coil 在 API 28 以上也用 ImageDecoder，`PreviewTest` 換成 `imageDecoderEnabled(false)` 的 ImageLoader（用 BitmapFactory），縮圖才看得到
- 對話框、下拉選單是另一個視窗，`captureToImage` 截不到（截出來是主畫面的一角）：那些畫面看 CI 的截圖
- `SavePreviewTest`：Robolectric 沒有相簿，換成假的 MediaStore（`FakeGallery`，`Robolectric.setupContentProvider`），
  看存好之後回到作品分頁、「繼續改」、從格線下面打開的舊作品存完捲到最上面
- `TransformPreviewTest` 把裁切、旋轉整段跑一次（真的寫底圖檔、復原換回舊的底圖、離開時刪掉沒在用的），改到 `CanvasSession` 時先跑它
- 看得到的：版面、畫出來的標註（`MarkupPainter` 真的畫在 Canvas 上）、流程與存檔、轉螢幕。
  看不到的：觸控的手感、效能、鍵盤、系統畫面（相機、挑選器、分享），這些還是要看 CI 的錄影
- 那個 jar 被限流時：只能等，不要改用鏡像（權限檢查會擋「轉向別的下載來源」）。
  如果這台 VM 的 Gradle 快取裡已經有同一個版本的 jar（`~/.gradle/caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat/`），
  而且 `sha1sum` 跟 Maven Central 上的 `.jar.sha1` 一樣，可以在 scratchpad 複製一份專案，在複本的 `app/build.gradle.kts` 最後加
  `configurations.configureEach { exclude(group = "org.robolectric", module = "nativeruntime-dist-compat") }` 與
  `dependencies { testImplementation(files("<那個 jar>")) }` 來跑（repo 本身不改）

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest
```

`assembleRelease` 會跑 R8，可以先抓到缺 class 之類的錯誤；執行時才會出現的問題要看 CI 的 release 冒煙測試。
改了畫面的話，另外跑一次畫面預覽（上一節，`-Ppreview`），看 `app/build/previews/` 的 PNG。
和 Android 無關的邏輯（幾何、編輯的狀態機、復原、JSON）寫成純 Kotlin，放在 JVM 單元測試裡測，幾秒就有結果。
CI 錄影前也會跑 `testDebugUnitTest`，沒過就不會錄影（失敗原因在 `gradle-build.txt`）。

## 錄影測試：DemoRecordingTest

`app/src/androidTest/java/com/twtrubiks/snapmark/DemoRecordingTest.kt`，CI 只錄這個 class，
`record.sh` 在它執行期間用 `screenrecord` 錄整個螢幕。找元件、輪詢、截圖這些放在 `UiTest`。

- 用 UiAutomator 操作（真實時間驅動，動畫會正常播放），不要用 Compose test rule
- 不要用 `ActivityScenario` 開 App，用 `UiTest.launchApp()`（`context.startActivity`）；找元件用 `waitFor` 每 0.1 秒輪詢，
  不要用 `device.wait(Until...)` 或 `device.waitForIdle()`。元件找到之後畫面又重組了，按之前要重新找一次，否則會拿到 `StaleObjectException`。
  首頁按分頁之後頁面還要滑一下，滑的時候點下去只會停住滑動、點不到東西：要馬上點頁面裡的東西時用 `UiTest.openTab(tab, selector)`
  （等它停在同一個位置 0.3 秒）
- 開頭把深色模式設回跟系統、工具設成箭頭（`container.tools.remember(ToolChoice(Tool.ARROW))`：App 第一次打開畫布是筆，記住上次用的），
  模擬器上別的測試（`SystemUiTest` 在 `pm clear` 之後跑）拉一下畫出來的是筆
- 一個 `@Test` 跑完整的展示流程，每個操作之間停 0.6～1.2 秒讓影片看得清楚。`screenrecord` 一次最多 180 秒，
  `record.sh` 測試還在跑就接著錄下一段（`demo-1.mp4`、`demo-2.mp4`…），`record.yml` 用 ffmpeg 接起來（段跟段之間少不到一秒），
  所以長度不受限；但影片是給人看的，盡量 3 分鐘內，別的測試已經測到的重複操作不用放
- 用 `By.text(...)` 找元件；要用 testTag 時用 `By.res(...)`（`SnapMarkApp` 的 root 已經開了 `testTagsAsResourceId`）。
  目前的 testTag：分頁 `tab_camera`／`tab_photos`／`tab_blank`／`tab_works`、按鈕 `camera_button`／`photos_button`／`blank_button`（圓形大按鈕）、照片頁的「用其他 App 選」`photos_other_app`、
  首頁右上角的深色模式 `theme_button` 與展開後的 `theme_system`／`theme_light`／`theme_dark`（`ThemeMode.tag`）、
  `work_list`（作品分頁的格線）與每一張縮圖 `work_<id>`（說明文字是「照片／空白畫布，N 個標註」：用 `By.descContains("N 個標註")` 找）、
  長按之後的確認 `delete_confirm`／`delete_cancel` 與「一併刪除存到相簿的圖」那一格 `delete_gallery`（有存過相簿才有）、
  `canvas`（畫圖、接手勢的整塊區域，作品放在它正中間）、`canvas_back`、畫布右上角的 `save_button`（存到相簿）／`share_button`、
  存到相簿之後作品分頁下面的提示 `saved_snackbar`（「繼續改」用 `By.text("繼續改")` 找）、
  `more_button`（⋮）與展開後的 `menu_crop`／`menu_rotate_right`／`menu_rotate_left`／`menu_clear`、裁切時上面的 `crop_cancel`／`crop_done`、
  畫成品圖時蓋住畫面的 `exporting`、裁切旋轉時的 `transforming`、`undo`／`redo`（有東西可以復原／重做時才有）、
  `tool_button` 與展開後的 `tool_<工具>`（`Tool.tag`，例如 `tool_arrow`、`tool_pan`；`tool_pixelate` 只有照片的作品才有）、
  `color_button` 與展開後的 `color_<顏色>`（`MarkupColor.tag`，例如 `color_blue`）、`size_<粗細>`（`StrokeSize.tag`，例如 `size_xl`）、
  工具的子選項 `shape_<形狀>`（`ShapeKind.tag`）、`pen_marker`／`pen_highlighter`（`penTag()`）、`stamp_<印章>`（`StampKind.tag`）
  （形狀、筆、印章要先點一下 `tool_<工具>` 才會展開子選項）、打字的輸入框 `text_field` 與上面的 `text_done`（完成）、
  選到東西時右上角的 `delete`、選到文字時泡泡上的 `bubble_cartoon`／`bubble_plain`、選到印章時的 `bubble_tail`／`bubble_label`、
  讀照片時蓋住整個畫面的 `importing`
- **點在已經畫好的東西上是選取它**（任何工具都是）：要畫新的、蓋印章、開始打字，要點在空白的地方，
  有東西選著時先點一下空白取消選取。座標要算好跟已經畫好的東西隔開（手指點得到的距離大約是 20dp）
- **在畫布上畫東西用座標**：`device.swipe(x1, y1, x2, y2, steps)` 拉箭頭、`device.click(x, y)` 蓋印章；
  座標從 `canvas` 那個元件的 `visibleBounds` 算，不要寫死。兩指縮放用 `UiObject2.pinchOpen`／`pinchClose`
- 畫出來對不對 UiAutomator 讀不到：用 `screenshot("名稱")` 存截圖給人（Claude）看；要自動判斷的話，
  讀 `container.works` 裡存下來的 `Markup`（例如拉一下之後多了一個 `Node.Arrow`）。存檔在背景，用 `UiTest.waitForNodes` 等；
  在畫布上拉一下用 `UiTest.drag`（座標是元件大小的比例）
- BottomSheet、Dialog、DropdownMenu 是另一個視窗，App 根部開的 `testTagsAsResourceId` 管不到：要在裡面再開一次，或用 `By.text` 找
- 開始前清空作品（`clearWorks()`），要用到的資料都在測試裡建。照片的作品：測試自己畫一張（`DemoPhoto`），
  放在 `cacheDir/share/`，用 `ACTION_SEND`（FileProvider 的網址）像別的 App 一樣分享給 SnapMark，直接開成作品進畫布
  （跟相機、照片同一段匯入，系統的畫面交給 `SystemUiTest`）；`SystemUiTest` 裡是 `container.importPhoto` 再從作品分頁打開
- 裁切：⋮ → `menu_crop`，用 `imageToScreen` 從底圖的角（例如 (0, 0)）拉到要的位置，再按 `crop_done`；裁切、旋轉、
  換底圖的復原都要等 `transforming` 消失（`UiTest.waitUntilGone`）
- 要對準照片上的某個地方（例如用馬賽克蓋住電話號碼）：`UiTest.imageToScreen` 把底圖座標換成螢幕座標（跟畫布同一套 `CanvasFrame`）
- 打字用 `waitFor(By.res("text_field")).text = "..."`，打完按 `text_done`（會收起鍵盤）。
  要用返回鍵收鍵盤的話，鍵盤開著才按——沒開的時候按返回鍵會離開那一頁（在 `UiTest` 加 `hideKeyboard()`：`dumpsys input_method` 有 `mInputShown=true` 才按返回鍵）

## 模擬器上的測試：DeviceTest

`app/src/androidTest/java/com/twtrubiks/snapmark/DeviceTest.kt`，`record.sh` 在錄影前先跑、不錄影，輸出在 `device-test.txt`。
只放模擬器才測得到的事：

- `WorkStore` 在 Android 9 的 SQLite 上跑一輪
- 底圖：上下兩色的 JPEG 寫上 EXIF 方向（1、3、6、8）與 GPS，`decodeBase` 之後核對大小與顏色的位置（只看長寬的話轉錯方向也會過）、
  存出來的 `base.jpg` 沒有 GPS 與相機型號；5000px 寬的縮到 4096；透明的 PNG 墊白；`importPhoto` 建出有底圖的作品
- 馬賽克：一個像素黑白相間的格紋打碎之後，框裡是灰的、同一格一樣（包括最右邊不滿一格的），框外還是格紋
- 成品圖：在已知顏色的底圖上畫一支箭頭、一塊馬賽克，核對大小與幾個點的顏色；照片存 JPEG、空白存 PNG（PNG 讀回來每個像素都一樣）；
  縮圖 512 以內、箭頭與底圖的顏色在對的地方；`importPhoto` 也寫好縮圖
- 裁切、旋轉：底圖的像素跟 `Markup.rotated` 的座標對得上（黑色的角跑到哪裡）、轉回來每個像素都一樣；
  裁切之後馬賽克的格子還是原本那麼大；`base_name` 欄位在 Android 9 的 SQLite 上存得了（舊的表補欄位在 JVM 的 `WorkStoreTest` 測）
- 之後要加：手機拍的 HEIC

## 系統畫面的測試：SystemUiTest

`app/src/androidTest/java/com/twtrubiks/snapmark/SystemUiTest.kt`，繼承 `UiTest`。`record.sh` 在錄影結束、截圖 pull 回來之後先 `pm clear` 再跑，不錄影，
輸出在 `system-ui-test.txt`、`logcat-system-ui.txt`，截圖是 `screenshots/systemui-*.png`。
只放不是 App 畫的畫面，每個 Android 版本長得不一樣，只能點一次才知道接不接得上（Android 9 接得上，其他版本還沒跑過）：

- 系統的照片挑選器：Android 9、10 是檔案畫面（找檔名，找不到就從側邊欄進「圖片」→ Pictures），Android 13 以後是照片挑選器（只有縮圖，挑第一張）。
  「用其他 App 選」：先是系統的「選擇 App」（`systemui-10-choose-app.png`），點系統的檔案畫面那一項（`CHOOSER_FILES`），再照上面選
  照片先放進相簿：Android 10 以後 App 直接寫 MediaStore，Android 9 要權限，交給 shell（`UiTest.shell`）複製再廣播掃描。
  放的是 1200×800、上紅下藍、EXIF 轉 90 度的 JPEG：選完核對作品是 800×1200、紅色在右邊
- 系統相機：`record.yml` 的模擬器開了 `-camera-back emulated`（假的鏡頭畫面）。相機 App 的權限先用 `pm grant` 給好，
  第一次打開的提示、快門、確認的按鈕每個版本不一樣，看得到哪個點哪個（`CAMERA_*` 的 selector）；
  真的接不起來就只測「拍完之後」那一段（測試自己放一張圖到那個暫存檔）
  模擬器的假鏡頭偶爾連不上（遇過一次：相機 App 自己跳「Can't connect to the camera」，前後幾次都正常）：
  看到 `CAMERA_ERROR` 就關掉、退回首頁再拍一次（`systemui-3-camera-error.png`），第二次還是連不上才算失敗
- 取消：挑選器、相機打開後按返回鍵，回到首頁，沒有多出作品，相機的暫存檔刪掉了
- 存到相簿：Android 9 先跳系統問權限的對話框（`UiTest.allowStorageIfAsked` 按允許）；存完用 shell 查 MediaStore（`UiTest.savedInGallery`），
  多出一張在 `Pictures/SnapMark/`、檔名對、跟底圖一樣大；回到作品分頁有「已存到相簿」，「繼續改」打開同一張。測完用 `_id` 刪掉
- 分享：系統的分享選單打得開（只看選單出來、`cacheDir/share/` 有那張 PNG，按返回回到畫布）
- 刪作品時一併刪除相簿的圖：存一張（自己回到作品分頁）→ 長按 → `delete_gallery` 預設不勾、寫「1 張」→ 勾 → 刪除，
  MediaStore 查不到、檔案也不在了（`systemui-12-delete-with-gallery.png`）
- `UiTest.shell` 不經過 sh：指令照空白切開，引號原封不動傳過去。SQL 裡不要有空白（寫成 `--where _id=12`、`--where _display_name='a.jpg'`）

## 畫的時候的效能：PerfTest

`app/src/androidTest/java/com/twtrubiks/snapmark/PerfTest.kt`，`record.sh` 在 `SystemUiTest` 之後跑，不錄影，輸出在 `perf-test.txt`。
已經畫了 40 條筆畫（每條 80 點）的空白畫布上，同一次、同一台模擬器上先用改之前的畫法（`AppContainer.splitCanvasLayers` 關掉：一層、每一格全部重畫），
再用分兩層的畫法，各用筆畫 4 筆；每一筆花多久（UiAutomator 拉一筆要等 App 處理完每一下，卡的話會拖很久）與 `dumpsys gfxinfo`
的總格數、卡頓的格數、各百分位的時間存成 `screenshots/perf.txt`（每畫完一筆就先存一次，被 timeout 停掉也留得下）。
**不判斷快慢**（模擬器是軟體繪圖，跟手機差很多，沒過也不會讓 job 失敗）。不同次的 CI 跑在不同的機器上，數字只在同一次裡比。
筆畫再多（60 條、每條 120 點、拉一筆 120 步）的話，改之前的畫法在模擬器上 5 分鐘還畫不完 8 筆，會被 timeout 停掉。
Android 9 模擬器上量到的：

| | 每一筆（60 步） | 每一格的中位數 | 主執行緒太慢的格數 | 送繪圖指令太慢的格數 |
| --- | --- | --- | --- | --- |
| 改之前（一層） | 約 23.5 秒 | 300 ms | 203 / 488 | 488 / 488 |
| 分兩層 | 約 12.1 秒 | 150 ms | 0 / 486 | 0 / 486 |

App 這邊（主執行緒、送繪圖指令）已經不慢了，剩下的 150 ms 是模擬器用軟體（swiftshader）合成整個畫面，手機的 GPU 不會這麼慢。
還沒在真的手機上量過

## release 冒煙測試

`record.sh` 在錄影之後覆蓋安裝 release 版（同一把 debug 金鑰，可以直接覆蓋），打開 App 截圖（`screenshots/release-1-launch.png`），
用 `uiautomator dump` 確認看得到首頁的分頁列（`tab_blank`），再真的畫一筆：空白 → 拉一筆（工具是預設的筆） → 存到相簿（Android 9 按允許）
→ 自己回到作品分頁，看得到「1 個標註」的縮圖（`release-2-stroke.png`、`release-3-saved.png`、`release-4-works.png`；
kotlinx.serialization 存 JSON、畫成品圖寫進 MediaStore、Coil 讀縮圖在 R8 之後都還正常）。
`logcat-release.txt` 有 `FATAL EXCEPTION` 或「縮圖讀不到」（檔案在、Coil 卻讀不出來）、看不到分頁列、沒存到、相簿裡找不到 `Pictures/SnapMark/` 的 PNG 時 job 會失敗。
R8 壓過的程式只有 release 版跑得到：之後每做一個會用到函式庫的功能（Coil 讀縮圖、畫成品圖、分享），
就在這裡真的走一次（`record.sh` 的 `tap_res <testTag>` 點元件、`bounds_of` 拿元件的範圍）。

## 錄影流程（GitHub Actions）

1. push 到目前的 branch；改到 `app/**`、`gradle/**`、`*.gradle.kts`、`gradle.properties`、`scripts/ci/**`、`record.yml` 會自動觸發。
   只改文件（`CLAUDE.md`、`README.md`、`docs/**`）不會觸發。workflow 被停用時（`disabled_manually`）雲端 session 的工具啟用不了，
   要使用者在 Actions 頁面按「Enable workflow」。要手動觸發：
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
   - `/tmp/media/system-ui-test.txt`、`logcat-system-ui.txt`：SystemUiTest 的輸出與 log（截圖是 `screenshots/systemui-*.png`）
   - `/tmp/media/perf-test.txt`、`logcat-perf.txt`、`screenshots/perf.txt`：PerfTest 的輸出、log 與量到的數字（改之前、分兩層各畫幾筆）
   - `/tmp/media/release-smoke.txt`、`logcat-release.txt`：release 冒煙測試的啟動時間與 log
   - `/tmp/media/segments.txt`：接起來的錄影段（每段最多 180 秒）
   - `/tmp/media/demo.mp4`：接起來、壓縮後的影片（給使用者看）

模擬器偶爾會自己掛掉（遇過一次：`DemoRecordingTest` 通過後 20 秒，job log 先是 `adb: device offline` 再變成
`device 'emulator-5554' not found`，後面每一段都空的、連截圖都沒有，logcat 也沒有閃退）：不是 App 的問題，
用 GitHub MCP 的 `actions_run_trigger`（`rerun_failed_jobs`）重跑，重跑就全部通過。同一個地方又掉才往程式找原因。

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。傳之前先核對 `info.txt` 的 commit 是這次的。

## README 的截圖與 GIF

`docs/images/`：都從 CI 的 `media` branch 來（Android 9 模擬器）。畫面改了要換時照這樣重做：

- 截圖：`/tmp/media/screenshots/` 的 `00-camera`（→ `home`）、`10-all-tools`（→ `tools`）、`08-tool-picker`（→ `tool-menu`）、
  `04-color-picker`（→ `colors`）、`13-mosaic`（→ `mosaic`）、`13c-crop`（→ `crop`）、`14-saved`（→ `saved`）、`01b-dark`（→ `dark`），
  `convert <來源> -resize 300x -strip -define png:compression-level=9 docs/images/<名字>.png`
- `demo.gif`：`demo.mp4` 剪幾段（首頁與深色模式、箭頭與顏色、選取、形狀／筆／文字／印章、照片打馬賽克、裁切、存到相簿 → 作品分頁 → 繼續改；
  Android 9 問權限的系統對話框剪掉），每段 `trim` 之後 `setpts=(PTS-STARTPTS)/4` 加速 4 倍、`concat`，
  再 `fps=8,scale=300:-1`、`palettegen=max_colors=128:stats_mode=diff`／`paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`。
  45 秒、約 2 MB。錄影每次長度不一樣，先用每 5 秒一張的影格對好時間再剪

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
