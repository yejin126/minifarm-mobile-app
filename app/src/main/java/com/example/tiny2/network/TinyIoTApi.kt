package com.example.tiny2.network

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.tiny.TinyFarmData
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import android.util.Log
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.serialization.json.Json
import com.example.tiny2.monitor.ResourceTree
import com.example.tiny2.monitor.SensorDef
import com.example.tiny2.monitor.ActDef
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.delay
import android.os.SystemClock

private val json = Json { ignoreUnknownKeys = true }

private val httpClient by lazy { OkHttpClient() }

private suspend fun httpGetJson(
    url: String,
    headers: Map<String, String> = emptyMap()
): JSONObject? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()

    httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@withContext null
        val body = resp.body?.string() ?: return@withContext null
        return@withContext JSONObject(body)
    }
}

private fun defaultActuatorInitial(name: String): String = when (name.lowercase()) {
    "fan"      -> "OFF"
    "fan1"     -> "OFF"
    "water"    -> "OFF"
    "door"     -> "Closed"
    "led"      -> "0"         // 밝기 값 (숫자)
    else       -> "OFF"
}

/** 세부 단계별 시간 로깅 (DNS/Connect/TLS/TTFB/Total) */
class TimingEventListener : EventListener() {
    private val t = mutableMapOf<String, Long>()
    private fun mark(k: String) { t[k] = System.nanoTime() }
    private fun ms(a: String, b: String): Double {
        val ta = t[a] ?: return 0.0
        val tb = t[b] ?: return 0.0
        return (tb - ta) / 1_000_000.0
    }

    override fun callStart(call: Call) { mark("callStart") }
    override fun dnsStart(call: Call, domainName: String) { mark("dnsStart") }
    override fun dnsEnd(call: Call, domainName: String, inet: List<InetAddress>) { mark("dnsEnd") }
    override fun connectStart(call: Call, addr: InetSocketAddress, proxy: Proxy) { mark("connStart") }
    override fun secureConnectStart(call: Call) { mark("tlsStart") }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { mark("tlsEnd") }
    override fun connectEnd(call: Call, addr: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { mark("connEnd") }
    override fun requestHeadersStart(call: Call) { mark("reqHeadersStart") }
    override fun responseHeadersStart(call: Call) { mark("respHeadersStart") }

    override fun callEnd(call: Call) {
        mark("callEnd")
        Log.d(
            "NET_TIME",
            """
            URL: ${call.request().url}
            DNS:           ${ms("dnsStart","dnsEnd")} ms
            Connect(+TLS): ${ms("connStart","connEnd")} ms (TLS: ${ms("tlsStart","tlsEnd")} ms)
            TTFB:          ${ms("reqHeadersStart","respHeadersStart")} ms
            Total:         ${ms("callStart","callEnd")} ms
            """.trimIndent()
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val total = (System.nanoTime() - (t["callStart"] ?: System.nanoTime())) / 1_000_000.0
        Log.w("NET_TIME", "FAILED url=${call.request().url} total=${"%.1f".format(total)} ms", ioe)
    }
}

/** 총 소요시간(벽시계 기준) 로깅 */
class WallClockInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val start = System.nanoTime()
        val resp = chain.proceed(chain.request())
        val end = System.nanoTime()
        Log.d(
            "NET_TIME",
            "WallClock total = ${"%.1f".format((end - start) / 1_000_000.0)} ms  ${chain.request().url}"
        )
        return resp
    }
}
// ---------------------------------------------------------------------------


object TinyIoTApi {
    private val client = OkHttpClient.Builder()
        .addInterceptor(WallClockInterceptor())            // 전체 소요시간
        .eventListenerFactory { TimingEventListener() }    // 단계별 시간
        .build()
    private const val BASE = "http://203.250.148.89:3000"
    private const val AE_LIST_QUERY = "?fu=1&ty=2"
    private const val GGW_LAT = 37.55097
    private const val GGW_LNG = 127.07378

    private fun canonOf(remote: String): String = when {
        remote.equals("Temperature", true) -> "Temperature"
        remote.startsWith("Humid", true)   -> "Humidity"
        remote.equals("CO2", true)         -> "CO2"
        remote.equals("Soil", true)        -> "Soil"
        remote.equals("LED", true)         -> "LED"
        remote.startsWith("Fan", true)     -> "Fan"
        remote.equals("Water", true)       -> "Water"
        remote.equals("Door", true)        -> "Door"
        else -> remote
    }

    private fun commonHeaders() = Request.Builder()
        .addHeader("X-M2M-Origin", "CAdmin")
        .addHeader("Accept", "application/json")
        .addHeader("X-M2M-RVI", "2a")



    suspend fun createCnt(ae: String, parent: String, rn: String): Boolean {
        val path = "TinyIoT/$ae/$parent"
        val body = """{"m2m:cnt":{"rn":"$rn"}}"""
        return postRaw(path, body, jsonTy3)
    }
    suspend fun createCin(ae: String, parent: String, rn: String, value: String): Boolean {
        val path = "TinyIoT/$ae/$parent/$rn"
        val body = """{"m2m:cin":{"con":"$value"}}"""
        return postRaw(path, body, jsonTy4)
    }
    suspend fun createActuators(ae: String, names: List<String>) {
        for (r in names) {
            Log.d("CREATE_ACT", "CNT TinyIoT/$ae/Actuators/$r 생성 시도")
            val okCnt = createCnt(ae, "Actuators", r)
            Log.d("CREATE_ACT", "CNT ok=$okCnt")
            if (okCnt) {
                val init = defaultActuatorInitial(r)
                val okCin = createCin(ae, "Actuators", r, init)
                Log.d("CREATE_ACT", "CIN init='$init' ok=$okCin")
            }
        }
    }
    suspend fun createSensors(ae: String, names: List<String>) {
        for (r in names) {
            Log.d("CREATE_SEN", "CNT TinyIoT/$ae/Sensors/$r 생성 시도")
            val okCnt = createCnt(ae, "Sensors", r)
            Log.d("CREATE_SEN", "CNT ok=$okCnt")
            if (okCnt) {
                val okCin = createCin(ae, "Sensors", r, "0")
                Log.d("CREATE_SEN", "CIN init='0' ok=$okCin")
            }
        }
    }

    private fun initialSensorValue(t: String): String = when (t) {
        "Temperature" -> "0"
        "Humid"       -> "0"
        "CO2"         -> "0"
        "Soil"        -> "0"
        else          -> "0"
    }

    private fun initialActuatorValue(t: String): String = when (t) {
        "LED"   -> "0"     // 밝기 0
        "Fan"   -> "OFF"
        "Door"  -> "Closed"
        "Water" -> "OFF"
        else    -> ""
    }

//    suspend fun fetchAvailableAEs(): List<String> = withContext(Dispatchers.IO) {
//        val url = "http://10.0.2.2:3000/TinyIoT?fu=1&ty=2"
//        val request = Request.Builder()
//            .url(url)
//            .addHeader("X-M2M-Origin", "CAdmin")
//            .addHeader("Accept", "application/json")
//            .addHeader("X-M2M-RVI", "2a")
//            .addHeader("Content-Type", "application/json;ty=2")
//            .addHeader("X-M2M-RI", "1234")
//            .build()
//
//        try {
//            val response = client.newCall(request).execute()
//            if (!response.isSuccessful) {
//                Log.w("FETCH_AE", "❌ 응답 실패: ${response.code}")
//                return@withContext emptyList()
//            }
//
//            val jsonStr = response.body?.string() ?: return@withContext emptyList()
//            Log.d("FETCH_AE", "✅ 응답 본문: $jsonStr")
//
//            val json = JSONObject(jsonStr)
//            val raw = json.opt("m2m:uril")
//            val urilArray: JSONArray = when (raw) {
//                is JSONArray -> raw
//                is String -> JSONArray().put(raw)
//                else -> {
//                    Log.w("FETCH_AE", "❌ 'm2m:uril' 키 없음 또는 타입 오류: ${raw?.javaClass?.name}")
//                    return@withContext emptyList()
//                }
//            }
//
//            val aeNames = mutableListOf<String>()
//            for (i in 0 until urilArray.length()) {
//                val path = urilArray.getString(i)
//                if (path.startsWith("TinyIoT/") && path.count { it == '/' } == 1) {
//                    aeNames.add(path.substringAfter("/"))
//                }
//            }
//
//            Log.d("FETCH_AE", "✅ parsed AE list: $aeNames")
//            aeNames
//        } catch (e: Exception) {
//            Log.e("FETCH_AE", "❌ 예외 발생", e)
//            emptyList()
//        }
//    }

    suspend fun fetchAvailableAEs(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BASE+"/TinyIoT" + AE_LIST_QUERY)   // ← 기존 "$BASE/$SOME_PATH" 를 이걸로 교체
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<String>()
                val body = resp.body?.string().orEmpty()

                val parsed: UriListResponse =
                    json.decodeFromString<UriListResponse>(body)

                // parsed.uril 그대로 사용
                val names = parsed.uril
                    .mapNotNull { p ->
                        if (p.startsWith("TinyIoT/") && p.count { it == '/' } == 1)
                            p.substringAfter('/') else null
                    }
                    .distinct()

                return@withContext names
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

//    suspend fun parseDevicesFromJson(json: JSONObject): List<TinyFarmData> {
//        Log.d("PARSE_JSON", "🚨 parseDevicesFromJson() 실행됨")
//        Log.d("PARSE_JSON", "받은 JSON 전체: ${json.toString(2)}")
//
//        val result = mutableListOf<TinyFarmData>()
//        Log.d("TEST", "ayd$result")
//        val aeList = json.optJSONArray("m2m:uril") ?: run {
//            Log.d("PARSE_JSON", "aeList가 null임 — key 'm2m:uril' 없음")
//            return result
//        }
//
//        Log.d("PARSE_JSON", "aeList 길이: ${aeList.length()}")
//
//        for (i in 0 until aeList.length()) {
//            val path = aeList.getString(i)
//
//            Log.d("PARSE_JSON", "받은 path: $path")
//
//            if (path.startsWith("TinyIoT/") && path.count { it == '/' } >= 1) {
//                val aeName = path.substringAfterLast("/")
//                val aeResp = fetchAe(aeName)
//                if (aeResp == null) {
//                    Log.w("PARSE_JSON", "❌ $aeName 응답이 null임")
//                    continue
//                }
//
//                Log.d("PARSE_JSON", "📦 $aeName AE 응답: ${aeResp.toString(2)}")
//
//                val ae = aeResp.optJSONObject("m2m:ae")
//                if (ae == null) {
//                    Log.w("PARSE_JSON", "⚠️ ae null – $aeName 에서 m2m:ae 못 찾음")
//                    continue
//                }
//
//                val name = ae.optString("rn", "No name")
//                val rawLoc = ae.optJSONArray("lbl")?.findLocationLabel()
//                val location = if (rawLoc.isNullOrBlank()) "No location information" else rawLoc
//
//                val sensors = fetchSensors(name)
//                val actuators = fetchActuators(name)
//
//                val temp = sensors["Temperature"]
//                Log.d("abcd","$temp")
//                val humi = sensors["Humid"]
//
//                Log.d("PARSE", "📦 생성된 Device: $name, sensors=$sensors, actuators=$actuators")
//
//                result.add(
//                    TinyFarmData(
//                        name = name,
//                        location = "Sejong Univ, Gwanggaeto Hall",
//                        sensors = sensors,
//                        actuators = actuators,
//                        lat = GGW_LAT,
//                        lng = GGW_LNG,
//                        lastUpdated = nowTimeString(),
//                        temperatureHistory = if (temp != null) listOf(temp) else listOf(),
//                        humidityHistory = if (humi != null) listOf(humi) else listOf()
//                    )
//                )
//
//                Log.d("PARSE", "📦 생성된 Device: $name, sensors=$sensors, actuators=$actuators")
//            }
//        }
//
//        Log.d("PARSE_JSON", "🎯 최종 디바이스 개수: ${result.size}")
//
//        return result
//    }

    suspend fun fetchAe(aeName: String): JSONObject? {
        val request = Request.Builder()
            .url("$BASE/TinyIoT/$aeName")
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("Accept", "application/json")
            .addHeader("X-M2M-RVI", "2a")
            .get()
            .build()

        return suspendCancellableCoroutine { cont ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string()
                        cont.resume(JSONObject(body))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        cont.resume(null)
                    }
                }
            })
        }
    }

    private fun Request.Builder.commonHeaders(): Request.Builder =
        this.header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")

    private fun cntNameFromTy4(base: String, uri: String): String? {
        if (!uri.startsWith("$base/")) return null
        val after = uri.removePrefix("$base/")   // "Fan1/4-2025..." or "LED/5-..."
        return after.substringBefore('/')        // → "Fan1" / "LED"
    }

    private suspend fun getCntNamesWithFallback(basePath: String): List<String> {
        // 1) ty=4 우선: ".../<CNT>/<cin-id>"
        val uris4 = httpGetUris("$basePath?fu=1&ty=4")
        val from4 = uris4.mapNotNull { uri ->
            // basePath 이후 첫 세그먼트만 취함 → LED/4-... -> LED, Fan1/4-... -> Fan1
            if (!uri.startsWith("$basePath/")) null
            else uri.removePrefix("$basePath/").substringBefore('/')
                .takeIf { it.isNotBlank() }
        }.distinct()
        if (from4.isNotEmpty()) return from4

        // 2) fallback ty=3: 서버가 CNT 대신 /<CNT>/<값>들을 줄 때가 있어 첫 세그먼트만 취함
        val uris3 = httpGetUris("$basePath?fu=1&ty=3")
        val from3 = uris3.mapNotNull { uri ->
            if (!uri.startsWith("$basePath/")) null
            else uri.removePrefix("$basePath/").substringBefore('/')
                .takeIf { it.isNotBlank() }
        }.distinct()

        return from3
    }

    suspend fun fetchResourceTree(ae: String): ResourceTree {
        val sensorCnts = getCntNamesWithFallback("TinyIoT/$ae/Sensors")
        val actCnts    = getCntNamesWithFallback("TinyIoT/$ae/Actuators")
        val sensors = sensorCnts.map { SensorDef(canonical = canonOf(it), remote = it, intervalMs = 60_000L) }
        val acts    = actCnts.map    { ActDef   (canonical = canonOf(it), remote = it) }
        Log.d(
            "TREE",
            "fresh sensors=${sensors.map { it.remote }} acts=${acts.map { it.remote }}"
        )
        return ResourceTree(sensors = sensors, actuators = acts)
    }

    // fu=1 응답의 m2m:uril(또는 m2m:uri) 배열을 파싱해서 List<String>으로 반환
    private suspend fun httpGetUris(path: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/$path")
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()

                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val raw = obj.opt("m2m:uril") ?: obj.opt("m2m:uri")

                val list = when (raw) {
                    is JSONArray -> List(raw.length()) { raw.getString(it) }
                    is String    -> listOf(raw)
                    else         -> emptyList()
                }
                // 디버깅 로그
                Log.d("TREE_URIS", "path=$path -> size=${list.size} first=${list.firstOrNull()}")
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchLatestCin(path: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/$path/la"
        val req = Request.Builder()
            .url(url)
            .header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("ACT_GET", "GET $url failed code=${resp.code} body=${resp.body?.string()}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                return@withContext org.json.JSONObject(body)
                    .getJSONObject("m2m:cin")
                    .optString("con", null)
            }
        } catch (e: Exception) {
            Log.w("ACT_GET", "GET $url exception: ${e.message}")
            null
        }
    }

    // 숫자로 파싱
    suspend fun fetchLatestCinFloat(path: String): Float? =
        fetchLatestCin(path)?.toFloatOrNull()

    suspend fun fetchLatestCinText(cntPath: String): String? = suspendCancellableCoroutine { cont ->
        val req = Request.Builder()
            .url("$BASE/$cntPath/la")
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Accept", "application/json")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(null, null)
            override fun onResponse(call: Call, resp: Response) {
                resp.use {
                    val raw = resp.body?.string().orEmpty()
                    val con = runCatching { Json.decodeFromString<CinEnvelope>(raw).cin?.con }.getOrNull()
                    cont.resume(con, null)
                }
            }
        })
    }

    suspend fun fetchActuators(ae: String): Map<String, String> {
        val tree = fetchResourceTree(ae)               // 기존에 이미 있는 함수
        val result = mutableMapOf<String, String>()
        for (def in tree.actuators) {
            val v = fetchLatestCin("TinyIoT/$ae/Actuators/${def.remote}")
            if (v != null) result[def.remote] = v
        }
        return result
    }

    private suspend fun fetchCinOnce(path: String): String? {
        val url = "http://203.250.148.89:3000/$path"
        val req = Request.Builder()
            .url(url)
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("Accept", "application/json")
            .addHeader("X-M2M-RVI", "2a")
            .build()

        return suspendCancellableCoroutine { cont ->
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(null, null)
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = response.body?.string().orEmpty()
                        // m2m:cin 에서 con만 안전하게 추출
                        val con = runCatching {
                            JSONObject(body).optJSONObject("m2m:cin")?.optString("con", null)
                        }.getOrNull()

                        Log.d("API", "요청=$path, 응답=$body, con=$con")
                        cont.resume(con, null)
                    }
                }
            })
        }
    }

    private val mediaJson  = "application/json".toMediaType()
    private val jsonTy3    = "application/json;ty=3".toMediaType()
    private val jsonTy4    = "application/json;ty=4".toMediaType()

    private suspend fun postRaw(path: String, body: String, mediaType: MediaType): Boolean =
        suspendCancellableCoroutine { cont ->
            val reqBody = body.toRequestBody(mediaType)
            val req = Request.Builder()
                .url("$BASE/$path")
                .post(reqBody)
                .addHeader("X-M2M-Origin", "CAdmin")
                .addHeader("X-M2M-RVI", "2a")
                .addHeader("Accept", "application/json")
                .build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w("POST_RAW","$path FAIL ${e.message}")
                    cont.resume(false, null)
                }
                override fun onResponse(call: Call, resp: Response) {
                    val ok = resp.isSuccessful || resp.code == 409   // ✅ 여기서 409 허용
                    Log.d("POST_RAW","$path -> ${resp.code} ok=$ok")
                    resp.close()
                    cont.resume(ok, null)
                }
            })
        }

    suspend fun postCinText(cntPath: String, value: String): Boolean {
        val url = "http://203.250.148.89:3000/$cntPath"
        val body = """{"m2m:cin":{"con":"$value"}}""".toRequestBody("application/json;ty=4".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Accept", "application/json")
            .build()
        return suspendCancellableCoroutine { cont ->
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(false, null)
                override fun onResponse(call: Call, response: Response) {
                    response.use { cont.resume(response.isSuccessful, null) }
                }
            })
        }
    }

    suspend fun seedActuatorDefault(ae: String, remote: String, label: String): String {
        val value = when (label.lowercase()) {
            "fan", "fan1", "fan2" -> "OFF"
            "water"               -> "OFF"
            "door"                -> "Closed"
            "led"                 -> "0"
            else                  -> ""       // 모르는 타입은 패스
        }
        if (value.isNotEmpty()) {
            postCinText("TinyIoT/$ae/Actuators/$remote", value)
        }
        return value
    }

    suspend fun fetchByTree(ae: String, tree: ResourceTree):
            Pair<Map<String, Float>, Map<String, String>> {
        val sensors = mutableMapOf<String, Float>()
        for (def in tree.sensors) {
            val cin = fetchLatestCin("TinyIoT/$ae/Sensors/${def.remote}")
            cin?.toFloatOrNull()?.let { sensors[def.remote] = it }
        }
        val acts = mutableMapOf<String, String>()
        for (def in tree.actuators) {
            val cin = fetchLatestCin("TinyIoT/$ae/Actuators/${def.remote}")
            if (cin != null) acts[def.remote] = cin
        }
        return sensors to acts
    }

    suspend fun parseSingleDeviceFromJson(json: JSONObject): TinyFarmData? {
        val ae = json.optJSONObject("m2m:ae") ?: return null

        val name = ae.optString("rn", "이름없음")
        val rawLoc = ae.optJSONArray("lbl")?.findLocationLabel()
        val location = if (rawLoc.isNullOrBlank()) "위치 정보 없음" else rawLoc

        val sensors = fetchSensors(name)
        val actuators = fetchActuators(name)

        val temp = sensors["Temperature"]
        val humi = sensors["Humid"]

        return TinyFarmData(
            name = name,
            location = location,
            sensors = sensors,
            actuators = actuators,
            lat = GGW_LAT,
            lng = GGW_LNG,
            lastUpdated = nowTimeString(),
            temperatureHistory = if (temp != null) listOf(temp) else listOf(),
            humidityHistory = if (humi != null) listOf(humi) else listOf()
        )
    }

    suspend fun fetchAddableCnts(ae: String): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        fun getUris(url: String): List<String> {
            val req = Request.Builder()
                .url(url)
                .addHeader("X-M2M-Origin", "CAdmin")
                .addHeader("Accept", "application/json")
                .addHeader("X-M2M-RVI", "2a")
                .build()
            return runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val raw = json.opt("m2m:uril") ?: json.opt("m2m:uri")
                    when (raw) {
                        is JSONArray -> List(raw.length()) { raw.getString(it) }
                        is String    -> listOf(raw)
                        else         -> emptyList()
                    }
                }
            }.getOrElse { emptyList() }
        }

        fun fromTy4(base: String, uris: List<String>) =
            uris.mapNotNull { it.removePrefix("$base/").substringBefore('/').takeIf { s -> s.isNotBlank() } }
                .distinct()

        fun fromTy3(uris: List<String>) =
            uris.map { it.substringAfterLast('/') }.distinct()

        val sensorsBase = "TinyIoT/$ae/Sensors"
        val actsBase    = "TinyIoT/$ae/Actuators"   // ← 복수형

        val s4 = getUris("$BASE/$sensorsBase?fu=1&ty=4")
        val sensors = if (s4.isNotEmpty()) fromTy4(sensorsBase, s4)
        else fromTy3(getUris("$BASE/$sensorsBase?fu=1&ty=3"))

        val a4 = getUris("$BASE/$actsBase?fu=1&ty=4")
        val acts = if (a4.isNotEmpty()) fromTy4(actsBase, a4)
        else fromTy3(getUris("$BASE/$actsBase?fu=1&ty=3"))

        sensors to acts
    }

    suspend fun fetchTinyIoTDetail(aeName: String): TinyFarmData? =
        withContext(Dispatchers.IO) {
            try {
                val url = "http://203.250.148.89:3000/TinyIoT/$aeName"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-M2M-Origin", "CAdmin")
                    .addHeader("X-M2M-RVI", "2a")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()

                    val parsed: AeResponse = json.decodeFromString<AeResponse>(body)

                    val name = parsed.ae?.rn ?: return@withContext null
                    val location = parsed.ae.lbl.firstOrNull { it.startsWith("location:") }
                        ?.substringAfter("location:")
                        ?.trim()
                        ?: "위치 정보 없음"

                    // ↓ 기존 로직으로 센서/액추에이터 값 가져오기
                    val sensors = fetchSensors(name)
                    val actuators = fetchActuators(name)

                    return@withContext TinyFarmData(
                        name = name,
                        location = location,
                        sensors = sensors,
                        actuators = actuators,
                        lat = 37.55097,   // 현재 고정값 사용 중
                        lng = 127.07378,
                        lastUpdated = nowTimeString(),
                        temperatureHistory = sensors["Temperature"]?.let { listOf(it) } ?: emptyList(),
                        humidityHistory    = sensors["Humid"]?.let { listOf(it) } ?: emptyList()
                    )
                }
            } catch (e: Exception) {
                Log.e("AE_DETAIL", "예외", e)
                null
            }
        }

    suspend fun fetchSensors(aeName: String): Map<String, Float> {
        val remotes = getCntNamesWithFallback("TinyIoT/$aeName/Sensors")
        val raw = mutableMapOf<String, Float>()

        for (remote in remotes) {
            val path = "TinyIoT/$aeName/Sensors/$remote"
            val v = fetchLatestCin(path)?.toFloatOrNull() ?: continue
            raw[remote] = v
        }

        // 정규화
        fun canonOf(r: String) = when {
            r.equals("Temperature", true) -> "Temperature"
            r.startsWith("Humid", true)   -> "Humidity"
            r.equals("CO2", true)         -> "CO2"
            r.equals("Soil", true)        -> "Soil"
            else -> r
        }

        val boxed = raw.entries.groupBy({ canonOf(it.key) }, { it.value })

        val result = mutableMapOf<String, Float>()
        for ((k, vs) in boxed) {
            // humid 계열은 0이 아닌 값을 우선 채택, 없으면 0 중 하나
            val picked = if (k == "Humidity") {
                vs.firstOrNull { it != 0f } ?: vs.firstOrNull()
            } else {
                vs.firstOrNull()
            }
            if (picked != null) result[k] = picked
        }
        return result
    }

    // TinyIoTApi.kt
    // 파일 상단
    private const val TAG_ACT = "ACT_MEASURE"
    private fun String.norm() = trim().uppercase()   // 공백/대소문자 차이 제거

    suspend fun sendActuatorWithLatency(
        ae: String,
        remote: String,
        value: String,
        timeoutMs: Long = 4_000,
        pollMs: Long = 150
    ): ActuationLatency = withContext(Dispatchers.IO) {

        val path = "TinyIoT/$ae/Actuators/$remote"
        val want = value.norm()
        val t0   = SystemClock.elapsedRealtime()

        Log.d(TAG_ACT, "▶ POST start remote=$remote want=$want path=$path")

        // 1) 명령 전송
        val okPost = sendActuatorCommand(ae, remote, value) // 기존 함수 사용
        val httpMs = SystemClock.elapsedRealtime() - t0
        Log.d(TAG_ACT, "◀ POST done ok=$okPost httpMs=${httpMs}ms")

        if (!okPost) {
            return@withContext ActuationLatency(
                ok = false, totalMs = httpMs, httpMs = httpMs, finalValue = null, observedMs = httpMs
            )
        }

        // 2) st/값 변화 대기
        var lastSt = -1
        var lastLa: String? = null
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            // (선택) CNT stateTag 체크
            val st = fetchStateTag(path) ?: -1
            if (st != -1 && st == lastSt) {
                delay(pollMs); continue
            }
            lastSt = st

            val la = fetchLatestCin(path)   // 서버의 실제 최신 con
            Log.d(TAG_ACT, "poll st=$st la=$la (want=$want)")
            if (la != null) lastLa = la

            if (la?.norm() == want) {
                val total = SystemClock.elapsedRealtime() - t0
                Log.d(TAG_ACT, "✅ match remote=$remote total=${total}ms httpMs=${httpMs}ms")
                return@withContext ActuationLatency(
                    ok = true, totalMs = total, httpMs = httpMs, finalValue = la, observedMs = total
                )
            }

            delay(pollMs)
        }

        val total = SystemClock.elapsedRealtime() - t0
        Log.w(TAG_ACT, "⛔ timeout remote=$remote total=${total}ms lastLa=$lastLa lastSt=$lastSt want=$want")
        ActuationLatency(
            ok = false, totalMs = total, httpMs = httpMs, finalValue = lastLa, observedMs = total
        )
    }

    fun JSONArray.findLocationLabel(): String? {
        for (i in 0 until length()) {
            val item = getString(i)
            if (item.startsWith("location:")) {
                val value = item.substringAfter("location:").trim()
                return if (value.isBlank()) null else value
            }
        }
        return null
    }

    suspend fun fetchHistoryFloats(cntPath: String, limit: Int): List<Float>? {
        val url = "http://203.250.148.89:3000/$cntPath?rcn=4&ty=4&lim=$limit"
        Log.d("HIST_NET", "REQ url=$url")

        val json = httpGetJson(url, headers = mapOf(
            "X-M2M-Origin" to "CAdmin",
            "X-M2M-RVI" to "2a",
            "Accept" to "application/json"
        )) ?: return null

        val cnt = json.optJSONObject("m2m:cnt") ?: return emptyList()

        val cinRaw = cnt.opt("m2m:cin")
        val arr = when (cinRaw) {
            is JSONArray -> cinRaw
            is JSONObject -> JSONArray().put(cinRaw)
            else -> return emptyList()
        }

        val out = ArrayList<Float>(arr.length())
        for (i in 0 until arr.length()) {
            val cin = arr.getJSONObject(i)
            val con = cin.optString("con")
            con.toFloatOrNull()?.let { out.add(it) }
        }
        return out
    }

    suspend fun fetchStateTag(cntPath: String): Int? {
        // cntPath 예: "TinyIoT/<AE>/Sensors/<remote>"
        val url = "http://203.250.148.89:3000/$cntPath"
        val req = Request.Builder()
            .url(url)
            .header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.string().orEmpty()
                    JSONObject(body).getJSONObject("m2m:cnt").optInt("st", -1)
                        .takeIf { it >= 0 }
                }
            }.getOrNull()
        }
    }

    fun nowTimeString(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    suspend fun sendActuatorCommand(
        ae: String,
        actuatorType: String,
        value: String
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "http://203.250.148.89:3000/TinyIoT/$ae/Actuators/$actuatorType"

        val json = JSONObject().put("m2m:cin", JSONObject().put("con", value))
        val body = json.toString().toRequestBody("application/json;ty=4".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-M2M-Origin", "CAdmin")
            .addHeader("Accept", "application/json")
            .addHeader("X-M2M-RVI", "2a")
            .addHeader("Content-Type", "application/json;ty=4")
            .build()

        try {
            Log.d("ACTUATOR_COMMAND", "Sending POST to: $url  payload=$json")
            client.newCall(request).execute().use { resp ->
                Log.d("ACTUATOR_COMMAND", "code=${resp.code} body=${resp.body?.string()}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("ACTUATOR_COMMAND", "Exception", e)
            false
        }
    }
}