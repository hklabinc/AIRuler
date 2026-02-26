package com.hklab.airuler.net

/**
 * 공통 업로드 결과 타입.
 *
 * - ok      : 업로드 성공 여부
 * - message : 사용자에게 표시 가능한 메시지(성공/실패/오류 포함)
 * - httpCode: HTTP 응답 코드(네트워크 오류 등으로 없을 수 있음)
 */
data class UploadResult(
    val ok: Boolean,
    val message: String,
    val httpCode: Int? = null
)
