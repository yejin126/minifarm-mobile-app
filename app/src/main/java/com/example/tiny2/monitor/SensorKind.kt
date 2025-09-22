package com.example.tiny2.monitor

enum class SensorKind(
    val key: String,
    val defaultMs: Long,
    val boostedMs: Long,
    val boostDurationMs: Long
) {
    Temperature("Temperature", 30_000, 7_000, 20_000),
    Humid      ("Humid",       30_000, 7_000, 20_000),
    CO2        ("CO2",        60_000, 15_000, 30_000),
    Soil       ("Soil",       120_000, 45_000, 60_000);

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k }
    }
}