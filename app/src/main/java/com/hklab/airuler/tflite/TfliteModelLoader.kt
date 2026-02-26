package com.hklab.airuler.tflite

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite 모델 로딩(파일 경로 or asset)을 공통으로 처리하기 위한 유틸.
 *
 * 여러 곳에서 동일한 로딩 코드가 중복되던 것을 한 곳으로 모았습니다.
 */
object TfliteModelLoader {

    /**
     * @param pathOrAsset 1) 실제 파일 경로 또는 2) assets 내 파일명
     */
    fun load(context: Context, pathOrAsset: String): ByteBuffer {
        val f = File(pathOrAsset)
        if (f.exists() && f.isFile) {
            FileInputStream(f).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
            }
        }

        // asset mmap
        context.assets.openFd(pathOrAsset).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
