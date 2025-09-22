package com.example.tiny

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tiny2.ui.theme.TInyTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavController
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.tiny2.components.SensorLineChart
import androidx.compose.animation.AnimatedVisibility
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import com.example.tiny2.network.TinyIoTApi
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.example.tiny2.monitor.DeviceMonitorViewModel
import com.example.tiny2.monitor.RegisteredDevicesStore
import kotlinx.coroutines.delay
import com.example.tiny2.monitor.ResourceTree
import com.example.tiny2.monitor.ResourceTreeStore
import com.example.tiny2.data.entities.CntDefEntity
import android.location.Geocoder
import java.util.Locale
import androidx.compose.runtime.DisposableEffect
import com.example.tiny2.monitor.SensorDef as UiSensorDef
import com.example.tiny2.monitor.ActDef as UiActDef
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Info



@Composable
fun LocationLine(lat: Double, lng: Double) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lat, lng) {
        runCatching {
            val g = Geocoder(ctx, Locale.getDefault())
            val list = g.getFromLocation(lat, lng, 1)
            addr = list?.firstOrNull()?.getAddressLine(0)
        }
    }

    Text("Location: ${addr ?: "${lat}, ${lng}"}")
}

@Composable
fun LocationText(lat: Double, lng: Double) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lat, lng) {
        runCatching {
            val g = Geocoder(ctx, Locale.getDefault())
            val list = g.getFromLocation(lat, lng, 1)
            addr = list?.firstOrNull()?.getAddressLine(0)
        }
    }

    Text(
        text = addr ?: "$lat, $lng",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}

private fun msText(ms: Long): String =
    if (ms < 1000) "${ms} ms" else String.format("%.1f s", ms / 1000.0)


// 센서 remote → UI 표기용 canonical
private fun sensorKeyForUi(remote: String) = when (remote.lowercase()) {
    "temperature", "temp" -> "TEMPERATURE"
    "humid", "humid1"     -> "HUMID"
    "soil"                -> "SOIL"
    "co2"                 -> "CO2"
    else                  -> remote.uppercase()
}

// 액추에이터 remote → UI 표기용 canonical
private fun actuatorKeyForUi(remote: String) = when (remote.lowercase()) {
    "fan", "fan1", "fan2" -> "FAN"
    "door"                 -> "DOOR"
    "led"                  -> "LED"
    "water"                -> "WATER"
    else                   -> remote.uppercase()
}

data class TinyFarmData(
    val name: String,
    val location: String,
    val sensors: Map<String, Float>,      // 예: temp, humi, co2...
    val actuators: Map<String, String>,    // 예: water -> ON, fan -> OFF
    val lat: Double,       // 위도
    val lng: Double,       // 경도
    val lastUpdated: String,
    val temperatureHistory: List<Float>,
    val humidityHistory: List<Float>
)

sealed class Screen(val route: String) {
    object Main : Screen("main")

    object DeviceDetail : Screen("device_detail/{deviceName}") {
        fun createRoute(deviceName: String) = "device_detail/$deviceName"
    }

    object SensorDetail : Screen("sensor_detail/{sensorType}/{deviceName}") {
        fun createRoute(sensorType: String, deviceName: String) =
            "sensor_detail/$sensorType/$deviceName"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TInyTheme {
                val navController = rememberNavController()
                val deviceListState = remember { mutableStateOf(emptyList<TinyFarmData>()) }

                // 저장소 인스턴스
                val ctx = LocalContext.current
                val treeStore = remember(ctx) { ResourceTreeStore(ctx) }

                val regStore = remember(ctx) { RegisteredDevicesStore(ctx) }

                val appCtx = LocalContext.current
                val db = remember(appCtx) { com.example.tiny2.data.db.AppDatabase.get(appCtx) }
                val cntRepo = remember(db) { com.example.tiny2.repository.CntRepository(db.cntDefDao()) }

                // DataStore에 저장된 AE 집합을 State로 구독
                val registeredAes by regStore.registeredAEs.collectAsState(initial = emptySet())

                // 목록이 바뀔 때마다 서버에서 상세 받아 리스트 재구성
                LaunchedEffect(registeredAes) {
                    if (registeredAes.isEmpty()) {
                        deviceListState.value = emptyList()
                        return@LaunchedEffect
                    }
                    val fetched = mutableListOf<TinyFarmData>()
                    for (ae in registeredAes) {
                        runCatching {
                            val tree = TinyIoTApi.fetchResourceTree(ae)
                            // 기존 파일 스토어 유지
                            treeStore.save(ae, tree)
                            // ✅ DB에 치환 저장
                            cntRepo.replaceByTree(ae, tree)
                        }
                        TinyIoTApi.fetchTinyIoTDetail(ae)?.let { fetched += it }
                    }
                    deviceListState.value = fetched.distinctBy { it.name }
                }

//                자동 로딩
//                LaunchedEffect(Unit) {
//                    val result = TinyIoTApi.fetchTinyIoTResourceTree()
//                    Log.d("MAIN_SCREEN", "🎯 가져온 디바이스 수: ${result.size}")
//                    deviceListState.value = result
//                }

                NavHost(
                    navController = navController,
                    startDestination = Screen.Main.route
                ) {
                    // 📌 메인
                    composable(Screen.Main.route) {
                        MainScreen(navController = navController, deviceListState = deviceListState, regStore = regStore)
                    }

                    // 📌 디바이스 상세
                    composable(
                        route = Screen.DeviceDetail.route,
                        arguments = listOf(
                            navArgument("deviceName") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val deviceName = backStackEntry.arguments?.getString("deviceName") ?: ""
                        val device = deviceListState.value.find { it.name == deviceName }

                        Log.d("NAV", "✅ 선택한 deviceName: $deviceName")
                        Log.d("NAV", "✅ 검색된 device: ${device?.name}")

                        if (device != null) {
                            DeviceDetailScreen(
                                deviceParam = device,
                                navController = navController
                            )
                        } else {
                            Text("❌ 디바이스를 찾을 수 없습니다.")
                        }
                    }

                    // 📌 센서 상세
                    composable(
                        route = Screen.SensorDetail.route,
                        arguments = listOf(
                            navArgument("sensorType") { type = NavType.StringType },
                            navArgument("deviceName") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val sensorType = backStackEntry.arguments?.getString("sensorType") ?: ""
                        val deviceName = backStackEntry.arguments?.getString("deviceName") ?: ""

                        val device = deviceListState.value.find { it.name == deviceName }

                        if (device != null) {
                            SensorDetailScreen(
                                sensorType = sensorType,
                                device = device,
                                navController = navController
                            )

                        } else {
                            Text("⚠️ 기기 정보가 없습니다.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    navController: NavController,
    deviceListState: MutableState<List<TinyFarmData>>,
    regStore: RegisteredDevicesStore
) {
    val scope = rememberCoroutineScope()

    // UI 상태
    val showAePickerDialog = remember { mutableStateOf(false) }
    val isFetchingAEs = remember { mutableStateOf(false) }
    val aeList = remember { mutableStateListOf<String>() }
    val fetchError = remember { mutableStateOf<String?>(null) }

    val deviceList = deviceListState.value

    val registeredSet by regStore.registeredAEs.collectAsState(initial = emptySet())

    var aeToDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        // 로고 + 타이틀
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "TinyIoT Logo",
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "TinyIoT Connect",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }

        // 디바이스 개수
        Text(
            text = "Registered smart farm: ${registeredSet.size}",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
        )

        Button(
            onClick = {
                // AE 목록 받아오기 (수동)
                scope.launch {
                    isFetchingAEs.value = true
                    fetchError.value = null
                    aeList.clear()
                    try {
                        val fetched = TinyIoTApi.fetchAvailableAEs()
                        aeList.addAll(fetched)
                        showAePickerDialog.value = true
                    } catch (e: Exception) {
                        fetchError.value = "Failed to fetch Smart Farm list."
                    } finally {
                        isFetchingAEs.value = false
                    }
                }
            },
            enabled = !isFetchingAEs.value,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303F9F))
        ) {
            if (isFetchingAEs.value) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Add Smart Farm", color = Color.White)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 리스트
        if (deviceList.isEmpty()) {
            Text(
                "NO DEVICE",
                color = Color.Gray,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            LazyColumn {
                items(deviceList) { device ->
                    TinyFarmCard(
                        farm = device,
                        onClick = {
                            navController.navigate(
                                Screen.DeviceDetail.createRoute(device.name)
                            )
                        },
                        onUnsubscribeRequest = { name ->
                            aeToDelete = name
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (aeToDelete != null) {
        AlertDialog(
            onDismissRequest = { aeToDelete = null },
            title = { Text("Remove Smart Farm") },
            text = { Text("Do you want to unsubscribe '${aeToDelete}'?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        regStore.removeAE(aeToDelete!!)   // DataStore에서 제거
                        aeToDelete = null                 // 닫기
                    }
                }) { Text("Unsubscribe") }
            },
            dismissButton = {
                TextButton(onClick = { aeToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // AE 선택 다이얼로그
    if (showAePickerDialog.value) {
        AlertDialog(
            onDismissRequest = { showAePickerDialog.value = false },
            containerColor = Color.White,
            title = { Text("Select Smart Farm", color = Color.Black) },
            text = {
                when {
                    fetchError.value != null -> {
                        Text(fetchError.value!!, color = Color.Red)
                    }
                    aeList.isEmpty() -> {
                        Text("No connected Smart Farm.", color = Color.Gray)
                    }
                    else -> {
                        val currentCards = deviceListState.value.map { it.name }.toSet()
                        val filtered = aeList.filter { it !in registeredSet && it !in currentCards }

                        if (filtered.isEmpty()) {
                            Text("No new smart farms available.", color = Color.Gray)
                        } else {
                            Column(Modifier.padding(top = 4.dp)) {
                                filtered.forEach { ae ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .background(
                                                Color(0xFFF1F1F1),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                // 선택하면 카드 추가 대신 DataStore에만 등록
                                                scope.launch {
                                                    regStore.addAE(ae)
                                                    showAePickerDialog.value = false
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(ae, color = Color.Black, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAePickerDialog.value = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun formatHms(ts: Long?): String =
    ts?.let {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceParam: TinyFarmData,
    navController: NavController,
) {
    var uiDevice by remember { mutableStateOf(deviceParam) }

    val ctx = LocalContext.current
    val treeStore = remember(ctx) { ResourceTreeStore(ctx) }
    val db = remember(ctx) { com.example.tiny2.data.db.AppDatabase.get(ctx) }
    val cntRepo = remember(db) { com.example.tiny2.repository.CntRepository(db.cntDefDao()) }
    val vm: DeviceMonitorViewModel = viewModel()

    var lastRefreshedAt by rememberSaveable { mutableStateOf<Long?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.resume()  // 구현 돼 있으면 재개, 없으면 no-op
                Lifecycle.Event.ON_STOP  -> vm.pause()   // 구현 돼 있으면 일시정지, 없으면 no-op
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // DB에서 현재 AE의 센서/액추 목록 실시간 구독
    val sensorDefsDb by cntRepo.observeSensors(deviceParam.name).collectAsState(emptyList())
    val actDefsDb    by cntRepo.observeActuators(deviceParam.name).collectAsState(emptyList())

    val sensorDefs = sensorDefsDb.map { UiSensorDef(it.canonical, it.remote, it.intervalMs ?: 60_000L) }
    val actDefs    = actDefsDb.map    { UiActDef(it.canonical,    it.remote) }

    val sensorMap by vm.sensorValues.collectAsState()
    val actMap    by vm.actuatorValues.collectAsState()

    val sensorsState = remember { mutableStateMapOf<String, Float>() }
    val actsState    = remember { mutableStateMapOf<String, String>() }

    val latMap  by vm.actLatency.collectAsState()   // ⬅️ 마지막 측정 결과
    val busySet by vm.actBusy.collectAsState()

    LaunchedEffect(actMap) {
        // 서버에서 최신 값이 들어오면 로컬 임시 상태를 덮어써서 화면과 동기화
        actMap.forEach { (k, v) -> actsState[k] = v }
    }


    // ✅ 스코프는 여기서 딱 한 번!
    val scope = rememberCoroutineScope()

    // 진입/이탈 시 폴링 시작/정지
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    var pendingLed by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val context = LocalContext.current
    var tree by remember { mutableStateOf<ResourceTree?>(null) }

    LaunchedEffect(deviceParam.name) {
        // 1) 서버에서 현재 AE의 리소스 트리 가져오기 (Sensors / Actuators)
        val fresh = TinyIoTApi.fetchResourceTree(deviceParam.name)   // <-- "Actuators" 경로 사용하는 버전

        // 2) DB를 서버 기준으로 통째로 교체
        cntRepo.replaceByTree(deviceParam.name, fresh)

        // 3) 모니터 폴링 시작 (값은 나중에 들어와도 '카드'는 즉시 보임)
        vm.stop()
        vm.start(deviceParam.name, fresh)

        lastRefreshedAt = System.currentTimeMillis()
    }

    LaunchedEffect(tree) {
        // 센서 인터벌 설정 + 초깃값 한 번 채우기
        tree?.sensors.orEmpty().forEach { def ->
            vm.registerSensorInterval(uiDevice.name, def.remote, def.intervalMs)
            // 초기값 씨드
            TinyIoTApi.fetchLatestCinFloat("TinyIoT/${uiDevice.name}/Sensors/${def.remote}")
                ?.let { v -> sensorsState[def.remote] = v }
        }

        // 액추에이터 초기값 씨드
        tree?.actuators.orEmpty().forEach { def ->
            TinyIoTApi.fetchLatestCin("TinyIoT/${uiDevice.name}/Actuators/${def.remote}")
                ?.let { v -> actsState[def.remote] = v }
        }
    }

    LaunchedEffect(pendingLed) {
        pendingLed?.let { (remote, v) ->
            delay(250) // 슬라이더 드래그 디바운스
            vm.commandActuatorMeasured(
                ae = uiDevice.name,
                remote = remote,
                value = v.toString()
            )
            pendingLed = null
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val expandedActuator = remember { mutableStateOf<String?>(null) }

    var addableSensors by remember { mutableStateOf<List<String>>(emptyList()) }
    var addableActs    by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(uiDevice.name) {
        tree = treeStore.load(uiDevice.name)
    }

    LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            val (allSensors, allActs) = TinyIoTApi.fetchAddableCnts(uiDevice.name)
            addableSensors = allSensors
            addableActs    = allActs
        }
    }

    LaunchedEffect(actDefsDb) {
        actDefsDb.forEach { def ->
            if (actsState[def.remote] == null) {
                actsState[def.remote] = defaultActuatorInitial(def.canonical)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = {
                    Text(
                        text = "Smart Farm Detail: ${uiDevice.name}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.Black
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 마지막 새로고침 시각 표시 (아이콘 왼쪽)
                        Text(
                            text = formatHms(lastRefreshedAt),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )

                        IconButton(onClick = {
                            scope.launch {
                                val fresh = TinyIoTApi.fetchResourceTree(uiDevice.name)
                                cntRepo.replaceByTree(uiDevice.name, fresh)
                                sensorsState.keys.retainAll(fresh.sensors.map { it.remote }.toSet())
                                actsState.keys.retainAll(fresh.actuators.map { it.remote }.toSet())
                                vm.stop()
                                vm.start(uiDevice.name, fresh)
                                vm.forceRefreshOnce(uiDevice.name,
                                    fresh.sensors.map{it.remote}, fresh.actuators.map{it.remote})
                                lastRefreshedAt = System.currentTimeMillis()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Refresh",
                                tint = Color.Black
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = Color.White)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        // 1) 최신 트리 갱신
                        tree = treeStore.load(uiDevice.name)
                        // 2) 서버에서 “전체” CNT 목록 가져오기
                        val (allSensors, allActs) = TinyIoTApi.fetchAddableCnts(uiDevice.name)
                        addableSensors = allSensors
                        addableActs = allActs
                        // 3) 다이얼로그 열기
                        showAddDialog = true
                    }
                },
                containerColor = Color(0xFF303F9F),   // 남색
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text  = { Text("Add Items") }
            )
        }
    ) { innerPadding ->

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // 위치 텍스트
                LocationLine(lat = uiDevice.lat, lng = uiDevice.lng)
                Spacer(Modifier.height(16.dp))

                // 센서
                Text("Sensor Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.height(8.dp))

                sensorDefs.forEach { def ->
                    val value = sensorMap[def.remote] ?: sensorsState[def.remote]
                    val shown = value?.toString() ?: "—"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .clickable {
                                navController.navigate(
                                    Screen.SensorDetail.createRoute(canonicalKey(def.canonical), uiDevice.name)
                                )
                            },
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = sensorIcon(def.canonical),
                                contentDescription = null,
                                tint = sensorColor(def.canonical),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "${def.remote}: $value",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Divider(modifier = Modifier.padding(horizontal = 8.dp), color = Color.LightGray, thickness = 1.dp)
                Spacer(Modifier.height(16.dp))

                // 액추에이터
                Text(
                    text = "Actuator Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Black
                )

                actDefs.forEach { def ->
                    val isExpanded = expandedActuator.value == def.remote
                    val label = def.canonical                  // 화면에 보여줄 이름
                    val remote = def.remote          // 상태맵 키 (remote 기반)
                    val current = actMap[remote] ?: actsState[remote] ?: ""

                    val innerPad   = 16.dp
                    val iconSize   = 24.dp
                    val gap        = 12.dp
                    val lastTop    = (-6).dp      // ← LED에서 쓰던 y 오프셋과 똑같이

                    val ledLastLineMod = Modifier
                        .padding(start = innerPad + iconSize + gap)  // LED의 텍스트 시작 x
                        .offset(y = lastTop)

                    if (label.equals("LED", true)) {
                        val ledValueState = current?.toFloatOrNull() ?: 5f
                        var ledValue by remember(remote, current) { mutableFloatStateOf(ledValueState) }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                                .clickable { expandedActuator.value = if (isExpanded) null else remote },
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = actuatorIcon(label),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = actuatorColor(label)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // 🔽 제목: CANONICAL (remote)
                                    Text(
                                        " $remote Brightness: ${ledValue.roundToInt()}",
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.Black
                                    )
                                }

                                when {
                                    busySet.contains(remote) -> {
                                        Spacer(Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "⏳ sending…",
                                                color = Color(0xFF1976D2),
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    else -> {
                                        latMap[remote]?.let { last ->
                                            Spacer(Modifier.height(6.dp))
                                            val okColor = if (last.ok) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                            val txt = if (last.ok) "✔ ${msText(last.totalMs)}" else "✖ timeout ${msText(last.totalMs)}"
                                            Text("Last action: $txt", fontSize = 12.sp, color = okColor)
                                        }
                                    }
                                }
                                AnimatedVisibility(visible = isExpanded) {
                                    Column {
                                        Spacer(Modifier.height(12.dp))
                                        Slider(
                                            value = ledValue,
                                            onValueChange = {
                                                ledValue = it
                                                actsState[remote] = it.toInt().toString()
                                                pendingLed = remote to it.toInt()
                                            },
                                            valueRange = 0f..10f, steps = 9,
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF303F9F),
                                                activeTrackColor = Color(0xFF303F9F),
                                                inactiveTrackColor = Color(0xFFBDBDBD)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                                .clickable {
                                    val base = (actsState[remote] ?: actMap[remote]).orEmpty()
                                    val newState =
                                        if (label.equals("fan", true) || label.equals("water", true)) {
                                            if (base.equals("ON", true)) "OFF" else "ON"
                                        } else if (label.equals("door", true)) {
                                            if (base.equals("OPENED", true) || base.equals("OPEN", true)) "Closed" else "Opened"
                                        } else {
                                            base.ifEmpty { defaultActuatorInitial(label) }
                                        }
                                    actsState[remote] = newState
                                    vm.commandActuatorMeasured(uiDevice.name, remote, newState)
                                },
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            // ★ LED와 동일한 레이아웃 기준
                            Column(Modifier.fillMaxWidth().padding(innerPad)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = actuatorIcon(label),
                                        contentDescription = null,
                                        modifier = Modifier.size(iconSize),
                                        tint = actuatorColor(label)
                                    )
                                    Spacer(Modifier.width(gap)) // ★ 8.dp
                                    Text(
                                        text = "$remote: ${(actsState[remote] ?: current).ifEmpty { defaultActuatorInitial(label) }}",
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.Black
                                    )
                                }

                                Spacer(Modifier.height(6.dp)) // ★ LED와 동일한 세로 간격

                                if (busySet.contains(remote)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Sending…", fontSize = 12.sp, color = Color(0xFF1976D2))
                                    }
                                } else {
                                    latMap[remote]?.let { last ->
                                        val okColor = if (last.ok) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                        val txt = if (last.ok) "Last action: ✔ ${msText(last.totalMs)}"
                                        else "Last action: ✖ timeout ${msText(last.totalMs)}"
                                        Text(txt, fontSize = 12.sp, color = okColor)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = Color.LightGray, thickness = 1.dp)
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "📍Location Info",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Black
                )

                Log.d("MAP_DEBUG", "detail -> ${uiDevice.name} lat=${uiDevice.lat}, lng=${uiDevice.lng}")

                DeviceMapOSM(lat = uiDevice.lat, lng = uiDevice.lng, title = uiDevice.name)
            }
        }

        val existingSensorRemotes = remember(sensorDefsDb) { sensorDefsDb.map { it.remote } }
        val existingActRemotes    = remember(actDefsDb)    { actDefsDb.map { it.remote } }

        val sensorOptions = remember(addableSensors, existingSensorRemotes) {
            addableSensors.filter { it !in existingSensorRemotes }
        }
        val actOptions = remember(addableActs, existingActRemotes) {
            addableActs.filter { it !in existingActRemotes }
        }

        // ── 추가 다이얼로그 ─────────────────────────────────────
        if (showAddDialog) {
            AddThingDialogFiltered(
                device = uiDevice,
                sensorOptions = sensorOptions,   // ← 필터된 것만
                actOptions    = actOptions,      // ← 필터된 것만
                showActIntervals = false,
                onDismiss = { showAddDialog = false },
                onConfirm = { sensorsAdded, actsAdded, sIntervals, _ ->
                    isWorking = true
                    scope.launch {
                        try {
                            // 1) 서버에 CNT 생성 (409 OK)
                            TinyIoTApi.createSensors(uiDevice.name, sensorsAdded)
                            TinyIoTApi.createActuators(uiDevice.name, actsAdded)

                            // 2) 초기값 시드
                            actsAdded.forEach { r ->
                                val label = actuatorKeyForUi(r)
                                val seeded = TinyIoTApi.seedActuatorDefault(uiDevice.name, r, label)
                                if (seeded.isNotEmpty()) actsState[r] = seeded
                            }
                            sensorsAdded.forEach { r ->
                                TinyIoTApi.postCinText("TinyIoT/${uiDevice.name}/Sensors/$r", "0")
                                sensorsState[r] = 0f
                            }

                            // 3) 최신값 1회 읽기
                            actsAdded.forEach { r ->
                                TinyIoTApi.fetchLatestCinText("TinyIoT/${uiDevice.name}/Actuators/$r")
                                    ?.let { actsState[r] = it }
                            }
                            sensorsAdded.forEach { r ->
                                TinyIoTApi.fetchLatestCinFloat("TinyIoT/${uiDevice.name}/Sensors/$r")
                                    ?.let { sensorsState[r] = it }
                            }

                            // 4) 🔥 서버 기준 트리 재발견 → DB 통째로 교체(핵심)
                            val fresh = TinyIoTApi.fetchResourceTree(uiDevice.name)
                            cntRepo.replaceByTree(uiDevice.name, fresh)   // ⬅️ 이 줄이 핵심

                            sensorsState.keys.retainAll(fresh.sensors.map { it.remote }.toSet())
                            actsState.keys.retainAll(fresh.actuators.map { it.remote }.toSet())

                            // 5) Compose/VM 동기화
                            tree = fresh
                            vm.stop()
                            vm.start(uiDevice.name, fresh)
                            vm.forceRefreshOnce(
                                ae = uiDevice.name,
                                sensors = fresh.sensors.map { it.remote },
                                acts    = fresh.actuators.map { it.remote }
                            )

                        } finally {
                            isWorking = false
                            showAddDialog = false
                        }
                    }
                }
            )
        }

        if (isWorking) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}


// 선택 다이얼로그
@Composable
fun AddThingDialogFiltered(
    device: TinyFarmData,
    sensorOptions: List<String>,   // 이미 필터된 remote 목록
    actOptions: List<String>,
    showActIntervals: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (
        sensorsAdded: List<String>,
        actsAdded: List<String>,
        sensorIntervals: Map<String, Long>,
        actIntervals: Map<String, Long>
    ) -> Unit
) {
    // 선택 상태 + 인터벌 상태
    val selSensors = remember { mutableStateMapOf<String, Boolean>() }
    val selActs = remember { mutableStateMapOf<String, Boolean>() }
    val intSensorsMs = remember { mutableStateMapOf<String, Long>() }
    val intActsMs = remember { mutableStateMapOf<String, Long>() }

    // 프리셋
    val presets = listOf(
        5_000L to "Every 5s",
        10_000L to "Every 10s",
        30_000L to "Every 30s",
        60_000L to "Every 60s",
        300_000L to "Every 5 min"
    )

    // 기본값은 넘어온 옵션 기준으로만 세팅
    LaunchedEffect(sensorOptions, actOptions) {
        sensorOptions.forEach { s -> if (intSensorsMs[s] == null) intSensorsMs[s] = 60_000L }
        actOptions.forEach { a -> if (intActsMs[a] == null) intActsMs[a] = 3_000L }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Add items", color = Color.Black, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // Sensors
                Text("Sensors", fontWeight = FontWeight.Bold, color = Color.Black)
                if (sensorOptions.isEmpty()) {
                    Text("No new sensors found.", color = Color.Gray)
                } else {
                    sensorOptions.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = selSensors[s] == true,
                                onCheckedChange = { selSensors[s] = it }
                            )
                            val label = sensorKeyForUi(s)           // 예: "HUMID"
                            Text("$label  ($s)", modifier = Modifier.weight(1f))

                            // interval selector
                            var expanded by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(presets.first {
                                    it.first == (intSensorsMs[s] ?: 60_000L)
                                }.second)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }) {
                                presets.forEach { (ms, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { intSensorsMs[s] = ms; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Actuators
                Text("Actuators", fontWeight = FontWeight.Bold, color = Color.Black)
                if (actOptions.isEmpty()) {
                    Text("No new actuators found.", color = Color.Gray)
                } else {
                    actOptions.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = selActs[a] == true,
                                onCheckedChange = { selActs[a] = it }
                            )
                            val label = actuatorKeyForUi(a)
                            Text("$label  ($a)", modifier = Modifier.weight(1f))

                            if (showActIntervals) {
                                var expanded by remember { mutableStateOf(false) }
                                OutlinedButton(onClick = { expanded = true }) {
                                    val cur = intActsMs[a] ?: 3_000L
                                    Text(if (cur < 1000) "$cur ms" else "Every ${cur / 1000}s")
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }) {
                                    listOf(2_000L, 3_000L, 5_000L, 10_000L).forEach { ms ->
                                        DropdownMenuItem(
                                            text = { Text("Every ${ms / 1000}s") },
                                            onClick = { intActsMs[a] = ms; expanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sensors = selSensors.filter { it.value }.keys.toList()
                val acts = selActs.filter { it.value }.keys.toList()
                val sIntervals = sensors.associateWith { intSensorsMs[it] ?: 60_000L }
                val aIntervals = acts.associateWith { intActsMs[it] ?: 3_000L }
                onConfirm(sensors, acts, sIntervals, aIntervals)
            }) { Text("Add", color = Color(0xFF303F9F)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

private fun defaultActuatorInitial(apiType: String): String = when (apiType) {
    "LED" -> "0"
    "Fan" -> "OFF"
    "Door" -> "Closed"
    "Water" -> "OFF"
    else -> ""
}

@Composable
fun DeviceMapOSM(lat: Double, lng: Double, title: String = "Smart Farm") {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // osmdroid 기본 설정 + userAgent (필수!)
    LaunchedEffect(Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(16.0)
        }
    }

    // 라이프사이클 연동 (메모리/성능)
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp) // 높이 꼭 지정!
            .clip(RoundedCornerShape(12.dp)),
        factory = { mapView },
        update = { mv ->
            val p = GeoPoint(lat, lng)

            // 마커 갱신
            mv.overlays.removeAll { it is Marker }
            mv.overlays.add(Marker(mv).apply {
                position = p
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                this.title = title
            })

            mv.controller.setCenter(p)
            mv.invalidate()
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailScreen(
    sensorType: String,
    device: TinyFarmData,
    navController: NavController
) {
    val vm: DeviceMonitorViewModel = viewModel()
    val key = pickSensorKey(sensorType, device.sensors)

    val remote = remember(sensorType, device.sensors) {
        device.sensors.keys.firstOrNull { it.equals(sensorType, ignoreCase = true) }
            ?: sensorType
    }

    val liveMap by vm.sensorValues.collectAsState()
    val currentValue: Float =
        liveMap[remote]
            ?: liveMap.entries.firstOrNull { it.key.equals(remote, true) }?.value
            ?: device.sensors[remote]
            ?: device.sensors.entries.firstOrNull { it.key.equals(remote, true) }?.value
            ?: 0f

    val history by vm.historyOf(remote).collectAsState(initial = emptyList())

    LaunchedEffect(history) {
        Log.d("HIST_UI", "history size=${history.size}, values=$history")
    }

    val sensorValue = remember(device.sensors, key) {
        device.sensors[key]
            ?: device.sensors.entries
                .firstOrNull { it.key.lowercase() == key.lowercase() }
                ?.value
            ?: 0f
    }

    LaunchedEffect(remote, device.name) {
        vm.backfillHistory(ae = device.name, remote = remote, points = 12)
    }

    val scrollState = rememberScrollState()
    Log.d("SENSOR_DETAIL", "센서 타입: $sensorType, 값: $sensorValue → 상태: ${getSensorStatus(sensorType, sensorValue)}")

    val samples by vm.historyOf(key).collectAsState(initial = emptyList())

    val historyValues by vm.historyOf(key).collectAsState(initial = emptyList())

    val chartValues = remember(key, history) {
        if (historyValues.isNotEmpty()) historyValues else generateDummySeries(key)
    }

    var showGuide by rememberSaveable { mutableStateOf(false) }

    var lastRefreshedAt by rememberSaveable {
        mutableStateOf(System.currentTimeMillis())
    }

    fun formatHms(ts: Long?): String =
        ts?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) }
            ?: "--:--:--"

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = {
                    Text(text = "$key 센서", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatHms(lastRefreshedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        IconButton(onClick = {
                            vm.refreshOne(device.name, remote)
                            lastRefreshedAt = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Default.Sync, contentDescription = "Refresh", tint = Color.Black)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.Black
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = Color.White,
                    scrolledContainerColor = Color.White
                ),
                modifier = Modifier
                    .background(Color.White)
                    .drawBehind {}
            )
        },
        content = { innerPadding ->

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .background(Color.White)
                    .padding(16.dp)
            ) {

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Current Value: ${"%.1f".format(currentValue)} ${getUnit(remote)}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(8.dp))

                val status = getSensorStatus(sensorType.lowercase(), sensorValue)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Status: $status",
                        fontSize = 18.sp,
                        color = when (status) {
                            "Normal" -> Color(0xFF2E7D32)
                            "Low"    -> Color(0xFF1976D2)
                            "High"   -> Color(0xFFD32F2F)
                            else     -> Color.Gray
                        },
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(onClick = { showGuide = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Status ranges",
                            tint = Color(0xFF616161)
                        )
                    }
                }

                // ➌ 상태 기준(범위) 안내 다이얼로그
                if (showGuide) {
                    AlertDialog(
                        onDismissRequest = { showGuide = false },
                        title = { Text("Status Ranges") },
                        text = {
                            Column {
                                when (sensorType.lowercase()) {
                                    "temperature" -> {
                                        Text("• Low    : < 15°C")
                                        Text("• Normal : ≤ 30°C")
                                        Text("• High   : > 30°C")
                                    }
                                    "humid", "humidity" -> {
                                        Text("• Low    : < 30%")
                                        Text("• Normal : ≤ 70%")
                                        Text("• High   : > 70%")
                                    }
                                    "soil" -> {
                                        Text("• Low    : < 20%")
                                        Text("• Normal : ≤ 60%")
                                        Text("• High   : > 60%")
                                    }
                                    "co2" -> {
                                        Text("• Normal : ≤ 1000 ppm")
                                        Text("• High   : > 1000 ppm")
                                    }
                                    else -> Text("Unknown sensor type")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showGuide = false }) { Text("OK") }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.LightGray, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trend Chart", fontSize = 16.sp)
                }

                if (samples.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("No data yet. Tap refresh to record the first sample.", color = Color.Gray)
                } else {
                    SensorLineChart(
                        sensorType = sensorType,
                        values = history,
                        intervalMs = vm.intervalMsFor(remote)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.LightGray, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = Color(0xFF7E57C2), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Statistics", fontWeight = FontWeight.Bold)
                }

                if (samples.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("📛 Unable to retrieve statistics.", color = Color.Red)
                } else {
                    val (avg, max, min) = vm.statsOf(remote).also {
                        Log.d("HIST_UI", "stats avg=${it.first}, max=${it.second}, min=${it.third}")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(String.format("Average: %.1f %s", avg, getUnit(key)))
                    Text(String.format("Max: %.1f %s", max, getUnit(key)))
                    Text(String.format("Min: %.1f %s", min, getUnit(key)))
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StatusRow(
    sensorType: String,       // "Temperature" / "Humidity" / "Soil" / "CO2" ...
    currentValue: Float       // 현재 값
) {
    val status = remember(sensorType, currentValue) {
        getSensorStatus(sensorType, currentValue)
    }

    var showGuide by remember { mutableStateOf(false) }
    val (guideTitle, guideBody) = remember(sensorType) { statusGuide(sensorType) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    ) {
        Text(
            text = "Status: $status",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF333333)
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = { showGuide = true }) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Status ranges",
                tint = Color(0xFF616161)
            )
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(guideTitle, fontWeight = FontWeight.Bold) },
            text  = { Text(guideBody) },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text("OK")
                }
            }
        )
    }
}

private fun generateDummySeries(sensorType: String): List<Float> {
    // 센서 타입별 기본 중심값을 다르게
    val base = when (sensorType.lowercase()) {
        "temperature" -> 26f
        "humid"       -> 55f
        "co2"         -> 420f
        "soil"        -> 350f
        else          -> 10f
    }

    // 20포인트짜리 시퀀스 (약간의 노이즈와 완만한 변화)
    return List(20) { i ->
        val wave = kotlin.math.sin(i / 3f) * 1.2f          // 완만한 파형
        val noise = (-8..8).random() / 10f                 // 작은 노이즈
        (base + wave + noise).coerceAtLeast(0f)
    }
}


fun actuatorColor(type: String): Color {
    return when (type.uppercase()) {
        "WATER" -> Color(0xFF6495ED)   // 진한 파랑 (물)
        "LED" -> Color(0xFFFFC107)     // 밝은 노랑 (전구)
        "FAN" -> Color(0xFF66CDAA)     // 청록색 (선풍기 느낌)
        "DOOR" -> Color(0xFF8B4513)    // 진한 갈색 (문)
        else -> Color.Gray
    }
}

fun actuatorIcon(type: String): ImageVector {
    return when (type.uppercase()) {
        "WATER" -> Icons.Default.InvertColors     // 물방울 아이콘
        "LED" -> Icons.Default.Lightbulb          // 전구
        "FAN" -> Icons.Default.Cached              // 회전 느낌
        "DOOR" -> Icons.Default.MeetingRoom        // 문
        else -> Icons.Default.Build                // 기본 아이콘
    }
}

fun sensorColor(sensorType: String): Color {
    return when (sensorType.uppercase()) {
        "TEMPERATURE" -> Color(0xFFEF6C00)
        "HUMIDITY" -> Color(0xFF42A5F5)
        "CO2" -> Color(0xFF66BB6A)
        "SOIL" -> Color(0xFF8D6E63)
        else -> Color.Gray
    }
}

fun sensorIcon(sensorType: String): ImageVector {
    return when (sensorType.uppercase()) {
        "TEMPERATURE" -> Icons.Default.Thermostat      // 온도
        "HUMIDITY" -> Icons.Default.WaterDrop       // 습도
        "CO2"  -> Icons.Default.Cloud           // 이산화탄소
        "SOIL" -> Icons.Default.Agriculture     // 토양
        else -> Icons.Default.Sensors           // 기본값
    }
}

fun getSensorStatus(type: String, value: Float): String {
    return when (type.lowercase()) {
        "temperature" -> when {
            value < 15 -> "Low"
            value <= 30 -> "Normal"
            else -> "High"
        }
        "humidity" -> when {
            value < 30 -> "Low"
            value <= 70 -> "Normal"
            else -> "High"
        }
        "soil" -> when {
            value < 20 -> "Low"
            value <= 60 -> "Normal"
            else -> "High"
        }
        "co2" -> if (value <= 1000) "Normal" else "High"
        else -> "Unknown"
    }
}

fun statusGuide(type: String): Pair<String /*title*/, String /*body*/> {
    return when (type.lowercase()) {
        "temperature" -> "Temperature status ranges" to """
            • Low:    < 15°C
            • Normal: 15°C – 30°C
            • High:   > 30°C
        """.trimIndent()

        "Humidity", "humidity" -> "Humidity status ranges" to """
            • Low:    < 30%
            • Normal: 30% – 70%
            • High:   > 70%
        """.trimIndent()

        "soil" -> "Soil moisture status ranges" to """
            • Low:    < 20
            • Normal: 20 – 60
            • High:   > 60
        """.trimIndent()

        "co2" -> "CO₂ status ranges" to """
            • Normal: ≤ 1000 ppm
            • High:   > 1000 ppm
        """.trimIndent()

        else -> "Unknown sensor" to "No guideline is available for this sensor."
    }
}

private fun canonicalKey(t: String) = when (t.lowercase()) {
    "temp", "temperature"   -> "Temperature"
    "humid", "humidity"     -> "Humidity"
    "soil", "moisture"      -> "Soil"
    "co2", "co₂"            -> "CO2"
    else                    -> t
}

fun getUnit(type: String): String {
    return when (type) {
        "Temperature" -> "°C"
        "Humidity" -> "%"
        "Soil" -> ""
        "CO2" -> "ppm"
        else -> ""
    }
}

// MainActivity.kt 맨 아래나 별도 파일(예: SensorUtils.kt)에 top-level 로 추가
private fun pickSensorKey(
    requested: String,
    sensors: Map<String, Float>
): String {
    // 1) 요청 키가 그대로 있으면 그대로
    if (sensors.containsKey(requested)) return requested

    // 2) 습도는 대/소문자 혼재 → 값 있는 "Humidity" 우선
    sensors["Humidity"]?.let { return "Humidity" }
    sensors["humidity"]?.let { return "humidity" }

    // 3) 그 외엔 대소문자 무시 매칭
    sensors.keys.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }

    // 4) 정말 못 찾으면 요청 키 그대로
    return requested
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinyFarmCard(
    farm: TinyFarmData,
    onClick: () -> Unit,
    onUnsubscribeRequest: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(farm.sensors) {
        Log.d("CARD_DEBUG", "farm.sensors keys = ${farm.sensors.keys}")
        Log.d("CARD_DEBUG", "farm.sensors = ${farm.sensors}")
    }

    val temp = farm.sensors["Temperature"]
        ?: farm.temperatureHistory.lastOrNull()
    val humi: Float? = farm.sensors["Humid"]
        ?: farm.sensors["Humidity"]
        ?: farm.sensors["humidity"]
        ?: farm.sensors.entries
            .firstOrNull { it.key.equals("humid", true) || it.key.startsWith("humid", true) }
            ?.value
        ?: farm.humidityHistory.lastOrNull()

    val tempText = temp?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "—"
    val humiText = humi?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "—"

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp) // 👈 안쪽 여백 조절
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = farm.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )

                // ⋮ 버튼 오른쪽 여백 줄이기
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp) // 버튼 자체 크기도 줄일 수 있음
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = { showMenu = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Unsubscribe") },
                        onClick = {
                            showMenu = false
                            onUnsubscribeRequest(farm.name)
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            // 위치
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                LocationText(lat = farm.lat, lng = farm.lng)
            }

            Spacer(Modifier.height(8.dp))

            // 센서 값
            Row {
                Text("🌡 $tempText", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text("💧 $humiText", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp)) // 👈 카드 하단 여백 추가
        }
    }
}





