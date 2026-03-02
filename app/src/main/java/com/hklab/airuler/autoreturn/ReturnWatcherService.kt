package com.hklab.airuler.autoreturn

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hklab.airuler.MainActivity
import com.hklab.airuler.R
import com.hklab.airuler.samsungcapture.SamsungCaptureContract
import com.hklab.airuler.samsungcapture.SamsungCaptureStore
import com.hklab.airuler.log.AirulerFileLogger

class ReturnWatcherService : Service() {

    companion object {
        private const val CH_ID = "airuler_return_watcher"
        private const val NOTI_ID = 4401

        // (옵션) 풀스크린 알림 폴백
        private const val FS_CH_ID = "airuler_return_fullscreen"
        private const val FS_NOTI_ID = 4402

        // ✅ Expert RAW 저장 지연 대응(파일이 완전히 써질 때까지 size 안정화 대기)
        private const val EXPERT_RAW_POLL_INTERVAL_MS = 350L
        private const val EXPERT_RAW_STABLE_COUNT = 2

        // ✅ Samsung Camera(50MP)도 저장/후처리 지연이 발생할 수 있어 안정화 대기
        // - (증상) 복귀 직후 바로 Uri를 읽으면 content stream이 block 되거나
        //         OpenCV imread 단계에서 “끝나지 않는 대기”가 발생할 수 있음
        // - 안정화 기준: SIZE가 연속으로 동일(>=2회) + IS_PENDING==0
        private const val SAMSUNG_POLL_INTERVAL_MS = 250L
        private const val SAMSUNG_STABLE_COUNT = 2
        private const val SAMSUNG_POLL_MAX_MS = 25_000L
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private var launchAtMs: Long = 0L
    private var captureSource: String = SamsungCaptureContract.SOURCE_SAMSUNG_CAMERA
    private var handled = false
    private var mediaObserver: ContentObserver? = null

    // Expert RAW polling state
    private var expertRawPollRunning = false
    private var expertCandidateUri: Uri? = null
    private var expertCandidateLastSize: Long = -1L
    private var expertCandidateStableCount: Int = 0

    // Samsung Camera polling state
    private var samsungPollRunning = false
    private var samsungPollStartElapsedMs: Long = 0L
    private var samsungCandidateUri: Uri? = null
    private var samsungCandidateLastSize: Long = -1L
    private var samsungCandidateStableCount: Int = 0

    private val expertRawPollRunnable = object : Runnable {
        override fun run() {
            if (handled || !expertRawPollRunning) return

            val uri = findLatestNewExpertRawJpegUri()
            if (uri != null && isExpertRawUriStable(uri)) {
                onDetected(uri)
                return
            }

            main.postDelayed(this, EXPERT_RAW_POLL_INTERVAL_MS)
        }
    }

    private val samsungPollRunnable = object : Runnable {
        override fun run() {
            if (handled || !samsungPollRunning) return

            val latest = findLatestNewSamsungJpegUri() ?: samsungCandidateUri

            // (1) 안정화 확인
            if (latest != null && isSamsungUriStable(latest)) {
                onDetected(latest)
                return
            }

            // (2) 너무 오래 끌면(사용자 입장에서는 이미 앱으로 돌아와 “로딩 화면”만 보이는 상태)
            //     더 이상 서비스에서 대기하지 않고, 가장 최근 후보 Uri를 넘겨
            //     MeasurementPipeline의 내부 대기 로직에 맡깁니다.
            val waited = SystemClock.elapsedRealtime() - samsungPollStartElapsedMs
            if (waited > SAMSUNG_POLL_MAX_MS) {
                if (latest != null) {
                    AirulerFileLogger.w(
                        "ReturnWatcher",
                        "Samsung polling timeout (${waited}ms). Proceed without stable-check. uri=$latest"
                    )
                    onDetected(latest)
                    return
                }

                // 촬영이 취소/실패하여 후보 Uri가 끝내 잡히지 않는 경우: 서비스가 무한정 남지 않게 종료
                AirulerFileLogger.w(
                    "ReturnWatcher",
                    "Samsung polling timeout (${waited}ms) but no candidate uri. Stop watcher."
                )
                cleanupAndStop()
                return
            }

            main.postDelayed(this, SAMSUNG_POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()

        AirulerFileLogger.i("ReturnWatcher", "onCreate()")

        // 채널 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CH_ID, "AIRuler Return watcher", NotificationManager.IMPORTANCE_LOW)
            )
            nm?.createNotificationChannel(
                NotificationChannel(FS_CH_ID, "AIRuler Return (full-screen)", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        // 우선 generic 문구로 시작하고, onStartCommand에서 소스에 맞게 업데이트
        val noti = buildNotification("촬영 감지 중…")

        // Android 14+ 타입 지정까지 대응
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            noti,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launchAtMs = intent?.getLongExtra(SamsungCaptureContract.EXTRA_LAUNCH_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        captureSource = intent?.getStringExtra(SamsungCaptureContract.EXTRA_CAPTURE_SOURCE)
            ?: SamsungCaptureContract.SOURCE_SAMSUNG_CAMERA

        AirulerFileLogger.i(
            "ReturnWatcher",
            "onStartCommand() source=$captureSource launchAtMs=$launchAtMs"
        )

        // ✅ 알림 문구 업데이트
        updateNotification(
            when (captureSource) {
                SamsungCaptureContract.SOURCE_EXPERT_RAW -> "Expert RAW 촬영 감지 중…"
                else -> "삼성 카메라 촬영 감지 중…"
            }
        )

        registerMediaObserver()

        // ✅ Expert RAW는 저장 완료까지 시간이 걸릴 수 있어 polling으로 안정화 확인
        if (captureSource == SamsungCaptureContract.SOURCE_EXPERT_RAW) {
            startExpertRawPolling()
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTI_ID, buildNotification(text))
    }

    private fun registerMediaObserver() {
        if (mediaObserver != null) return

        AirulerFileLogger.d("ReturnWatcher", "registerMediaObserver()")

        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (handled) return

                when (captureSource) {
                    SamsungCaptureContract.SOURCE_EXPERT_RAW -> {
                        // Expert RAW는 저장 완료까지 시간이 걸릴 수 있어 polling으로 안정화 확인
                        // uri가 주어져도 최종 판정은 polling에서 수행
                        if (uri != null && isNewExpertRawJpegUri(uri)) {
                            expertCandidateUri = uri
                        }
                        startExpertRawPolling()
                    }

                    else -> {
                        // ✅ Samsung Camera(50MP):
                        //    여기(ReturnWatcher)에서는 “가능한 빨리 앱으로 복귀”시키고,
                        //    실제 파일 안정화/대기는 MeasurementPipeline.waitMediaReady()에서 처리합니다.
                        //    (중복 안정화 대기를 피해서 전체 지연 시간을 줄이기 위함)
                        val candidate = when {
                            uri != null && isNewSamsungJpegUri(uri) -> uri
                            else -> findLatestNewSamsungJpegUri()
                        }

                        if (candidate != null) {
                            onDetected(candidate)
                        }
                    }
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver!!
        )
    }

    // ---------------- Expert RAW ----------------
    private fun startExpertRawPolling() {
        if (expertRawPollRunning) return
        expertRawPollRunning = true
        expertCandidateUri = null
        expertCandidateLastSize = -1L
        expertCandidateStableCount = 0
        main.post(expertRawPollRunnable)
    }

    // ---------------- Samsung Camera ----------------
    private fun startSamsungPollingIfNeeded() {
        if (samsungPollRunning) return
        samsungPollRunning = true
        samsungPollStartElapsedMs = SystemClock.elapsedRealtime()
        samsungCandidateLastSize = -1L
        samsungCandidateStableCount = 0
        main.post(samsungPollRunnable)
    }

    private fun isSamsungUriStable(uri: Uri): Boolean {
        val sz = querySizeIfReady(uri) ?: return false
        if (sz <= 0L) return false

        val key = uri.toString()
        val lastKey = samsungCandidateUri?.toString()

        if (lastKey != key) {
            samsungCandidateUri = uri
            samsungCandidateLastSize = sz
            samsungCandidateStableCount = 0
            return false
        }

        if (sz == samsungCandidateLastSize) {
            samsungCandidateStableCount++
        } else {
            samsungCandidateLastSize = sz
            samsungCandidateStableCount = 0
        }

        return samsungCandidateStableCount >= SAMSUNG_STABLE_COUNT
    }

    private fun isExpertRawUriStable(uri: Uri): Boolean {
        val sz = querySizeIfReady(uri) ?: return false
        if (sz <= 0L) return false

        val key = uri.toString()
        val lastKey = expertCandidateUri?.toString()

        if (lastKey != key) {
            expertCandidateUri = uri
            expertCandidateLastSize = sz
            expertCandidateStableCount = 0
            return false
        }

        if (sz == expertCandidateLastSize) {
            expertCandidateStableCount++
        } else {
            expertCandidateLastSize = sz
            expertCandidateStableCount = 0
        }

        return expertCandidateStableCount >= EXPERT_RAW_STABLE_COUNT
    }

    private fun querySizeIfReady(itemUri: Uri): Long? {
        val proj = arrayOf(
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_PENDING
        )

        return try {
            contentResolver.query(itemUri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null

                // IS_PENDING=1이면 아직 파일 쓰는 중일 수 있어 제외
                val pendingIdx = c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                if (pendingIdx >= 0) {
                    val pending = c.getInt(pendingIdx)
                    if (pending == 1) return null
                }

                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                if (sizeIdx >= 0) c.getLong(sizeIdx) else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** “Expert RAW DCIM/Expert RAW에 저장된 신규 JPEG”만 통과 (요구사항: JPG only, DNG 미사용) */
    private fun isNewExpertRawJpegUri(itemUri: Uri): Boolean {
        if (itemUri.scheme != "content") return false

        val proj = arrayOf(
            MediaStore.Images.Media.DATE_ADDED,          // seconds
            MediaStore.Images.Media.RELATIVE_PATH,       // Q+
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, // legacy
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_PENDING           // Q+
        )

        return try {
            contentResolver.query(itemUri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return false

                val dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val threshold = (launchAtMs / 1000L) - 5
                if (dateAdded < threshold) return false

                val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: return false
                val isJpeg = mime.equals("image/jpeg", true) || mime.equals("image/jpg", true)
                if (!isJpeg) return false

                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                if (sizeIdx >= 0) {
                    val sz = c.getLong(sizeIdx)
                    if (sz <= 0L) return false
                }

                // IS_PENDING=1이면 아직 파일 쓰는 중일 수 있어 제외
                val pendingIdx = c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                if (pendingIdx >= 0) {
                    val pending = c.getInt(pendingIdx)
                    if (pending == 1) return false
                }

                val folderName = SamsungCaptureContract.EXPERT_RAW_DCIM_FOLDER
                val inExpertRaw =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        val rel = if (relIdx >= 0) c.getString(relIdx) else null
                        rel?.contains("DCIM/$folderName", ignoreCase = true) == true
                    } else {
                        val buckIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val bucket = if (buckIdx >= 0) c.getString(buckIdx) else null
                        bucket.equals(folderName, true)
                    }

                inExpertRaw
            } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    /** 테이블에서 “launchAt 이후 저장된 DCIM/Expert RAW JPEG” 최신 1개 Uri를 찾아 반환 */
    private fun findLatestNewExpertRawJpegUri(): Uri? {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_PENDING
        )

        val threshold = (launchAtMs / 1000L) - 5
        val folderName = SamsungCaptureContract.EXPERT_RAW_DCIM_FOLDER

        val sel = StringBuilder().apply {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            append(" AND (${MediaStore.Images.Media.MIME_TYPE}=? OR ${MediaStore.Images.Media.MIME_TYPE}=?)")
            append(" AND ${MediaStore.Images.Media.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                append(" AND (${MediaStore.Images.Media.IS_PENDING} IS NULL OR ${MediaStore.Images.Media.IS_PENDING}=0)")
            } else {
                append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?")
            }
        }.toString()

        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(threshold.toString(), "image/jpeg", "image/jpg", "%DCIM/$folderName%")
        } else {
            arrayOf(threshold.toString(), "image/jpeg", "image/jpg", folderName)
        }

        return try {
            contentResolver.query(
                base,
                proj,
                sel,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                ContentUris.withAppendedId(base, id)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    // ---------------- Samsung Camera (기존) ----------------
    /** “삼성 카메라 DCIM/Camera에 저장된 신규 JPEG”만 통과 */
    private fun isNewSamsungJpegUri(itemUri: Uri): Boolean {
        if (itemUri.scheme != "content") return false

        val proj = arrayOf(
            MediaStore.Images.Media.DATE_ADDED,          // seconds
            MediaStore.Images.Media.RELATIVE_PATH,       // Q+
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, // legacy
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_PENDING           // Q+
        )

        return try {
            contentResolver.query(itemUri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return false

                val dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val threshold = (launchAtMs / 1000L) - 5
                if (dateAdded < threshold) return false

                val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: return false
                val isJpeg = mime.equals("image/jpeg", true) || mime.equals("image/jpg", true)
                if (!isJpeg) return false  // ✅ DNG/HEIC 등은 무시

                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                if (sizeIdx >= 0) {
                    val sz = c.getLong(sizeIdx)
                    if (sz <= 0L) return false
                }

                // IS_PENDING=1이면 아직 파일 쓰는 중일 수 있어 제외
                val pendingIdx = c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                if (pendingIdx >= 0) {
                    val pending = c.getInt(pendingIdx)
                    if (pending == 1) return false
                }

                val inDcimCamera =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        val rel = if (relIdx >= 0) c.getString(relIdx) else null
                        rel?.contains("DCIM/Camera", ignoreCase = true) == true
                    } else {
                        val buckIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val bucket = if (buckIdx >= 0) c.getString(buckIdx) else null
                        bucket.equals("Camera", true)
                    }

                inDcimCamera
            } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    /** 테이블에서 “launchAt 이후 저장된 DCIM/Camera JPEG” 최신 1개 Uri를 찾아 반환 */
    private fun findLatestNewSamsungJpegUri(): Uri? {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_PENDING
        )

        val threshold = (launchAtMs / 1000L) - 5

        val sel = StringBuilder().apply {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            append(" AND (${MediaStore.Images.Media.MIME_TYPE}=? OR ${MediaStore.Images.Media.MIME_TYPE}=?)")
            append(" AND ${MediaStore.Images.Media.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                append(" AND (${MediaStore.Images.Media.IS_PENDING} IS NULL OR ${MediaStore.Images.Media.IS_PENDING}=0)")
            } else {
                append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?")
            }
        }.toString()

        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(threshold.toString(), "image/jpeg", "image/jpg", "%DCIM/Camera%")
        } else {
            arrayOf(threshold.toString(), "image/jpeg", "image/jpg", "Camera")
        }

        return try {
            contentResolver.query(
                base,
                proj,
                sel,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                ContentUris.withAppendedId(base, id)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    // ---------------- 공통 ----------------
    private fun onDetected(capturedUri: Uri) {
        if (handled) return
        handled = true

        val dt = kotlin.math.max(0L, System.currentTimeMillis() - launchAtMs)
        AirulerFileLogger.i(
            "ReturnWatcher",
            "onDetected() source=$captureSource dtMs=$dt uri=$capturedUri"
        )

        // polling stop
        expertRawPollRunning = false
        runCatching { main.removeCallbacks(expertRawPollRunnable) }

        samsungPollRunning = false
        runCatching { main.removeCallbacks(samsungPollRunnable) }

        // 옵저버 해제
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null

        // ✅ 정확한 Uri 저장(추정 제거)
        SamsungCaptureStore.save(this, capturedUri)

        // 접근성 ON → HOME → 앱 복귀(+Uri 전달)
        if (ReturnAccessibilityService.isEnabled(this)) {
            ReturnAccessibilityService.instance()?.returnToAiruler(capturedUri)

            // 보강: bring-to-front 재시도 (혹시 HOME/전환 타이밍 꼬일 때)
            scheduleBringToFront(capturedUri)

            main.postDelayed({ cleanupAndStop() }, 1500L)
            return
        }

        // 접근성 OFF → 그냥 bring-to-front 시도 + (옵션) 풀스크린 알림
        scheduleBringToFront(capturedUri)
        main.postDelayed({ showFullScreenReturn(capturedUri) }, 200L)
        main.postDelayed({ cleanupAndStop() }, 1700L)
    }

    private fun scheduleBringToFront(uri: Uri) {
        longArrayOf(0L, 200L, 500L, 900L, 1300L).forEach { delay ->
            main.postDelayed({ tryBringToFront(uri) }, delay)
        }
    }

    private fun tryBringToFront(uri: Uri) {
        try {
            val am = getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { t -> runCatching { t.moveToFront() } }
        } catch (_: Throwable) {}

        runCatching {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(SamsungCaptureContract.EXTRA_CAPTURED_URI, uri.toString())
            }
            startActivity(i)
        }
    }

    private fun showFullScreenReturn(uri: Uri) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(SamsungCaptureContract.EXTRA_CAPTURED_URI, uri.toString())
            },
            flags
        )

        // NOTE: 기존 구현은 풀스크린 notification을 주석 처리하고 있어 그대로 유지합니다.
        // 필요 시 아래 주석을 해제하여 fallback UX를 강화할 수 있습니다.
    }

    private fun cleanupAndStop() {
        AirulerFileLogger.i("ReturnWatcher", "cleanupAndStop()")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        AirulerFileLogger.i("ReturnWatcher", "onDestroy()")
        super.onDestroy()
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null

        expertRawPollRunning = false
        runCatching { main.removeCallbacks(expertRawPollRunnable) }

        samsungPollRunning = false
        runCatching { main.removeCallbacks(samsungPollRunnable) }
    }
}
