plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.happyfamily.twophone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.happyfamily.twophone"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // 개인용 앱: 어디서 빌드해도 같은 서명이 되도록 저장소에 든 키를 쓴다.
        // (서명이 달라지면 기존 앱 위에 업데이트 설치가 안 된다)
        create("shared") {
            storeFile = rootProject.file("signing/app.keystore")
            storePassword = "twophone"
            keyAlias = "twophone"
            keyPassword = "twophone"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
