package com.example.tiny2.monitor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tiny2.data.db.AppDatabase
import com.example.tiny2.data.entities.SensorSampleEntity
import com.example.tiny2.network.TinyIoTApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.tiny2.network.ActuationLatency
import com.example.tiny2.network.mqtt.MqttConfig
import com.example.tiny2.network.mqtt.OneM2MMqtt

class DeviceMonitorViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val stCache = mutableMapOf<String, Int>()

    // ---------------------------------------------------------------------
    // DB
    // ---------------------------------------------------------------------
    private val db = AppDatabase.get(application)
    private val sampleDao = db.sensorSampleDao()

    // ---------------------------------------------------------------------
    // 메모리 히스토리 버퍼 (센서 remote 별)
    // ---------------------------------------------------------------------
    private val histories = mutableMapOf<String, ArrayDeque<Float>>()
    private val historiesFlow = MutableStateFlow<Map<String, List<Float>>>(emptyMap())

    private val intervalMsBySensor = mutableMapOf<String, Long>()

    private val WINDOW_MINUTES = 20L
    private val WINDOW_MS get() = WINDOW_MINUTES * 60_000L

    private val _unhealthyAlert = MutableStateFlow<String?>(null)
    val unhealthyAlert: StateFlow<String?> = _unhealthyAlert

    fun dismissUnhealthyAlert() {
        _unhealthyAlert.value = null
    }

    // ---------------------------------------------------------------------
    // 외부에서 interval 등록(트리 로드 시 호출)
    // ---------------------------------------------------------------------
    fun registerSensorInterval(ae: String, remote: String, intervalMs: Long) {
        intervalMsBySensor[remote] = intervalMs
        ensureCapacity(remote)
    }

    private fun ensureCapacity(remote: String) {
        val interval = intervalMsBySensor[remote] ?: 60_000L
        val cap = ((WINDOW_MS + interval - 1) / interval)
            .coerceIn(10, 240)
        val q = histories.getOrPut(remote) { ArrayDeque() }
        while (q.size > cap) q.removeFirst()
    }

    private val _actLatency = MutableStateFlow<Map<String, ActuationLatency>>(emptyMap())
    val actLatency: StateFlow<Map<String, ActuationLatency>> = _actLatency
    private val _actBusy = MutableStateFlow<Set<String>>(emptySet())
    val actBusy: StateFlow<Set<String>> = _actBusy

    fun commandActuatorMeasured(ae: String, remote: String, value: String) {
        viewModelScope.launch {
            _actBusy.update { it + remote }
            try {
                val res = TinyIoTApi.sendActuatorWithLatency(ae, remote, value)
                _actLatency.update { it + (remote to res) }
                onActuatorChanged(ae, remote)
            } finally {
                _actBusy.update { it - remote }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 새 샘플 반영 (메모리/스트림)
    // ---------------------------------------------------------------------
    private fun onSensorSample(remote: String, value: Float) {
        ensureCapacity(remote)
        val q = histories.getOrPut(remote) { ArrayDeque() }
        q.addLast(value)

        val interval = intervalMsBySensor[remote] ?: 60_000L
        val cap = ((WINDOW_MS + interval - 1) / interval).coerceIn(10, 240)
        while (q.size > cap) q.removeFirst()

        historiesFlow.update { it.toMutableMap().apply { put(remote, q.toList()) } }
    }

    // ---------------------------------------------------------------------
    // 화면에서 구독하는 히스토리/통계 API  (중복 정의 금지)
    // ---------------------------------------------------------------------
    fun historyOf(remote: String): StateFlow<List<Float>> =
        historiesFlow
            .map { it[remote] ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun statsOf(remote: String): Triple<Float, Float, Float> {
        val list = histories[remote].orEmpty()
        if (list.isEmpty()) return Triple(Float.NaN, Float.NaN, Float.NaN)
        var sum = 0f
        var max = Float.NEGATIVE_INFINITY
        var min = Float.POSITIVE_INFINITY
        list.forEach {
            sum += it
            if (it > max) max = it
            if (it < min) min = it
        }
        return Triple(sum / list.size, max, min)
    }

    fun addSample(ae: String, remote: String, value: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            sampleDao.insert(
                SensorSampleEntity(
                    ae = ae,
                    remote = remote,
                    ts = System.currentTimeMillis(),
                    value = value
                )
            )
        }
        onSensorSample(remote, value)
    }

    // ---------------------------------------------------------------------
    // 새로고침/폴링 루프
    // ---------------------------------------------------------------------
    // 센서 & 액추에이터 실시간 값 (상세/목록에서 쓰는 현재값들)
    private val _sensorValues = MutableStateFlow<Map<String, Float>>(emptyMap())
    val sensorValues: StateFlow<Map<String, Float>> = _sensorValues

    private val _sensorStringValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val sensorStringValues: StateFlow<Map<String, String>> = _sensorStringValues

    private val _inferenceValues = MutableStateFlow<Map<String, Pair<String?, List<String>>>>(emptyMap())
    val inferenceValues: StateFlow<Map<String, Pair<String?, List<String>>>> = _inferenceValues

    private val _actuatorValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val actuatorValues: StateFlow<Map<String, String>> = _actuatorValues

    private val jobsPerSensor = mutableMapOf<String, Job>()
    private val jobsPerAct = mutableMapOf<String, Job>()

    private var lastAe: String? = null
    private var lastTree: ResourceTree? = null

    fun stop() {
        jobsPerSensor.values.forEach { it.cancel() }
        jobsPerAct.values.forEach { it.cancel() }
        jobsPerSensor.clear()
        jobsPerAct.clear()
    }

    fun pause() = stop()

    fun resume() {
        val ae = lastAe
        val tr = lastTree
        if (ae != null && tr != null) start(ae, tr)
    }

    private fun intervalFor(remote: String, tree: ResourceTree?): Long =
        tree?.sensors?.firstOrNull { it.remote == remote }?.intervalMs ?: 60_000L

    fun start(ae: String, tree: ResourceTree) {
        lastAe = ae
        lastTree = tree
        stop()


        viewModelScope.launch(Dispatchers.IO) {
            val notificationUri = mqttCfg.aeId

            tree.sensors.forEach { sensor ->
                val path = "TinyIoT/$ae/Sensors/${sensor.remote}"
                mqtt.publishCreateSubscription(path, notificationUri)
                delay(50)
            }

            tree.actuators.forEach { act ->
                val path = "TinyIoT/$ae/Actuators/${act.remote}"
                mqtt.publishCreateSubscription(path, notificationUri)
                delay(50)
            }

            listOf("species", "health").forEach { inf ->
                val path = "TinyIoT/$ae/inference/$inf"
                mqtt.publishCreateSubscription(path, notificationUri)
                delay(50)
            }

            Log.d("MQTT_VM", "Subscription requests sent for AE: $ae")
        }
    }

    private fun processInferenceData(remote: String, dataPair: Pair<String?, List<String>>?) {
        if (dataPair == null) return

        _inferenceValues.update { it + (remote to dataPair) }

        if (remote == "health") {
            val healthList = dataPair.second
            val unhealthyPlant = healthList.find { it.startsWith("unhealthy_") }

            if (unhealthyPlant != null) {
                val speciesName = unhealthyPlant.removePrefix("unhealthy_")
                _unhealthyAlert.value = speciesName
            }
        }
    }

    private fun launchSensorLoop(ae: String, remote: String, intervalMs: Long) =
        viewModelScope.launch {
            while (isActive) {
                val cntPath = "TinyIoT/$ae/Sensors/$remote"

                val st = TinyIoTApi.fetchStateTag(cntPath)
                val prev = stCache[remote]
                if (st == null || st != prev) {
                    st?.let { stCache[remote] = it }
                    TinyIoTApi.fetchLatestCinFloat(cntPath)?.let { v ->
                        _sensorValues.update { it + (remote to v) }
                        onSensorSample(remote, v)
                    }
                }

                delay(intervalMs)
            }
        }

    private fun launchActuatorLoop(ae: String, remote: String, intervalMs: Long = 1_000L) =
        viewModelScope.launch {
            val path = "TinyIoT/$ae/Actuators/$remote"

            TinyIoTApi.fetchLatestCin(path)?.let { s ->
                Log.d("ACT_GET", "kick $path -> $s")
                _actuatorValues.update { it + (remote to s) }
                Log.d("Act", "[$remote] (kick) = $s")
            }

            while (isActive) {
                TinyIoTApi.fetchLatestCin(path)?.let { s ->
                    Log.d("ACT_GET", "$path -> $s")
                    _actuatorValues.update { it + (remote to s) }
                    Log.d("Act", "[$remote] = $s")
                }
                delay(intervalMs)
            }
        }

    fun refreshActuatorOnce(ae: String, remote: String) = viewModelScope.launch {
        TinyIoTApi.fetchLatestCin("TinyIoT/$ae/Actuators/$remote")?.let { s ->
            _actuatorValues.update { it + (remote to s) }
        }
    }

    fun refreshOne(ae: String, remote: String) {
        viewModelScope.launch {
            val path = "TinyIoT/$ae/Sensors/$remote"
            TinyIoTApi.fetchLatestCinFloat(path)?.let { v ->
                _sensorValues.update { it + (remote to v) }
            }
        }
    }

    fun intervalMsFor(remote: String): Long =
        intervalMsBySensor[remote] ?: 60_000L

    suspend fun backfillHistory(ae: String, remote: String, points: Int) {
        Log.d("HIST_VM", "backfill start ae=$ae remote=$remote points=$points")

        val path = "TinyIoT/$ae/Sensors/$remote"

        val list: List<Float> = TinyIoTApi.fetchHistoryFloats(path, points) ?: emptyList()
        Log.d("HIST_VM", "net result size=${list.size}, values=$list")

        val latest = TinyIoTApi.fetchLatestCinFloat(path)
        Log.d("HIST_VM", "latest value = $latest")

        val combined = buildList<Float> {
            addAll(list.asReversed())
            if (latest != null && (isEmpty() || latest != last())) {
                add(latest)
            }
        }

        val q = ArrayDeque<Float>()
        combined.forEach { q.addLast(it) }
        histories[remote] = q
        historiesFlow.value = histories.mapValues { it.value.toList() }

        Log.d("HIST_VM",
            "histories[$remote] size=${q.size}, head=${q.firstOrNull()} tail=${q.lastOrNull()}")
    }

    fun onActuatorChanged(ae: String, remote: String) {
        jobsPerAct[remote]?.cancel()
        jobsPerAct[remote] = launchActuatorLoop(ae, remote)
    }

    /** 상세 화면: 편의 함수 (이름 유지용) */
    fun refreshSensor(ae: String, sensor: String) = refreshOne(ae, sensor)

    /** 여러 개 강제 갱신(초기 진입/전체 새로고침 버튼) */
    fun forceRefreshOnce(ae: String, sensors: List<String>, acts: List<String>, infs: List<String>) {
        viewModelScope.launch {
            sensors.forEach { r ->
                val path = "TinyIoT/$ae/Sensors/$r"
                val conString = TinyIoTApi.fetchLatestCin(path)

                if (conString != null) {
                    val f = conString.toFloatOrNull()
                    if (f != null) {
                        _sensorValues.update { it + (r to f) }
                        onSensorSample(r, f)
                    } else {
                        _sensorStringValues.update { it + (r to conString) }
                    }
                }
            }
            acts.forEach { r ->
                val path = "TinyIoT/$ae/Actuators/$r"
                val s = TinyIoTApi.fetchLatestCin(path)
                if (s != null) _actuatorValues.update { it + (r to s) }
            }
            infs.forEach { r ->
                val path = "TinyIoT/$ae/inference/$r"
                val dataPair = TinyIoTApi.fetchLatestCinLabelData(path)
                processInferenceData(r, dataPair)
            }
        }
    }

    private val mqttCfg = MqttConfig(
        host = "203.250.148.89",
        port = 1883,
        aeId = "CAdmin",
        cseId = "tinyiot"
    )
    private val mqtt = OneM2MMqtt(mqttCfg)

    private val pendingAct = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun commandActuatorViaMqtt(ae: String, remote: String, value: String) {
        _actBusy.update { it + remote }
        val toPath = "TinyIoT/$ae/Actuators/$remote"

        val rqi = "req-${java.util.UUID.randomUUID()}"
        val start = System.currentTimeMillis()

        pendingAct[rqi] = remote to start

        mqtt.publishCreateCin(toPath, value, rqi)
    }

    init {
        mqtt.setOnNotify { container, con, lblList ->
            if (container == null) return@setOnNotify

            if (lblList != null && lblList.isNotEmpty()) {
                processInferenceData(container, (null to lblList))
                return@setOnNotify
            }

            if (con == null) return@setOnNotify

            val f = con.toFloatOrNull()
            if (f != null) {
                _sensorValues.update { it + (container to f) }
                onSensorSample(container, f)
            } else {
                _sensorStringValues.update { it + (container to con) }

                _actuatorValues.update { it + (container to con) }
            }
        }
        mqtt.setOnResponse { rqi, rsc, _ ->
            val pair = pendingAct.remove(rqi)
            if (pair != null) {
                val (remote, start) = pair
                val rtt = System.currentTimeMillis() - start
                val res = ActuationLatency(
                    finalValue = _actuatorValues.value[remote] ?: "",
                    httpMs = rtt,
                    observedMs = 0L,
                    totalMs = rtt,
                    ok = rsc in 2000..2999
                )
                _actLatency.update { it + (remote to res) }
                _actBusy.update { it - remote }
                lastAe?.let { aeNow -> refreshActuatorOnce(aeNow, remote) }
                Log.d("MQTT_ACT", "remote=$remote rsc=$rsc rtt=${rtt}ms")
            }
            if (rqi.startsWith("sub-")) {
                if (rsc == 2001) {
                    Log.d("MQTT_SUB", "Subscription created OK (rqi=$rqi)")
                } else if (rsc == 4105) {
                    Log.d("MQTT_SUB", "Subscription already exists (rqi=$rqi)")
                } else {
                    Log.w("MQTT_SUB", "Subscription creation failed (rsc=$rsc, rqi=$rqi)")
                }
            }
        }
        mqtt.connect(onConnected = {
            Log.d("MQTT_VM", "MQTT Connected. Ensuring subscriptions...")

            viewModelScope.launch(Dispatchers.IO) {
                val tree = lastTree ?: return@launch
                val ae = lastAe ?: return@launch

                val notificationUri = mqttCfg.aeId

                tree.sensors.forEach { sensor ->
                    val path = "/${mqttCfg.cseId}/TinyIoT/$ae/Sensors/${sensor.remote}"
                    mqtt.publishCreateSubscription(path, notificationUri)
                    delay(50)
                }

                tree.actuators.forEach { act ->
                    val path = "/${mqttCfg.cseId}/TinyIoT/$ae/Actuators/${act.remote}"
                    mqtt.publishCreateSubscription(path, notificationUri)
                    delay(50)
                }
            }
        })
    }
}