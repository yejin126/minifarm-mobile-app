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
        "http://203.250.148.89:3000/TinyIoT/TinyFarm/Sensors"  // CNT 부모 경로

    private fun req(url: String): Request =
        Request.Builder()
            .url(url)
            .header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .build()

    /** CNT의 stateTag(st) 조회 */
    suspend fun getStateTag(cnt: String): Int {
        client.newCall(req("$BASE/$cnt")).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return JSONObject(body).getJSONObject("m2m:cnt").getInt("st")
        }
    }

    /** 최신 CIN(/la)의 con 값 조회 */
    suspend fun getLatest(cnt: String): String {
        client.newCall(req("$BASE/$cnt/la")).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return JSONObject(body).getJSONObject("m2m:cin").getString("con")
        }
    }
}