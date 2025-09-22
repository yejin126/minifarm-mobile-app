package com.example.tiny2.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class SensorRepository {

    private val client = OkHttpClient()
    private val baseUrl = "http://10.0.2.2:3000"

    suspend fun fetchLatestSensorValue(ae: String, cnt: String): Float {
        val url = "$baseUrl/$ae/$cnt?rcn=4&ty=4&lim=1"

        val request = Request.Builder()
            .url(url)
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Unexpected code $response")

                val body = response.body?.string() ?: throw Exception("No response body")
                val json = JSONObject(body)

                val cntObj = json.getJSONObject("m2m:cnt")
                val cinArray = cntObj.getJSONArray("m2m:cin")
                if (cinArray.length() == 0) throw Exception("No CIN data")

                val latestCin = cinArray.getJSONObject(0)
                val value = latestCin.getString("con")
                value.toFloatOrNull() ?: throw Exception("Invalid con value")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            -1f  // 실패 시 기본값
        }
    }

    suspend fun fetchAllSensors(ae: String): Map<String, String> {
        val sensors = listOf("temp", "humi", "co2", "soil")
        val results = mutableMapOf<String, String>()

        for (sensor in sensors) {
            val value = fetchLatestSensorValue(ae, sensor)
            if (value != -1f) {
                results[sensor] = value.toString()
            }
        }

        return results
    }

    suspend fun fetchAllActuators(ae: String): Map<String, String> {
        val actuatorTypes = listOf("fan", "water", "door", "led")
        val result = mutableMapOf<String, String>()

        for (actuator in actuatorTypes) {
            val url = "http://10.0.2.2:3000/TinyIoT/$ae/Actuator/$actuator/la"
            val request = Request.Builder()
                .url(url)
                .addHeader("X-M2M-Origin", "CAdmin")
                .addHeader("X-M2M-RVI", "2a")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonString = response.body?.string()
                    val json = JSONObject(jsonString)
                    val con = json.getJSONObject("m2m:cin").optString("con")
                    result[actuator] = con
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return result
    }
}