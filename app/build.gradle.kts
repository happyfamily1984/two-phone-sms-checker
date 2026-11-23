import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Play 스토어 업로드 키: 저장소에 넣지 않는 keystore.properties 파일에서 읽는다.
// (파일이 없으면 직접 설치용 공용 키로 서명한다)
val uploadProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.happyfamily.twophone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.happyfamily.twophone"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        // 직접 설치(APK)용: 어디서 빌드해도 같은 서명이 되도록 저장소에 든 키
        create("shared") {
            storeFile = rootProject.file("signing/app.keystore")
            storePassword = "twophone"
            keyAlias = "twophone"
            keyPassword = "twophone"
        }
        if (uploadProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(uploadProps.getProperty("storeFile"))
                storePassword = uploadProps.getProperty("storePassword")
                keyAlias = uploadProps.getProperty("keyAlias")
                keyPassword = uploadProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("shared")
        }
        // 직접 설치용 APK (공용 키로 서명 → 기존 설치본 위에 업데이트 가능)
        create("sideload") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("shared")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
