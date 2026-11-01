plugins {
    // 改为直接加载，避免与根项目的 AGP 版本产生冲突
    id("com.android.library")
}

android {
    compileSdk = 37
    namespace = "me.ag2s"
    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles += file("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        checkDependencies = true
        targetSdk = 37
    }
    testOptions {
        targetSdk = 37
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}