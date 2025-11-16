package com.example.tiny2.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OneM2M {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val BASE =
        "YOUR_CSE_SERVER_URL_HERE/TinyIoT/TinyFarm/Sensors"

    private fun req(url: String): Request =
        Request.Builder()
            .url(url)
            .header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .build()

    suspend fun getStateTag(cnt: String): Int {
        client.newCall(req("$BASE/$cnt")).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return JSONObject(body).getJSONObject("m2m:cnt").getInt("st")
        }
    }

    suspend fun getLatest(cnt: String): String {
        client.newCall(req("$BASE/$cnt/la")).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return JSONObject(body).getJSONObject("m2m:cin").getString("con")
        }
    }
}