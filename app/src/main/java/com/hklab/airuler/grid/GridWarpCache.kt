package com.hklab.airuler.grid

import android.content.Context
import com.hklab.airuler.model.ModelFileStore

/**
 * Grid.json -> GridWarpPiecewiseAffine 캐시
 *
 * - 측정/분석 호출 때마다 매번 triangulation을 다시하면 비용이 크므로
 *   internal/models/Grid.json 의 lastModified 기준으로 캐시합니다.
 */
object GridWarpCache {

    @Volatile private var cachedWarp: GridWarpPiecewiseAffine? = null
    @Volatile private var cachedMtime: Long = 0L
    @Volatile private var cachedPath: String = ""

    /**
     * Grid.json이 존재하지 않으면 null
     * 파싱/warp 구축에 실패하면 null
     */
    fun getOrLoad(context: Context): GridWarpPiecewiseAffine? {
        val file = ModelFileStore.downloadedModelJsonFile(context, "Grid")
        if (!file.exists()) return null

        val path = file.absolutePath
        val mtime = file.lastModified()

        val w0 = cachedWarp
        if (w0 != null && cachedPath == path && cachedMtime == mtime) {
            return w0
        }

        synchronized(this) {
            val w1 = cachedWarp
            if (w1 != null && cachedPath == path && cachedMtime == mtime) {
                return w1
            }

            val loaded = runCatching {
                GridWarpPiecewiseAffine.fromGridJsonFile(file)
            }.getOrNull() ?: return null

            cachedWarp = loaded
            cachedPath = path
            cachedMtime = mtime
            return loaded
        }
    }

    fun clear() {
        synchronized(this) {
            cachedWarp = null
            cachedMtime = 0L
            cachedPath = ""
        }
    }
}
