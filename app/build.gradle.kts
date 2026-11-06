import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 畫面預覽（Robolectric，見 CLAUDE.md 的「畫面預覽」）是選用的：./gradlew testDebugUnitTest -Ppreview --tests '*PreviewTest'
// Robolectric 畫圖用的 jar 有 150 MB，雲端 VM 的共用 IP 下載常被 Maven Central 限流很久，
// 不放進每次都要跑的 build：沒有 -Ppreview 時，build、JVM 測試、CI 都碰不到 Robolectric
val preview = providers.gradleProperty("preview").isPresent

android {
    namespace = "com.twtrubiks.snapmark"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.twtrubiks.snapmark"
        // Android 9；java.time 從 API 26 就內建，不需要 core library desugaring
        minSdk = 28
        targetSdk = 36
        // 發版時由 release.yml 帶進來：versionCode 是 2026 年起算的分鐘數，只會越來越大，新版才裝得上去
        versionCode = providers.environmentVariable("SNAPMARK_VERSION_CODE").map(String::toInt).getOrElse(1)
        versionName = providers.environmentVariable("SNAPMARK_VERSION_NAME").getOrElse("0.1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 給別人下載的 APK（GitHub Release）一律用同一把金鑰簽：換了金鑰，手機上的舊版就不能直接更新，
    // 只能移除重裝，作品會一起刪掉。金鑰放在 GitHub Secrets，由 release.yml 解開後用環境變數帶進來
    val releaseKeystore = providers.environmentVariable("SNAPMARK_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore.get())
            storeType = "pkcs12"
            storePassword = providers.environmentVariable("SNAPMARK_KEYSTORE_PASSWORD").get()
            keyAlias = "snapmark"
            keyPassword = providers.environmentVariable("SNAPMARK_KEYSTORE_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // R8 刪掉沒用到的程式碼並最佳化，Compose 在 release 版會比 debug 版順很多
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 沒有發版金鑰（自己 build、錄影的 CI）就用這台機器的 debug 金鑰簽，能直接安裝，也能覆蓋同一台機器打包的 debug 版
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 模擬器上的測試與畫面預覽共用的測試資料（例如示範馬賽克用的「照片」）
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    if (preview) {
        // 預覽的測試放在 src/preview/java，要讀得到 App 的資源
        sourceSets.getByName("test").kotlin.directories.add("src/preview/java")
        sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/java")
        testOptions.unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// 畫面預覽的 PNG 放這裡（給 Claude 用 Read 看）
val previewDir = layout.buildDirectory.dir("previews").get().asFile.path

tasks.withType<Test>().configureEach {
    // CI 只留得下 gradle-build.txt，測試失敗要直接印出期望值與實際值
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
    systemProperty("snapmark.previews", previewDir)
    // captureToImage 走硬體繪圖，畫出來跟手機上一樣
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    if (preview) {
        testImplementation(libs.robolectric)
        testImplementation(platform(libs.androidx.compose.bom))
        testImplementation(libs.androidx.compose.ui.test.junit4)
        // createAndroidComposeRule 需要的空白 Activity
        debugImplementation(libs.androidx.compose.ui.test.manifest)
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.exifinterface)
}
