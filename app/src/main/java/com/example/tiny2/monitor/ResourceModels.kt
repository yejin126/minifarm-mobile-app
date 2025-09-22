package com.example.tiny2.monitor

import kotlinx.serialization.Serializable

@Serializable
data class CntNode(
    val ae: String,         // ex) "TinyFarm"
    val kind: String,       // ex) "Temperature" / "Humid" / "LED" ...
    val group: String,      // "Sensors" or "Actuator"
    val path: String,       // "TinyIoT/TinyFarm/Sensors/Temperature"
    val last: String? = null// 마지막 con(있으면 표시용)
)

@Serializable
data class AeTree(
    val ae: String,
    val sensors: List<CntNode>,
    val actuators: List<CntNode>
)