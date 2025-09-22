package com.example.tiny2.network

data class ActuationLatency(
    val ok: Boolean,
    val totalMs: Long,          // 전체 경과 시간
    val httpMs: Long,           // POST 왕복 시간
    val finalValue: String?,    // 마지막으로 읽힌 값 (예: "ON"/"OFF"/"7")
    val observedMs: Long        // 관측(변화 감지)까지 걸린 시간 (total - http)
)