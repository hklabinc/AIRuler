package com.hklab.airuler.samsungcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlin.math.max

/**
 * 삼성 카메라로 촬영된 JPEG(Uri)을 “프리뷰용(저해상도)” Bitmap으로 로드합니다.
 *
 * ✅ fixed5에서 사용하던 “엣지 에너지 기반 회전/반전(autoForceLandscape)”은 제거했습니다.
 *
 * ✅ 2026-01-08 정책 변경(사용자 요청)
 * - 삼성 카메라 원본을 가져올 때 EXIF orientation 기반 회전은 사용하지 않습니다.
 * - 대신 "세로(h>w)면 항상 90° CCW 회전"만 적용합니다. (Python cv2.rotate(..., ROTATE_90_COUNTERCLOCKWISE)와 동일)
 *
 * 정책
 * 1) inSampleSize로 다운샘플 디코드
 * 2) (옵션) forceLandscape=true인데 결과가 portrait이면 90° CCW로 1회 회전
 * 3) 최종적으로 targetWidth x targetHeight 로 스케일(프리뷰용)
 */
object SamsungPhotoPreviewLoader {

    data class Result(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int
    )

    fun loadPreviewBitmap(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        forceLandscape: Boolean = true
    ): Result? {
        // 1) bounds 읽기
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, bounds)
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // 목표 프리뷰 크기
        val reqW = max(1, targetWidth)
        val reqH = max(1, targetHeight)

        // 소스가 세로인데(forceLandscape) 결과는 가로가 되길 기대하므로,
        // 샘플 계산에서 reqW/reqH를 swap하면 더 적절한 inSample이 나오는 경우가 많습니다.
        val (decodeReqW, decodeReqH) = if (forceLandscape && srcH > srcW) {
            reqH to reqW
        } else {
            reqW to reqH
        }

        val inSample = calculateInSampleSize(srcW, srcH, decodeReqW, decodeReqH)

        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = inSample
            // ✅ 프리뷰(1280x720 수준)에서는 품질 우선(“화소 깨짐” 체감 완화)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inDither = false
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, opts)
        } ?: return null

        // 2) 프리뷰는 항상 가로(landscape)로 표시(선택)
        //    - EXIF orientation은 무시하고, "세로면 90° CCW"만 적용합니다.
        val landscape = if (!forceLandscape) decoded else ensureLandscape(decoded)

        // 4) 최종 크기 맞춤
        val out = if (landscape.width != reqW || landscape.height != reqH) {
            val scaled = Bitmap.createScaledBitmap(landscape, reqW, reqH, true)
            if (scaled !== landscape) runCatching { landscape.recycle() }
            scaled
        } else {
            landscape
        }

        return Result(out, srcW, srcH)
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        val rw = max(1, reqW)
        val rh = max(1, reqH)

        val wRatio = srcW / rw
        val hRatio = srcH / rh

        // ✅ target 이상 크기로 디코딩되게 하려면 floor(min(ratio))가 유리
        val sample = minOf(wRatio, hRatio)
        return max(1, sample)
    }

    private fun ensureLandscape(bmp: Bitmap): Bitmap {
        if (bmp.width >= bmp.height) return bmp
        // ✅ 엣지 휴리스틱 제거: portrait이면 CCW(-90)로 1회 회전
        return rotateBitmap(bmp, -90f, recycleSrc = true)
    }

    private fun rotateBitmap(bmp: Bitmap, degrees: Float, recycleSrc: Boolean): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (recycleSrc && out !== bmp) runCatching { bmp.recycle() }
        return out
    }
}
