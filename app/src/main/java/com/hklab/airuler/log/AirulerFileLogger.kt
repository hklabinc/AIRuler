package com.hklab.airuler.log

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.hklab.airuler.GlobalParams
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AIRuler 내부 파일 로거
 * - internal/files/logs/log_yyyyMMdd_HHmmss.txt 로 저장
 * - 앱 동작에 영향을 최소화하기 위해 단일 스레드에서 비동기 write
 *
 * 주의:
 * - GlobalParams.LOG_FILE_SAVE == true 인 경우에만 실제 파일 I/O가 발생합니다.
 * - write 는 executor thread에서만 수행하여 UI/Analyzer thread가 막히지 않게 합니다.
 */
object AirulerFileLogger {

    enum class Level { D, I, W, E }

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airuler-file-logger").apply { isDaemon = true }
    }

    // KST 고정(요구사항: 날짜/시간 기준 통일). 실패하면 시스템 타임존 사용
    private val zone: ZoneId = runCatching { ZoneId.of("Asia/Seoul") }.getOrElse { ZoneId.systemDefault() }
    private val fileTsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val lineTsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val lock = Any()

    // writer 생성 전(세션 시작 직후) 발생한 로그를 유실하지 않기 위한 버퍼
    // - 너무 커지지 않게 상한을 둡니다(앱 동작에 영향 최소화)
    private const val MAX_PENDING_LINES = 200
    private val pendingLines: ArrayDeque<String> = ArrayDeque()

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var currentFile: File? = null

    /** 현재 세션에서 사용 중인 로그 파일(없으면 null) */
    fun currentLogFile(): File? = currentFile

    /**
     * 새 로그 파일로 세션을 시작(또는 회전).
     * - ModelSelectActivity 시작 시 호출하는 것을 권장
     */
    fun startNewSession(context: Context, reason: String = "ModelSelectActivity") {
        if (!GlobalParams.LOG_FILE_SAVE) return

        val appCtx = context.applicationContext
        val logsDir = File(appCtx.filesDir, "logs")
        val now = ZonedDateTime.now(zone)
        val baseName = "log_${fileTsFmt.format(now)}.txt"
        val file = uniqueFile(File(logsDir, baseName))

        ioExecutor.execute {
            runCatching {
                logsDir.mkdirs()

                // 기존 writer 정리
                synchronized(lock) {
                    runCatching { writer?.flush() }
                    runCatching { writer?.close() }
                    writer = null
                    currentFile = null
                    pendingLines.clear()
                }

                val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
                synchronized(lock) {
                    writer = bw
                    currentFile = file
                }

                // 헤더(세션 시작)
                val androidId = runCatching {
                    Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull().orEmpty()

                writeRawLine(
                    formatLine(
                        Level.I,
                        "LOGGER",
                        "=== NEW LOG SESSION === reason=$reason file=${file.name} androidId=$androidId device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ==="
                    )
                )

                // writer 준비 전 들어온 로그가 있다면 flush
                val pendingToFlush: List<String> = synchronized(lock) { pendingLines.toList().also { pendingLines.clear() } }
                for (line in pendingToFlush) {
                    writeRawLine(line)
                }
            }.onFailure { e ->
                // 파일 로깅 실패는 앱 기능에 영향 주면 안 되므로 Logcat으로만 남기고 무시
                Log.e("AirulerFileLogger", "startNewSession failed", e)
            }
        }
    }

    /** 앱 종료/정리 시 호출(선택) */
    fun stop(reason: String = "stop") {
        if (!GlobalParams.LOG_FILE_SAVE) return
        ioExecutor.execute {
            runCatching {
                writeRawLine(formatLine(Level.I, "LOGGER", "=== LOGGER STOP === reason=$reason"))
                synchronized(lock) {
                    runCatching { writer?.flush() }
                    runCatching { writer?.close() }
                    writer = null
                    currentFile = null
                }
            }
        }
    }

    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Level.E, tag, msg, tr)

    fun log(level: Level, tag: String, msg: String, tr: Throwable? = null) {
        if (!GlobalParams.LOG_FILE_SAVE) return

        val safeMsg = msg.replace("\n", " ").trim()
        val throwableStr = tr?.let { " | ex=${it.javaClass.simpleName}:${it.message}" } ?: ""
        val rawLine = formatLine(level, tag, "$safeMsg$throwableStr")

        // writer가 아직 준비되지 않았으면 버퍼링(유실 방지)
        if (writer == null) {
            synchronized(lock) {
                if (writer == null) {
                    if (pendingLines.size >= MAX_PENDING_LINES) {
                        // 가장 오래된 로그부터 버림
                        pendingLines.removeFirstOrNull()
                    }
                    pendingLines.addLast(rawLine)
                    return
                }
            }
        }

        ioExecutor.execute {
            runCatching {
                writeRawLine(rawLine)
            }.onFailure { e ->
                // 파일 로그 실패도 앱 동작에는 영향 없어야 함
                Log.e("AirulerFileLogger", "write failed", e)
            }
        }
    }

    // ---------------- internal helpers ----------------

    private fun formatLine(level: Level, tag: String, msg: String): String {
        val ts = lineTsFmt.format(ZonedDateTime.now(zone))
        return buildString(msg.length + 64) {
            append(ts)
            append(' ')
            append('[')
            append(level.name)
            append(']')
            append(' ')
            append('[')
            append(tag)
            append(']')
            append(' ')
            append(msg)
            append('\n')
        }
    }

    private fun writeRawLine(line: String) {
        val bw = synchronized(lock) { writer } ?: return
        bw.append(line)
        // ✅ 디버깅 용도이므로 유실 최소화(큰 오버헤드가 발생할 만큼 자주 호출되지 않음)
        bw.flush()
    }

    private fun uniqueFile(f: File): File {
        if (!f.exists()) return f
        val dir = f.parentFile ?: return f
        val base = f.nameWithoutExtension
        val ext = "." + f.extension
        var i = 1
        while (true) {
            val nf = File(dir, "${base}_$i$ext")
            if (!nf.exists()) return nf
            i++
        }
    }
}
