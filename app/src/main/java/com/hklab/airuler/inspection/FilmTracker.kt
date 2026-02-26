package com.hklab.airuler.inspection

import kotlin.math.atan2

class FilmTracker(
    private var decisionThresholdNone: Int = 2,   // main_detector2: DECISION_THRESHOLD_NONE=2
    private var trajectoryWindow: Int = 5,        // main_detector2: TRAJECTORY_WINDOW=5
    private var directionThreshold: Float = 0.15f, // main_detector2: DIRECTION_THRESHOLD=0.15

    // ✅ 좌/우 판정 각도 여유(기존 ±45° → 기본 ±60°).
    //    - 각도가 커질수록 LEFT/RIGHT 영역이 넓어지고, DOWN 영역이 좁아집니다.
    //    - LEFT/RIGHT로 빼는 동작이 DOWN으로 오인식되는 케이스 완화 목적.
    private var horizontalHalfAngleDeg: Double = 60.0
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
        //  - horizontalHalfAngleDeg=60°(기본)면 RIGHT 범위는 [-60°, +60°]
        //  - DOWN 범위는 (60°, 120°] 로 좁아짐
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
