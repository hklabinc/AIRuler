plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.hklab.airuler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hklab.airuler"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // OpenCV AAR 직접 넣는 경우를 쓸 일 있으면 여기에 flatDir 추가
    // repositories { flatDir { dirs("libs") } }
    // dependencies { implementation(files("libs/opencv-4.12.0.aar")) }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
            "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
            "META-INF/ASL2.0"
        )
    }
}

dependencies {
    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OpenCV 4.12
    implementation("org.opencv:opencv:4.12.0")

    // YOLO(TFLite)용 - 기본 Interpreter + GPU delegate
    implementation("org.tensorflow:tensorflow-lite:2.12.0")
    //implementation("org.tensorflow:tensorflow-lite-gpu:2.12.0")
    // 필요하면 Support / Task library도 추가 가능
    // implementation("org.tensorflow:tensorflow-lite-support:0.4.3")

    // 여기 한 줄이 GPU delegate Java API (GpuDelegateFactory.Options 등)를 제공
    //runtimeOnly("org.tensorflow:tensorflow-lite-gpu-api:2.12.0")
    // (선택) Task Library + GPU delegate plugin 까지 쓸 생각이면 이거도 나중에 추가 가능
    //implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")

    // EXIF 회전 처리를 위해 의존성 추가
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

}
