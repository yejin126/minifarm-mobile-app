package com.example.tiny2.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Cin(val con: String? = null)

@Serializable
data class CinEnvelope(@SerialName("m2m:cin") val cin: Cin? = null)

@Serializable
data class UriListResponse(
    @SerialName("m2m:uril") val uril: List<String> = emptyList()
)

@Serializable
data class Ae(
    val rn: String? = null,
    val lbl: List<String> = emptyList()
)

@Serializable
data class AeResponse(@SerialName("m2m:ae") val ae: Ae? = null)