package com.hklab.airuler.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.hklab.airuler.GlobalParams
import com.hklab.airuler.tflite.TfliteModelLoader
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class YoloDetection(
    val rect: RectF,      // 0~1 normalized
    val score: Float,
    val label: String = "obj",
    val classId: Int = -1,
    val color: Int = Color.GREEN   // 기본은 초록
)

/**
 * YOLO TFLite 추론기 (CPU only, XNNPACK, multi-threads)
 *
 * ✅ (2026-02)
 * - 모델이 film/good/bad(3 classes)까지 함께 출력할 수 있어, 다양한 export output 형식을 지원합니다.
 *
 * 지원하는 대표 output tensor 형식:
 * 1) [x,y,w,h,conf,clsId]                    (numAttrs=6)
 * 2) [x,y,w,h, c0..c{nc-1}]                  (numAttrs=4+nc)  (YOLOv8/YOLO11 스타일)
 * 3) [x,y,w,h,obj, c0..c{nc-1}]              (numAttrs=5+nc)  (YOLOv5 스타일)
 * 4) [x,y,w,h,conf]                          (numAttrs=5)     (구형/단일 클래스 호환)
 */
class YoloDetector(
    context: Context,
    modelAssetName: String = "best_int8.tflite",
    private val numThreads: Int = 4,
    private val classLabels: Array<String>? = null
) {

    private val TAG = "YoloDetector"
    private val tfliteLock = Any()
    @Volatile private var closed = false

    private val interpreter: Interpreter

    // ------------------------------
    // (성능) Reusable buffers
    // ------------------------------
    private var inputBufferReuse: ByteBuffer? = null
    private var pixelReuse: IntArray = IntArray(0)
    private var outputReuse: Array<Array<FloatArray>>? = null

    // 실제 TFLite 입력 텐서 크기 (H,W)
    private var inputHeight: Int = 0
    private var inputWidth: Int = 0

    // 출력 텐서 정보
    private var numAttrs: Int = 0
    private var numBoxes: Int = 0
    private var attrsOnFirstDim: Boolean = true   // true: [1, attrs, boxes], false: [1, boxes, attrs]

    private enum class OutputFormat {
        XYWH_CONF,                 // [x,y,w,h,conf] (no class)
        XYWH_CONF_CLS_ID,          // [x,y,w,h,conf,clsId]
        XYWH_CLASS_SCORES,         // [x,y,w,h, c0..]
        XYWH_OBJ_CLASS_SCORES      // [x,y,w,h,obj, c0..]
    }

    private var outputFormat: OutputFormat = OutputFormat.XYWH_CONF
    private var numClasses: Int = 0
    private var classScoreStart: Int = -1
    private var objIndex: Int = -1
    private var clsIndex: Int = -1

    // COCO 80 클래스 라벨(기본값) — classLabels가 주어지면 그쪽이 우선합니다.
    private val cocoLabels = arrayOf(
        "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light",
        "fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow",
        "elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase",
        "frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard",
        "surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl",
        "banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake",
        "chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote",
        "keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock",
        "vase","scissors","teddy bear","hair drier","toothbrush"
    )

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
        }

        val model = TfliteModelLoader.load(context, modelAssetName)
        interpreter = Interpreter(model, options)
        Log.i(TAG, "YOLO interpreter created (CPU only, threads=$numThreads)")

        // ---- Input shape (ex: [1, 288, 512, 3] or [1, 512, 512, 3])
        val inShape = interpreter.getInputTensor(0).shape()
        if (inShape.size == 4) {
            inputHeight = inShape[1]
            inputWidth = inShape[2]
            Log.i(TAG, "YOLO input tensor shape=${inShape.contentToString()} (H=$inputHeight, W=$inputWidth)")
        } else {
            Log.w(TAG, "Unexpected YOLO input shape: ${inShape.contentToString()}")
        }

        // ---- Output shape
        // outShape: [1, dim1, dim2]
        val outShape = interpreter.getOutputTensor(0).shape()
        if (outShape.size == 3) {
            val d1 = outShape[1]
            val d2 = outShape[2]
            val maxAttrGuess = 128 // attr 차원은 보통 (4~(5+nc)) 이므로 작은 쪽을 attr로 봄

            when {
                d1 <= maxAttrGuess && d2 > d1 -> {
                    // [1, attrs, boxes]
                    numAttrs = d1
                    numBoxes = d2
                    attrsOnFirstDim = true
                }
                d2 <= maxAttrGuess && d1 > d2 -> {
                    // [1, boxes, attrs]
                    numAttrs = d2
                    numBoxes = d1
                    attrsOnFirstDim = false
                }
                else -> {
                    Log.w(TAG, "Unexpected YOLO output shape: ${outShape.contentToString()}")
                }
            }
        } else {
            Log.w(TAG, "Unexpected YOLO output rank: ${outShape.contentToString()}")
        }

        // ---- Decide output decode format
        // 가능한 경우 classLabels.size로 nc를 확정해서 잘못된 해석을 방지합니다.
        val hintNc = classLabels?.size

        outputFormat = when {
            numAttrs <= 0 -> OutputFormat.XYWH_CONF

            // 5: YOLOv8/YOLO11 단일 클래스(4+nc) 또는 구형 [xywh,conf]
            numAttrs == 5 -> {
                numClasses = 1
                classScoreStart = 4
                OutputFormat.XYWH_CLASS_SCORES
            }

            // 6: [xywh,conf,clsId] 또는 [xywh,obj,score] 류
            numAttrs == 6 -> {
                clsIndex = 5
                objIndex = 4
                OutputFormat.XYWH_CONF_CLS_ID
            }

            // 7 이상: 대부분 multi-class 확률 벡터
            numAttrs >= 7 -> {
                when {
                    hintNc != null && (numAttrs - 4) == hintNc -> {
                        numClasses = hintNc
                        classScoreStart = 4
                        OutputFormat.XYWH_CLASS_SCORES
                    }
                    hintNc != null && (numAttrs - 5) == hintNc -> {
                        numClasses = hintNc
                        objIndex = 4
                        classScoreStart = 5
                        OutputFormat.XYWH_OBJ_CLASS_SCORES
                    }
                    else -> {
                        // 힌트가 없으면 YOLOv8/YOLO11 스타일(4+nc)을 우선
                        val ncNoObj = numAttrs - 4
                        val ncWithObj = numAttrs - 5
                        if (ncNoObj >= 1) {
                            numClasses = ncNoObj
                            classScoreStart = 4
                            OutputFormat.XYWH_CLASS_SCORES
                        } else if (ncWithObj >= 1) {
                            numClasses = ncWithObj
                            objIndex = 4
                            classScoreStart = 5
                            OutputFormat.XYWH_OBJ_CLASS_SCORES
                        } else {
                            OutputFormat.XYWH_CONF
                        }
                    }
                }
            }

            else -> OutputFormat.XYWH_CONF
        }

        Log.i(
            TAG,
            "YOLO output tensor shape=${outShape.contentToString()} decoded as format=$outputFormat (attrs=$numAttrs, boxes=$numBoxes, nc=$numClasses, attrsOnFirstDim=$attrsOnFirstDim)"
        )
    }

    private fun labelOf(classId: Int): String {
        classLabels?.let { labels ->
            if (classId in labels.indices) return labels[classId]
        }
        return if (classId in cocoLabels.indices) {
            cocoLabels[classId]
        } else {
            "id=$classId"
        }
    }

    private fun ensureInputBuffer(): ByteBuffer {
        val needBytes = 1 * inputHeight * inputWidth * 3 * 4
        val existing = inputBufferReuse
        if (existing == null || existing.capacity() != needBytes) {
            inputBufferReuse = ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
        }
        return inputBufferReuse!!.apply { clear() }
    }

    private fun ensurePixelBuffer(): IntArray {
        val need = inputWidth * inputHeight
        if (pixelReuse.size != need) pixelReuse = IntArray(need)
        return pixelReuse
    }

    private fun ensureOutputBuffer(): Array<Array<FloatArray>> {
        val existing = outputReuse
        if (existing != null) return existing

        val out: Array<Array<FloatArray>> = if (attrsOnFirstDim) {
            Array(1) { Array(numAttrs) { FloatArray(numBoxes) } }
        } else {
            Array(1) { Array(numBoxes) { FloatArray(numAttrs) } }
        }
        outputReuse = out
        return out
    }

    /** 모델 입력 크기(Width, Height). */
    fun inputSizeWh(): Pair<Int, Int> = Pair(inputWidth, inputHeight)

    fun detect(
        bitmap: Bitmap,
        scoreThresh: Float = GlobalParams.SCORE_THRESH,
        iouThresh: Float = GlobalParams.IOU_THRESH
    ): List<YoloDetection> = synchronized(tfliteLock) {
        if (closed) return@synchronized emptyList()
        if (numBoxes <= 0 || numAttrs <= 0) return@synchronized emptyList()

        // 1) 입력 준비 (1, H, W, 3), float32, [0,1]
        val inputBuffer = ensureInputBuffer()

        // 모델 입력 크기에 맞춰 리사이즈
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }

        val pixels = ensurePixelBuffer()
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        var idx = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val p = pixels[idx++]
                val r = (p shr 16 and 0xFF) / 255f
                val g = (p shr 8 and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }
        inputBuffer.rewind()

        // 2) 출력 버퍼 준비 (재사용)
        val output: Array<Array<FloatArray>> = ensureOutputBuffer()

        // 3) 추론
        interpreter.run(inputBuffer, output)

        fun getAttr(boxIdx: Int, attrIdx: Int): Float {
            return if (attrsOnFirstDim) {
                output[0][attrIdx][boxIdx]
            } else {
                output[0][boxIdx][attrIdx]
            }
        }

        // 4) 후처리
        val candidates = mutableListOf<YoloDetection>()

        for (i in 0 until numBoxes) {
            val x = getAttr(i, 0)
            val y = getAttr(i, 1)
            val w = getAttr(i, 2)
            val h = getAttr(i, 3)

            var score = 0f
            var classId = -1
            var label = "obj"

            when (outputFormat) {
                OutputFormat.XYWH_CONF -> {
                    score = if (numAttrs > 4) getAttr(i, 4) else 1f
                    if (score < scoreThresh) continue
                    classId = -1
                    label = "obj"
                }

                OutputFormat.XYWH_CONF_CLS_ID -> {
                    val conf = getAttr(i, 4)
                    if (conf < scoreThresh) continue

                    // clsId는 float로 들어오므로 int로 캐스팅
                    val cls = getAttr(i, clsIndex.takeIf { it >= 0 } ?: 5).toInt()
                    score = conf
                    classId = cls
                    label = labelOf(cls)
                }

                OutputFormat.XYWH_CLASS_SCORES -> {
                    var best = -1f
                    var bestId = 0
                    val start = classScoreStart
                    val nc = numClasses
                    for (c in 0 until nc) {
                        val s = getAttr(i, start + c)
                        if (s > best) {
                            best = s
                            bestId = c
                        }
                    }
                    score = best
                    if (score < scoreThresh) continue
                    classId = bestId
                    label = labelOf(bestId)
                }

                OutputFormat.XYWH_OBJ_CLASS_SCORES -> {
                    val obj = getAttr(i, objIndex.takeIf { it >= 0 } ?: 4)
                    var best = -1f
                    var bestId = 0
                    val start = classScoreStart
                    val nc = numClasses
                    for (c in 0 until nc) {
                        val s = getAttr(i, start + c)
                        if (s > best) {
                            best = s
                            bestId = c
                        }
                    }
                    score = obj * best
                    if (score < scoreThresh) continue
                    classId = bestId
                    label = labelOf(bestId)
                }
            }

            // x,y,w,h 는 0~1 normalized center 형식이라는 가정
            val x1 = max(0f, x - w / 2f)
            val y1 = max(0f, y - h / 2f)
            val x2 = min(1f, x + w / 2f)
            val y2 = min(1f, y + h / 2f)

            val rect = RectF(x1, y1, x2, y2)
            candidates += YoloDetection(rect, score, label, classId)
        }

        return@synchronized nonMaxSuppressionPerClass(candidates, iouThresh)
    }

    /**
     * TFLite Interpreter 해제.
     */
    fun close() {
        synchronized(tfliteLock) {
            if (closed) return@synchronized
            closed = true
            try {
                interpreter.close()
            } catch (e: Exception) {
                Log.w(TAG, "interpreter.close() failed", e)
            }

            inputBufferReuse = null
            outputReuse = null
            pixelReuse = IntArray(0)
        }
    }

    /**
     * ✅ multi-class(필름 + good/bad)에서 클래스 간 NMS 간섭을 막기 위해,
     *    classId 별로 NMS를 수행 후 합칩니다.
     */
    private fun nonMaxSuppressionPerClass(
        detections: List<YoloDetection>,
        iouThresh: Float
    ): List<YoloDetection> {
        if (detections.isEmpty()) return emptyList()

        val byCls = detections.groupBy { it.classId }
        val out = mutableListOf<YoloDetection>()

        for ((_, list) in byCls) {
            out += nonMaxSuppression(list, iouThresh)
        }

        // 점수 내림차순으로 합치기
        return out.sortedByDescending { it.score }
    }

    private fun nonMaxSuppression(
        detections: List<YoloDetection>,
        iouThresh: Float
    ): List<YoloDetection> {
        val out = mutableListOf<YoloDetection>()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            out += best

            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                if (iou(best.rect, other.rect) > iouThresh) {
                    it.remove()
                }
            }
        }
        return out
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return interArea / unionArea
    }
}
