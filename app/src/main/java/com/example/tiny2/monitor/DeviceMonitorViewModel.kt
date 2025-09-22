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

    // interval 기억(트리/설정에서 받아오는 ms)
    private val intervalMsBySensor = mutableMapOf<String, Long>()

    // “최근 N분”만 보여주기 위한 윈도우
    private val WINDOW_MINUTES = 20L
    private val WINDOW_MS get() = WINDOW_MINUTES * 60_000L

    // ---------------------------------------------------------------------
    // 외부에서 interval 등록(트리 로드 시 호출)
    // ---------------------------------------------------------------------
    fun registerSensorInterval(ae: String, remote: String, intervalMs: Long) {
        intervalMsBySensor[remote] = intervalMs
        ensureCapacity(remote)
    }

    private fun ensureCapacity(remote: String) {
        val interval = intervalMsBySensor[remote] ?: 60_000L
        val cap = ((WINDOW_MS + interval - 1) / interval)   // ceil
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
            _actBusy.update { it + remote }          // 보내는 중 표시
            try {
                val res = TinyIoTApi.sendActuatorWithLatency(ae, remote, value)
                _actLatency.update { it + (remote to res) }   // 결과 저장
                onActuatorChanged(ae, remote)                 // 최신값 폴링 재시작
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

        // 용량 유지
        val interval = intervalMsBySensor[remote] ?: 60_000L
        val cap = ((WINDOW_MS + interval - 1) / interval).coerceIn(10, 240)
        while (q.size > cap) q.removeFirst()

        // 스트림 갱신
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

    // DB도 같이 적재(옵션). 화면은 메모리 버퍼를 바로 씀.
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

    // 트리의 intervalMs 사용(없으면 기본 60s)
    private fun intervalFor(remote: String, tree: ResourceTree?): Long =
        tree?.sensors?.firstOrNull { it.remote == remote }?.intervalMs ?: 60_000L

    // 시작: 센서/액추에이터 루프를 트리 기준으로 모두 구동
    fun start(ae: String, tree: ResourceTree) {
        lastAe = ae
        lastTree = tree
        stop()

        // 센서
        tree.sensors.forEach { def ->
            val remote = def.remote
            registerSensorInterval(ae, remote, def.intervalMs)
            jobsPerSensor[remote]?.cancel()
            jobsPerSensor[remote] = launchSensorLoop(ae, remote, def.intervalMs)
        }
        // 액추에이터
        tree.actuators.forEach { def ->
            val remote = def.remote
            jobsPerAct[remote]?.cancel()
            jobsPerAct[remote] = launchActuatorLoop(ae, remote)
        }
    }

    private fun launchSensorLoop(ae: String, remote: String, intervalMs: Long) =
        viewModelScope.launch {
            while (isActive) {
                val cntPath = "TinyIoT/$ae/Sensors/$remote"

                // 1) CNT의 stateTag 확인
                val st = TinyIoTApi.fetchStateTag(cntPath)
                val prev = stCache[remote]
                if (st == null || st != prev) {   // ← st==null이어도 1회는 읽기
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

            // 🔹 루프 돌기 전에 한 번 강제 읽기
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

    /** 상세 화면: 센서 하나 강제 갱신 */
    fun refreshOne(ae: String, remote: String) {
        // 최신 값 조회 → UI 상태만 갱신 (히스토리에는 추가하지 않음)
        viewModelScope.launch {
            val path = "TinyIoT/$ae/Sensors/$remote"
            TinyIoTApi.fetchLatestCinFloat(path)?.let { v ->
                _sensorValues.update { it + (remote to v) }
            }
        }
    }

    // 인터벌을 저장해두고 있다면 꺼내쓰는 헬퍼(없으면 60초 가정)
    fun intervalMsFor(remote: String): Long =
        intervalMsBySensor[remote] ?: 60_000L

    // 진입 시 N개 과거 데이터 백필 (예: 6개 => 5,4,3,2,1,0분 전)
    suspend fun backfillHistory(ae: String, remote: String, points: Int) {
        Log.d("HIST_VM", "backfill start ae=$ae remote=$remote points=$points")

        val path = "TinyIoT/$ae/Sensors/$remote"

        // 1. 과거 데이터 가져오기
        val list: List<Float> = TinyIoTApi.fetchHistoryFloats(path, points) ?: emptyList()
        Log.d("HIST_VM", "net result size=${list.size}, values=$list")

        // 2. 최신값 따로 가져오기
        val latest = TinyIoTApi.fetchLatestCinFloat(path)
        Log.d("HIST_VM", "latest value = $latest")

        // 3. 과거 + 최신값 합치기 (중복 제거)
        val combined = buildList<Float> {
            addAll(list.asReversed())  // 오래된 → 최신 순으로
            if (latest != null && (isEmpty() || latest != last())) {
                add(latest)
            }
        }

        // 4. 메모리 히스토리에 반영
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
    fun forceRefreshOnce(ae: String, sensors: List<String>, acts: List<String>) {
        viewModelScope.launch {
            sensors.forEach { r ->
                val v = TinyIoTApi.fetchLatestCinFloat("TinyIoT/$ae/Sensors/$r")
                if (v != null) {
                    _sensorValues.update { it + (r to v) }
                    onSensorSample(r, v)
                }
            }
            acts.forEach { r ->
                val s = TinyIoTApi.fetchLatestCin("TinyIoT/$ae/Actuators/$r")
                if (s != null) _actuatorValues.update { it + (r to s) }
            }
        }
    }
}