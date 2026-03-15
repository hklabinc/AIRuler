package com.hklab.airuler.inspection

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2

class FilmTracker(
    private var decisionThresholdNone: Int = 2,   // main_detector2: DECISION_THRESHOLD_NONE=2
    private var trajectoryWindow: Int = 5,        // main_detector2: TRAJECTORY_WINDOW=5
    private var directionThreshold: Float = 0.15f, // main_detector2: DIRECTION_THRESHOLD=0.15

    // ✅ 좌/우 판정 각도 여유(기존 ±45° → 기본 ±75°).
    //    - 각도가 커질수록 LEFT/RIGHT 영역이 넓어지고, DOWN 영역이 좁아집니다.
    //    - LEFT/RIGHT로 빼는 동작이 DOWN으로 오인식되는 케이스 완화 목적.
    private var horizontalHalfAngleDeg: Double = 75.0,

    // ✅ 빠르게 옆으로 빼는 경우를 위한 fallback 파라미터
    // - weighted angle vote 로 결론이 안 날 때, 최근 trajectory 의 실제 이동량/마지막 위치로 보완 판정
    private var displacementThresholdRatio: Float = 0.08f,
    private var displacementDominanceRatio: Float = 1.15f,
    private var relaxedEdgeThresholdScale: Float = 0.75f
) {
    private val trajectory = ArrayDeque<Pair<Int, Int>>() // (x,y)
    private var missingCount = 0
    private var directionDecided = false
    private var lastDirection: MoveDirection? = null

    private var frameW: Int = 0
    private var frameH: Int = 0
    private var centerX: Int = 0
    private var centerY: Int = 0
    private var xThresh: Float = 0f
    private var yThresh: Float = 0f

    fun updateResolution(w: Int, h: Int) {
        // ✅ (성능) Analyzer는 매 프레임 updateResolution()을 호출할 수 있으므로
        //    해상도가 바뀌지 않았다면 불필요한 연산을 생략합니다.
        if (w == frameW && h == frameH) return
        frameW = w
        frameH = h
        centerX = w / 2
        centerY = h / 2
        xThresh = w * directionThreshold
        yThresh = h * directionThreshold
    }

    fun reset() {
        trajectory.clear()
        missingCount = 0
        directionDecided = false
        lastDirection = null
    }

    fun update(point: Pair<Int, Int>?) {
        if (point != null) {
            trajectory.addLast(point)
            while (trajectory.size > 15) trajectory.removeFirst() // python deque(maxlen=15)
            missingCount = 0
            directionDecided = false
            return
        }

        if (!directionDecided) {
            missingCount++
        }

        if (missingCount >= decisionThresholdNone && !directionDecided) {
            inferDirection()
        }
    }

    /** 방향이 결정되면 1번만 꺼내고 내부 상태를 비움 */
    fun consumeDirection(): MoveDirection? {
        val d = lastDirection
        if (d != null) {
            lastDirection = null
        }
        return d
    }

    private fun inferDirection() {
        if (trajectory.isEmpty()) {
            lastDirection = MoveDirection.UNKNOWN
            directionDecided = true
            trajectory.clear()
            return
        }

        val recent = trajectory.takeLast(trajectoryWindow)
        val weights = (1..recent.size).toList() // 1..N
        var wRight = 0
        var wDown = 0
        var wLeft = 0

        // ✅ 기존(±45°)보다 좌/우 각도 영역을 넓혀, 좌/우 이동이 DOWN으로 오인식되는 케이스를 완화.
        //  - horizontalHalfAngleDeg=75°(기본)면 RIGHT 범위는 [-75°, +75°]
        //  - DOWN 범위는 (75°, 105°] 로 좁아짐
        val rightHalfAngleRad = Math.toRadians(horizontalHalfAngleDeg.coerceIn(1.0, 89.0))
        val leftBoundaryRad = Math.PI - rightHalfAngleRad // e.g. 120° when rightHalf=60°
        val xThreshD = xThresh.toDouble()
        val yThreshD = yThresh.toDouble()

        for (i in recent.indices) {
            val (x, y) = recent[i]
            val weight = weights[i]
            val dx = (x - centerX).toDouble()
            val dy = (y - centerY).toDouble()
            val angle = atan2(dy, dx) // -pi..pi

            val dir = when {
                // RIGHT: within horizontal wedge and far enough to the right
                angle >= -rightHalfAngleRad && angle <= rightHalfAngleRad && dx > xThreshD -> MoveDirection.RIGHT

                // DOWN: within remaining wedge and far enough downward
                angle > rightHalfAngleRad && angle <= leftBoundaryRad && dy > yThreshD -> MoveDirection.DOWN

                // LEFT: within left wedge and far enough to the left
                (angle > leftBoundaryRad || angle < -leftBoundaryRad) && dx < -xThreshD -> MoveDirection.LEFT

                else -> MoveDirection.UNKNOWN
            }

            when (dir) {
                MoveDirection.RIGHT -> wRight += weight
                MoveDirection.DOWN -> wDown += weight
                MoveDirection.LEFT -> wLeft += weight
                else -> {}
            }
        }

        lastDirection = when {
            wRight == 0 && wDown == 0 && wLeft == 0 -> MoveDirection.UNKNOWN
            wRight >= wDown && wRight >= wLeft -> MoveDirection.RIGHT
            wLeft >= wDown && wLeft >= wRight -> MoveDirection.LEFT
            else -> MoveDirection.DOWN
        }

        // ✅ 빠른 removal fallback
        // - motion box 가 1~2프레임만 잡히고 곧바로 사라지면, 중심 기준 vote 만으로는 UNKNOWN 이 될 수 있습니다.
        // - 이 경우 최근 궤적의 실제 Δx/Δy 또는 마지막 위치를 이용해 보수적으로 한 번 더 판정합니다.
        if (lastDirection == MoveDirection.UNKNOWN) {
            val first = recent.firstOrNull()
            val last = recent.lastOrNull()

            if (first != null && last != null) {
                val deltaX = (last.first - first.first).toDouble()
                val deltaY = (last.second - first.second).toDouble()
                val absDx = abs(deltaX)
                val absDy = abs(deltaY)

                val minDx = (frameW * displacementThresholdRatio).toDouble()
                val minDy = (frameH * displacementThresholdRatio).toDouble()

                lastDirection = when {
                    absDx >= minDx && absDx >= absDy * displacementDominanceRatio && deltaX > 0.0 -> MoveDirection.RIGHT
                    absDx >= minDx && absDx >= absDy * displacementDominanceRatio && deltaX < 0.0 -> MoveDirection.LEFT
                    absDy >= minDy && absDy > absDx * displacementDominanceRatio && deltaY > 0.0 -> MoveDirection.DOWN
                    else -> MoveDirection.UNKNOWN
                }

                if (lastDirection == MoveDirection.UNKNOWN) {
                    val relaxedXThresh = xThresh * relaxedEdgeThresholdScale
                    val relaxedYThresh = yThresh * relaxedEdgeThresholdScale
                    val dxLast = last.first - centerX
                    val dyLast = last.second - centerY

                    lastDirection = when {
                        dxLast >= relaxedXThresh -> MoveDirection.RIGHT
                        dxLast <= -relaxedXThresh -> MoveDirection.LEFT
                        dyLast >= relaxedYThresh -> MoveDirection.DOWN
                        else -> MoveDirection.UNKNOWN
                    }
                }
            }
        }

        directionDecided = true
        trajectory.clear()
    }
}

// 편의 확장
private fun <T> ArrayDeque<T>.takeLast(n: Int): List<T> {
    if (n <= 0) return emptyList()
    if (size <= n) return this.toList()
    val skip = size - n
    return this.drop(skip)
}
