package com.twtrubiks.snapmark

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * 首頁的入口：[takePhoto] 用系統的相機 App 拍一張、[pickPhoto] 用系統的照片挑選器選一張、
 * [pickFromApp] 自己挑要用哪個 App 選（相簿 App、Google 相簿、檔案管理…）。
 */
class PhotoSources(val takePhoto: () -> Unit, val pickPhoto: () -> Unit, val pickFromApp: () -> Unit)

/**
 * 接系統的相機與照片挑選器，都不需要任何權限（見 CLAUDE.md「相機」「照片」）：
 * - 相機：照片先寫到 `cacheDir/camera/`，用 FileProvider 把網址交給相機 App。**不宣告 CAMERA 權限**：
 *   宣告了又沒授權的話，反而叫不出系統相機。沒拍（取消）就把暫存檔刪掉；手機上沒有相機 App 時提示
 * - 照片：PickVisualMedia（只選圖片）。Android 9、10 沒有照片挑選器，會自動改開系統的檔案畫面
 * - 用其他 App 選：`ACTION_GET_CONTENT`（只要圖片）包在系統的「選擇 App」裡（[GetImageFromApp]），每次都問用哪一個。
 *   一樣不需要權限：選到的那一張，讀取權限跟著回傳的網址給我們
 *
 * 拿到圖就交給 [onImage]：[ImageDecoder.Source] 是要讀的圖，讀完（不管成不成功）呼叫 `cleanUp`。
 * 拍照、選照片的期間 App 可能被系統收掉，回來時結果一樣會送到這裡（暫存檔的位置用 rememberSaveable 記著）。
 */
@Composable
fun rememberPhotoSources(onImage: (source: ImageDecoder.Source, cleanUp: () -> Unit) -> Unit): PhotoSources {
    val context = LocalContext.current
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ ->
        val file = cameraPath?.let(::File) ?: return@rememberLauncherForActivityResult
        cameraPath = null
        // 不看回傳的成功與否：有的相機 App 拍好了也回「取消」。檔案有內容就是拍到了
        if (file.length() > 0) {
            onImage(ImageDecoder.createSource(file)) { file.delete() }
        } else {
            file.delete()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImage(ImageDecoder.createSource(context.contentResolver, uri)) {}
    }
    val otherApp = rememberLauncherForActivityResult(GetImageFromApp("用哪個 App 選照片？")) { uri ->
        if (uri != null) onImage(ImageDecoder.createSource(context.contentResolver, uri)) {}
    }

    return remember(context, camera, picker, otherApp) {
        PhotoSources(
            takePhoto = {
                val file = newCameraFile(context)
                try {
                    cameraPath = file.path
                    camera.launch(FileProvider.getUriForFile(context, context.fileProviderAuthority, file))
                } catch (_: ActivityNotFoundException) {
                    cameraPath = null
                    file.delete()
                    Toast.makeText(context, "找不到相機 App", Toast.LENGTH_SHORT).show()
                }
            },
            pickPhoto = {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "找不到選照片的畫面", Toast.LENGTH_SHORT).show()
                }
            },
            pickFromApp = {
                try {
                    otherApp.launch("image/*")
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "找不到可以選照片的 App", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/**
 * 選一張圖，但先讓使用者挑用哪個 App 選：不包 chooser 的話，大部分手機會直接開系統的檔案畫面，
 * 想用的相簿 App 要從側邊欄找。回傳的是那個 App 給的網址（不看 RESULT_OK 以外的）。
 */
private class GetImageFromApp(private val title: String) : ActivityResultContracts.GetContent() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent.createChooser(super.createIntent(context, input), title)
}

/** manifest 裡 FileProvider 的 authorities。 */
val Context.fileProviderAuthority get() = "$packageName.files"

/** 相機拍的照片先放這裡（`res/xml/file_paths.xml` 的 `camera`），讀進作品之後就刪掉。 */
fun cameraDir(context: Context) = File(context.cacheDir, "camera")

// 一次只拍一張：上次沒清掉的（例如讀到一半 App 被關掉）先刪掉
private fun newCameraFile(context: Context): File {
    val dir = cameraDir(context)
    dir.deleteRecursively()
    dir.mkdirs()
    return File(dir, "photo-${System.currentTimeMillis()}.jpg")
}
