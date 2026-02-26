package com.hklab.airuler.samsungcapture

object SamsungCaptureContract {
    const val SAMSUNG_CAMERA_PACKAGE = "com.sec.android.app.camera"

    // ✅ Expert RAW (200MP 촬영용)
    // - 기기/OS에 따라 패키지명이 다를 수 있으나, HkCamera 기준으로 사용합니다.
    const val EXPERT_RAW_PACKAGE = "com.samsung.android.app.galaxyraw"

    // MediaStore RELATIVE_PATH에 들어가는 DCIM 하위 폴더명
    const val EXPERT_RAW_DCIM_FOLDER = "Expert RAW"

    // ReturnWatcherService가 어떤 소스(삼성/Expert RAW)를 감지할지 지정
    const val EXTRA_CAPTURE_SOURCE = "com.hklab.airuler.extra.CAPTURE_SOURCE"
    const val SOURCE_SAMSUNG_CAMERA = "samsung_camera"
    const val SOURCE_EXPERT_RAW = "expert_raw"

    // ReturnWatcherService 시작 시 넘기는 launchAt 시간(ms)
    const val EXTRA_LAUNCH_AT = "com.hklab.airuler.extra.LAUNCH_AT_MS"

    // MainActivity로 전달할 "촬영된 사진 MediaStore Uri"
    const val EXTRA_CAPTURED_URI = "com.hklab.airuler.extra.CAPTURED_URI"
}
