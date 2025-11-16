package com.example.tiny2.network.mqtt


data class MqttConfig(
    val host: String = "YOUR_MQTT_BROKER_IP_HERE",
    val port: Int = 1883,
    val useTls: Boolean = false,

    val aeId: String = "CAdmin",
    val cseId: String = "tinyiot",

    val rvi: String = "3",
    val topicPrefix: String = "/oneM2M",
    val formatSuffix: String = "/json"
) {
    val cseIdPath: String = "/$cseId"

    val subReqTopicForNotify: String = "$topicPrefix/req/+/$aeId$formatSuffix"

    val subRespTopicForAEReq: String = "$topicPrefix/resp/$aeId/$cseId$formatSuffix"

    fun reqTopicAEtoCSE(): String = "$topicPrefix/req/$aeId/$cseId$formatSuffix"

    fun respTopicAEtoCSE(): String = "$topicPrefix/resp/$aeId/$cseId$formatSuffix"
}