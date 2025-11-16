package com.example.tiny2.network.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class OneM2MMqtt(private val cfg: MqttConfig) {

    private val client = MqttClient.builder()
        .identifier(cfg.aeId)
        .useMqttVersion5()
        .serverHost(cfg.host)
        .serverPort(cfg.port)
        .apply { if (cfg.useTls) sslWithDefaultConfig() }
        .buildAsync()

    private var onResponseListener: ((rqi: String, rsc: Int, raw: String) -> Unit)? = null

    fun setOnResponse(listener: (rqi: String, rsc: Int, raw: String) -> Unit) {
        onResponseListener = listener
    }

    private var onNotifyListener: ((container: String?, con: String?, lbl: List<String>?) -> Unit)? = null

    fun setOnNotify(listener: (container: String?, con: String?, lbl: List<String>?) -> Unit) {
        onNotifyListener = listener
    }

    fun connect(
        onConnected: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        client.connect().whenComplete { _, err ->
            if (err != null) {
                onError?.invoke(err)
                return@whenComplete
            }

            client.subscribeWith()
                .topicFilter(cfg.subReqTopicForNotify)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { m ->
                    handleIncoming(
                        m.topic.toString(),
                        m.payload.orElse(null)?.let { bbToString(it) } ?: ""
                    )
                }
                .send()

            client.subscribeWith()
                .topicFilter(cfg.subRespTopicForAEReq)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { m ->
                    handleIncoming(
                        m.topic.toString(),
                        m.payload.orElse(null)?.let { bbToString(it) } ?: ""
                    )
                }
                .send()

            onConnected?.invoke()
        }
    }

    fun disconnect() {
        client.disconnect()
    }

    private fun bbToString(bb: ByteBuffer): String {
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun handleIncoming(topic: String, payload: String) {
        if (payload.isBlank()) return
        try {
            val json = JSONObject(payload)

            if (json.optInt("op", -1) == 5) {
                val to = json.optString("to") //
                val rqi = json.optString("rqi")
                val fr  = json.optString("fr")

                val pc = json.optJSONObject("pc")
                var con: String? = null
                var lblList: List<String>? = null

                val cinObject = when {
                    pc?.optJSONObject("m2m:cin") != null -> pc.optJSONObject("m2m:cin")
                    pc?.optJSONObject("m2m:sgn") != null ->
                        pc.optJSONObject("m2m:sgn")
                            ?.optJSONObject("nev")
                            ?.optJSONObject("rep")
                            ?.optJSONObject("m2m:cin")
                    else -> null
                }

                if (cinObject != null) {
                    con = cinObject.optString("con", null)

                    val lblArray = cinObject.optJSONArray("lbl")
                    if (lblArray != null && lblArray.length() > 0) {
                        val innerJsonString = lblArray.optString(0, null)
                        if (innerJsonString != null) {
                            try {
                                val innerJson = JSONObject(innerJsonString)
                                val dataObject = innerJson.optJSONObject("data")
                                if (dataObject != null) {
                                    val results = mutableListOf<String>()
                                    dataObject.keys().forEach { key ->
                                        results.add(dataObject.optString(key, ""))
                                    }
                                    lblList = results
                                }
                            } catch (e: Exception) {
                                Log.w("MQTT_PARSE", "Failed to parse lbl JSON: $innerJsonString")
                            }
                        }
                    }
                }

                val container = to.substringAfterLast('/').takeIf { it.isNotBlank() && it != "la" }

                onNotifyListener?.invoke(container, con, lblList)

                val ack = JSONObject()
                    .put("rsc", 2000)
                    .put("to", fr)
                    .put("fr", cfg.aeId)
                    .put("rqi", rqi)
                    .put("rvi", cfg.rvi)
                publish(cfg.respTopicAEtoCSE(), ack.toString())
                return
            }

            if (json.has("rsc")) {
                val rqi = json.optString("rqi")
                val rsc = json.optInt("rsc")
                onResponseListener?.invoke(rqi, rsc, payload)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun publishCreateCin(
        toPath: String,
        conValue: String,
        rqi: String,
        ty: Int = 4
    ): String {
        val to = normalizeToPath(toPath)

        val req = JSONObject()
            .put("op", 1)
            .put("to", to)
            .put("fr", cfg.aeId)
            .put("rqi", rqi)
            .put("ty", ty)
            .put("pc", JSONObject().put("m2m:cin", JSONObject().put("con", conValue)))
            .put("rvi", cfg.rvi)

        publish(cfg.reqTopicAEtoCSE(), req.toString())
        return rqi
    }

    fun publishCreateSubscription(
        toPath: String,
        notificationUri: String,
        ty: Int = 23
    ): String {
        val to = normalizeToPath(toPath)
        val rqi = "sub-${UUID.randomUUID()}"

        val subPayload = JSONObject()
            .put("nu", org.json.JSONArray(listOf(notificationUri)))
            .put("nct", 2)

        val req = JSONObject()
            .put("op", 1)
            .put("to", to)
            .put("fr", cfg.aeId)
            .put("rqi", rqi)
            .put("ty", ty)
            .put("pc", JSONObject().put("m2m:sub", subPayload))
            .put("rvi", cfg.rvi)

        publish(cfg.reqTopicAEtoCSE(), req.toString())
        return rqi
    }

    private fun normalizeToPath(toPath: String): String {
        if (toPath.startsWith("/")) return toPath
        val rel = if (toPath.startsWith("/")) toPath else "/$toPath"
        return cfg.cseIdPath + rel
    }

    private fun publish(topic: String, payload: String) {
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload.toByteArray(StandardCharsets.UTF_8))
            .send()
    }
}