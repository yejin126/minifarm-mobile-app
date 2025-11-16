package com.example.tiny2.network

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.tiny2.TinyFarmData
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    "led"      -> "0"
    else       -> "OFF"
}

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


object TinyIoTApi {
    private val client = OkHttpClient.Builder()
        .addInterceptor(WallClockInterceptor())
        .eventListenerFactory { TimingEventListener() }
        .build()
    private const val BASE = "YOUR_CSE_SERVER_URL_HERE"
    private const val AE_LIST_QUERY = "?fu=1&ty=2"

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

    private fun urlOf(vararg seg: String): String {
        val head = BASE.removeSuffix("/")
        val tail = seg.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        return "$head/$tail"
    }

    private fun urlRaw(path: String): String =
        BASE.trimEnd('/') + "/" + path.trimStart('/')

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

    suspend fun fetchAvailableAEs(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BASE+"/TinyIoT" + AE_LIST_QUERY)
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

    suspend fun fetchLatestCinLabelData(path: String): Pair<String?, List<String>>? = withContext(Dispatchers.IO) {
        val url = "$BASE/$path/la"
        val req = Request.Builder()
            .url(url)
            .header("X-M2M-Origin", "CAdmin")
            .header("X-M2M-RVI", "2a")
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null

                val cinJson = JSONObject(body).optJSONObject("m2m:cin") ?: return@withContext null

                val lblArray = cinJson.optJSONArray("lbl") ?: return@withContext null

                val innerJsonString = lblArray.optString(0, null) ?: return@withContext null

                val innerJson = JSONObject(innerJsonString)

                val timestamp = innerJson.optString("timestamp", null)

                val dataObject = innerJson.optJSONObject("data") ?: return@withContext null

                val results = mutableListOf<String>()
                dataObject.keys().forEach { key ->
                    results.add(dataObject.optString(key, ""))
                }

                return@withContext (timestamp to results)
            }
        } catch (e: Exception) {
            Log.e("LBL_PARSE", "GET $url lbl parsing exception: ${e.message}")
            null
        }
    }

    private suspend fun getCntNamesWithFallback(basePath: String): List<String> {
        val uris4 = httpGetUris("$basePath?fu=1&ty=4")
        val from4 = uris4.mapNotNull { uri ->
            if (!uri.startsWith("$basePath/")) null
            else uri.removePrefix("$basePath/").substringBefore('/')
                .takeIf { it.isNotBlank() }
        }.distinct()
        if (from4.isNotEmpty()) return from4

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

        val infCnts    = getCntNamesWithFallback("TinyIoT/$ae/inference")

        val sensors = sensorCnts.map { SensorDef(canonical = canonOf(it), remote = it, intervalMs = 60_000L) }
        val acts    = actCnts.map    { ActDef   (canonical = canonOf(it), remote = it) }

        val infs    = infCnts.map    { ActDef   (canonical = canonOf(it), remote = it) }

        Log.d(
            "TREE",
            "fresh sensors=${sensors.map { it.remote }} acts=${acts.map { it.remote }} infs=${infs.map { it.remote }}"
        )
        return ResourceTree(sensors = sensors, actuators = acts, inference = infs)
    }

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
    suspend fun fetchLatestCinFloat(path: String): Float? {
        return try {
            val request = Request.Builder()
                .url("$BASE/$path/la")
                .header("X-M2M-Origin", "CAdmin")
                .header("X-M2M-RVI", "2a")
                .header("Accept", "application/json")
                .build()

            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val bodyString = response.body?.string()
                if (bodyString == null) {
                    return@withContext null
                }

                val json = JSONObject(bodyString)

                val conString = json.getJSONObject("m2m:cin").optString("con", null)

                conString?.toFloatOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

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
        val tree = fetchResourceTree(ae)
        val result = mutableMapOf<String, String>()
        for (def in tree.actuators) {
            val v = fetchLatestCin("TinyIoT/$ae/Actuators/${def.remote}")
            if (v != null) result[def.remote] = v
        }
        return result
    }
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
                    val ok = resp.isSuccessful || resp.code == 409
                    Log.d("POST_RAW","$path -> ${resp.code} ok=$ok")
                    resp.close()
                    cont.resume(ok, null)
                }
            })
        }

    suspend fun postCinText(cntPath: String, value: String): Boolean {
        val url  = urlOf(cntPath)
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
            else                  -> ""
        }
        if (value.isNotEmpty()) {
            postCinText("TinyIoT/$ae/Actuators/$remote", value)
        }
        return value
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
        val actsBase    = "TinyIoT/$ae/Actuators"

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
                val url = urlOf("TinyIoT", aeName)

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

                    val gpsString = fetchLatestCin("TinyIoT/$name/Sensors/GPS")

                    val (parsedLat, parsedLng) = parseGpsString(gpsString)

                    val lat = parsedLat ?: 37.55097
                    val lng = parsedLng ?: 127.07378

                    val sensors = fetchSensors(name)
                    val actuators = fetchActuators(name)

                    return@withContext TinyFarmData(
                        name = name,
                        location = location,
                        sensors = sensors,
                        actuators = actuators,
                        lat = lat,
                        lng = lng,
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

    /** GPS 문자열("lat,lng")을 Double 쌍으로 변환 */
    private fun parseGpsString(gps: String?): Pair<Double?, Double?> {
        if (gps == null) return null to null
        val parts = gps.split(',')
        if (parts.size != 2) return null to null
        return parts[0].trim().toDoubleOrNull() to parts[1].trim().toDoubleOrNull()
    }

    suspend fun fetchSensors(aeName: String): Map<String, Float> {
        val remotes = getCntNamesWithFallback("TinyIoT/$aeName/Sensors")
        val raw = mutableMapOf<String, Float>()

        for (remote in remotes) {
            val path = "TinyIoT/$aeName/Sensors/$remote"
            val v = fetchLatestCin(path)?.toFloatOrNull() ?: continue
            raw[remote] = v
        }

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
            val picked = if (k == "Humidity") {
                vs.firstOrNull { it != 0f } ?: vs.firstOrNull()
            } else {
                vs.firstOrNull()
            }
            if (picked != null) result[k] = picked
        }
        return result
    }

    private const val TAG_ACT = "ACT_MEASURE"
    private fun String.norm() = trim().uppercase()

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

        val okPost = sendActuatorCommand(ae, remote, value)
        val httpMs = SystemClock.elapsedRealtime() - t0
        Log.d(TAG_ACT, "◀ POST done ok=$okPost httpMs=${httpMs}ms")

        if (!okPost) {
            return@withContext ActuationLatency(
                ok = false, totalMs = httpMs, httpMs = httpMs, finalValue = null, observedMs = httpMs
            )
        }

        var lastSt = -1
        var lastLa: String? = null
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val st = fetchStateTag(path) ?: -1
            if (st != -1 && st == lastSt) {
                delay(pollMs); continue
            }
            lastSt = st

            val la = fetchLatestCin(path)
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

    suspend fun fetchHistoryFloats(cntPath: String, limit: Int): List<Float>? {
        val url = "${BASE.trimEnd('/')}/$cntPath?rcn=4&ty=4&lim=$limit"
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
        val url = urlRaw(cntPath)
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
        val url = urlOf("TinyIoT", ae, "Actuators", actuatorType)

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