package com.example.tiny2

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
import android.webkit.WebView
import androidx.compose.ui.text.style.TextAlign



@Composable
fun LocationLine(lat: Double, lng: Double) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lat, lng) {
        runCatching {
            val g = Geocoder(ctx, Locale.ENGLISH)
            val list = g.getFromLocation(lat, lng, 1)
            addr = list?.firstOrNull()?.getAddressLine(0)
        }
    }

    Text("Location: ${addr ?: String.format(Locale.US, "%.6f, %.6f", lat, lng)}")
}

@Composable
fun LocationText(lat: Double, lng: Double) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lat, lng) {
        runCatching {
            val g = Geocoder(ctx, Locale.ENGLISH)
            val list = g.getFromLocation(lat, lng, 1)
            addr = list?.firstOrNull()?.getAddressLine(0)
        }
    }

    Text(
        text = addr ?: String.format(Locale.US, "%.6f, %.6f", lat, lng),
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}

private fun msText(ms: Long): String =
    if (ms < 1000) "${ms} ms" else String.format("%.1f s", ms / 1000.0)


private fun sensorKeyForUi(remote: String) = when (remote.lowercase()) {
    "temperature", "temp" -> "TEMPERATURE"
    "humid", "humid1"     -> "HUMID"
    "soil"                -> "SOIL"
    "co2"                 -> "CO2"
    else                  -> remote.uppercase()
}

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
    val sensors: Map<String, Float>,
    val actuators: Map<String, String>,
    val lat: Double,
    val lng: Double,
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

    object CameraStream : Screen("camera_stream/{deviceName}") {
        fun createRoute(deviceName: String) = "camera_stream/$deviceName"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TInyTheme {
                val navController = rememberNavController()
                val deviceListState = remember { mutableStateOf(emptyList<TinyFarmData>()) }

                val ctx = LocalContext.current
                val treeStore = remember(ctx) { ResourceTreeStore(ctx) }

                val regStore = remember(ctx) { RegisteredDevicesStore(ctx) }

                val appCtx = LocalContext.current
                val db = remember(appCtx) { com.example.tiny2.data.db.AppDatabase.get(appCtx) }
                val cntRepo = remember(db) { com.example.tiny2.repository.CntRepository(db.cntDefDao()) }

                val registeredAes by regStore.registeredAEs.collectAsState(initial = emptySet())

                LaunchedEffect(registeredAes) {
                    if (registeredAes.isEmpty()) {
                        deviceListState.value = emptyList()
                        return@LaunchedEffect
                    }
                    val fetched = mutableListOf<TinyFarmData>()
                    for (ae in registeredAes) {
                        runCatching {
                            val tree = TinyIoTApi.fetchResourceTree(ae)
                            treeStore.save(ae, tree)
                            cntRepo.replaceByTree(ae, tree)
                        }
                        TinyIoTApi.fetchTinyIoTDetail(ae)?.let { fetched += it }
                    }
                    deviceListState.value = fetched.distinctBy { it.name }
                }

                NavHost(
                    navController = navController,
                    startDestination = Screen.Main.route
                ) {
                    composable(Screen.Main.route) {
                        MainScreen(navController = navController, deviceListState = deviceListState, regStore = regStore)
                    }

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
                            Text("❌ Device not found.")
                        }
                    }

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
                            Text("⚠️ Device information not available.")
                        }
                    }

                    @OptIn(ExperimentalMaterial3Api::class)
                    composable(
                        route = Screen.CameraStream.route,
                        arguments = listOf(
                            navArgument("deviceName") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val deviceName = backStackEntry.arguments?.getString("deviceName") ?: ""

                        val cameraUrl = "YOUR_CAMERA_STREAM_URL_HERE"

                        Scaffold(
                            topBar = {
                                SmallTopAppBar(
                                    title = { Text("$deviceName Camera Stream") },
                                    navigationIcon = {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        loadUrl(cameraUrl)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
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

        Text(
            text = "Registered smart farm: ${registeredSet.size}",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
        )

        Button(
            onClick = {
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
                        regStore.removeAE(aeToDelete!!)
                        aeToDelete = null
                    }
                }) { Text("Unsubscribe") }
            },
            dismissButton = {
                TextButton(onClick = { aeToDelete = null }) { Text("Cancel") }
            }
        )
    }

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
                    Text("Close", color=Color.Black)
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
                Lifecycle.Event.ON_START -> vm.resume()
                Lifecycle.Event.ON_STOP  -> vm.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val sensorDefsDb by cntRepo.observeSensors(deviceParam.name).collectAsState(emptyList())
    val actDefsDb    by cntRepo.observeActuators(deviceParam.name).collectAsState(emptyList())

    val sensorDefs = sensorDefsDb.map { UiSensorDef(it.canonical, it.remote, it.intervalMs ?: 60_000L) }
    val actDefs    = actDefsDb.map    { UiActDef(it.canonical,    it.remote) }

    val sensorMap by vm.sensorValues.collectAsState()
    val actMap    by vm.actuatorValues.collectAsState()
    val sensorStringMap by vm.sensorStringValues.collectAsState()

    val inferenceMap by vm.inferenceValues.collectAsState()

    val alertSpecies by vm.unhealthyAlert.collectAsState()

    if (alertSpecies != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissUnhealthyAlert() },
            containerColor = Color.White,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F)) },
            title = { Text("Plant Health Alert") },
            text = {
                Text(
                    text = "'$alertSpecies' has been detected as 'Unhealthy'.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissUnhealthyAlert() }) {
                    Text(
                        text = "OK",
                        color = Color(0xFF303F9F)
                    )
                }
            }
        )
    }

    val sensorsState = remember { mutableStateMapOf<String, Float>() }
    val actsState    = remember { mutableStateMapOf<String, String>() }

    val latMap  by vm.actLatency.collectAsState()
    val busySet by vm.actBusy.collectAsState()

    val gpsDef = remember(sensorDefs) { sensorDefs.find { it.canonical.equals("GPS", true) } }
    val liveGpsString = if (gpsDef != null) sensorStringMap[gpsDef.remote] else null
    val (liveLat, liveLng) = remember(liveGpsString) { parseGpsString(liveGpsString) }


    LaunchedEffect(actMap) {
        actMap.forEach { (k, v) -> actsState[k] = v }
    }

    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    var pendingLed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val context = LocalContext.current
    var tree by remember { mutableStateOf<ResourceTree?>(null) }

    LaunchedEffect(deviceParam.name) {
        val fresh = TinyIoTApi.fetchResourceTree(deviceParam.name)

        if (fresh == null) {
            Log.e("DeviceDetail", "Failed to fetch resource tree for ${deviceParam.name}. Cannot start monitor.")
            vm.stop()
            return@LaunchedEffect
        }

        cntRepo.replaceByTree(deviceParam.name, fresh)
        vm.stop()
        vm.start(deviceParam.name, fresh)
        vm.forceRefreshOnce(
            ae = deviceParam.name,
            sensors = fresh.sensors.map { it.remote },
            acts = fresh.actuators.map { it.remote },
            infs = fresh.inference.map { it.remote }
        )
        lastRefreshedAt = System.currentTimeMillis()
    }

    LaunchedEffect(pendingLed) {
        pendingLed?.let { (remote, v) ->
            delay(250)
            vm.commandActuatorViaMqtt(
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
                        Text(
                            text = formatHms(lastRefreshedAt),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        IconButton(onClick = {
                            scope.launch {
                                val fresh = TinyIoTApi.fetchResourceTree(uiDevice.name)
                                if (fresh == null) return@launch

                                cntRepo.replaceByTree(uiDevice.name, fresh)
                                sensorsState.keys.retainAll(fresh.sensors.map { it.remote }.toSet())
                                actsState.keys.retainAll(fresh.actuators.map { it.remote }.toSet())
                                vm.stop()
                                vm.start(uiDevice.name, fresh)
                                vm.forceRefreshOnce(uiDevice.name,
                                    fresh.sensors.map{it.remote},
                                    fresh.actuators.map{it.remote},
                                    fresh.inference.map { it.remote }
                                )
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
                        tree = treeStore.load(uiDevice.name)
                        val (allSensors, allActs) = TinyIoTApi.fetchAddableCnts(uiDevice.name)
                        addableSensors = allSensors
                        addableActs = allActs
                        showAddDialog = true
                    }
                },
                containerColor = Color(0xFF303F9F),
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
                LocationLine(
                    lat = liveLat ?: uiDevice.lat,
                    lng = liveLng ?: uiDevice.lng
                )
                Spacer(Modifier.height(16.dp))

                Text("Sensor Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.height(8.dp))

                sensorDefs.forEach { def ->
                    val floatValue = sensorMap[def.remote] ?: sensorsState[def.remote]
                    val stringValue = sensorStringMap[def.remote]
                    val shown = floatValue?.toString() ?: stringValue ?: "null"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .clickable {
                                if (def.canonical.equals("GPS", ignoreCase = true)) {
                                    scope.launch { scrollState.animateScrollTo(Int.MAX_VALUE) }
                                } else {
                                    navController.navigate(
                                        Screen.SensorDetail.createRoute(
                                            canonicalKey(def.canonical),
                                            uiDevice.name
                                        )
                                    )
                                }
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
                                text = "${def.remote}: $shown",
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

                Text("Camera Stream", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 8.dp)
                        .clickable {
                            navController.navigate(
                                Screen.CameraStream.createRoute(uiDevice.name)
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
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color(0xFF757575),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Live Camera Feed",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Black
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Divider(modifier = Modifier.padding(horizontal = 8.dp), color = Color.LightGray, thickness = 1.dp)
                Spacer(Modifier.height(16.dp))

                Text("Inference Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(Modifier.height(8.dp))

                val speciesList = inferenceMap["species"]?.second ?: emptyList()
                val healthList = inferenceMap["health"]?.second ?: emptyList()
                val healthTimestamp = inferenceMap["health"]?.first

                if (speciesList.isNotEmpty()) {
                    speciesList.forEach { species ->

                        val healthyCount = healthList.count { it.equals("healthy_$species", ignoreCase = true) }
                        val unhealthyCount = healthList.count { it.equals("unhealthy_$species", ignoreCase = true) }
                        val totalCount = healthyCount + unhealthyCount

                        val isUnhealthy = unhealthyCount > 0
                        val statusColor = if (isUnhealthy) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                        val statusIcon = if (isUnhealthy) Icons.Default.Warning else Icons.Default.Verified

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = statusIcon,
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "$species ($totalCount total)",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Black
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Column(modifier = Modifier.padding(start = 40.dp)) {
                                    Text(
                                        text = "Healthy: $healthyCount",
                                        fontSize = 14.sp,
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = "Unhealthy: $unhealthyCount",
                                        fontSize = 14.sp,
                                        color = if (isUnhealthy) Color(0xFFD32F2F) else Color.Gray
                                    )
                                }

                                if (healthTimestamp != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Last Inference: $healthTimestamp",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(start = 40.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("Health data is loading...", color = Color.Gray)
                }

                Spacer(Modifier.height(24.dp))
                Divider(modifier = Modifier.padding(horizontal = 8.dp), color = Color.LightGray, thickness = 1.dp)
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Actuator Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Black
                )

                actDefs.forEach { def ->
                    val isExpanded = expandedActuator.value == def.remote
                    val label = def.canonical
                    val remote = def.remote
                    val current = actMap[remote] ?: actsState[remote] ?: ""
                    val innerPad   = 16.dp
                    val iconSize   = 24.dp
                    val gap        = 12.dp
                    val lastTop    = (-6).dp
                    val ledLastLineMod = Modifier
                        .padding(start = innerPad + iconSize + gap)
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
                                        if (label.equals("fan", true) || label.equals("water", true) || label.equals("door", true)) {
                                            if (base.equals("ON", true)) "OFF" else "ON"
                                        }  else {
                                            base.ifEmpty { defaultActuatorInitial(label) }
                                        }
                                    actsState[remote] = newState
                                    vm.commandActuatorViaMqtt(uiDevice.name, remote, newState)
                                },
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(innerPad)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = actuatorIcon(label),
                                        contentDescription = null,
                                        modifier = Modifier.size(iconSize),
                                        tint = actuatorColor(label)
                                    )
                                    Spacer(Modifier.width(gap))
                                    Text(
                                        text = "$remote: ${(actsState[remote] ?: current).ifEmpty { defaultActuatorInitial(label) }}",
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.Black
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
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

                DeviceMapOSM(
                    lat = liveLat ?: uiDevice.lat,
                    lng = liveLng ?: uiDevice.lng,
                    title = uiDevice.name
                )
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

        if (showAddDialog) {
            AddThingDialogFiltered(
                device = uiDevice,
                sensorOptions = sensorOptions,
                actOptions    = actOptions,
                showActIntervals = false,
                onDismiss = { showAddDialog = false },
                onConfirm = { sensorsAdded, actsAdded, sIntervals, _ ->
                    isWorking = true
                    scope.launch {
                        try {
                            TinyIoTApi.createSensors(uiDevice.name, sensorsAdded)
                            TinyIoTApi.createActuators(uiDevice.name, actsAdded)
                            actsAdded.forEach { r ->
                                val label = actuatorKeyForUi(r)
                                val seeded = TinyIoTApi.seedActuatorDefault(uiDevice.name, r, label)
                                if (seeded.isNotEmpty()) actsState[r] = seeded
                            }
                            sensorsAdded.forEach { r ->
                                TinyIoTApi.postCinText("TinyIoT/${uiDevice.name}/Sensors/$r", "0")
                                sensorsState[r] = 0f
                            }
                            actsAdded.forEach { r ->
                                TinyIoTApi.fetchLatestCinText("TinyIoT/${uiDevice.name}/Actuators/$r")
                                    ?.let { actsState[r] = it }
                            }
                            sensorsAdded.forEach { r ->
                                TinyIoTApi.fetchLatestCinFloat("TinyIoT/${uiDevice.name}/Sensors/$r")
                                    ?.let { sensorsState[r] = it }
                            }
                            val fresh = TinyIoTApi.fetchResourceTree(uiDevice.name)
                            cntRepo.replaceByTree(uiDevice.name, fresh)
                            sensorsState.keys.retainAll(fresh.sensors.map { it.remote }.toSet())
                            actsState.keys.retainAll(fresh.actuators.map { it.remote }.toSet())
                            tree = fresh
                            vm.stop()
                            vm.start(uiDevice.name, fresh)
                            vm.forceRefreshOnce(
                                ae = uiDevice.name,
                                sensors = fresh.sensors.map { it.remote },
                                acts    = fresh.actuators.map { it.remote },
                                infs = fresh.inference.map { it.remote }
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


@Composable
fun AddThingDialogFiltered(
    device: TinyFarmData,
    sensorOptions: List<String>,
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
    val selSensors = remember { mutableStateMapOf<String, Boolean>() }
    val selActs = remember { mutableStateMapOf<String, Boolean>() }
    val intSensorsMs = remember { mutableStateMapOf<String, Long>() }
    val intActsMs = remember { mutableStateMapOf<String, Long>() }

    val presets = listOf(
        5_000L to "Every 5s",
        10_000L to "Every 10s",
        30_000L to "Every 30s",
        60_000L to "Every 60s",
        300_000L to "Every 5 min"
    )

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
                            val label = sensorKeyForUi(s)
                            Text("$label  ($s)", modifier = Modifier.weight(1f))

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
    "Door" -> "OFF"
    "Water" -> "OFF"
    else -> ""
}

@Composable
fun DeviceMapOSM(lat: Double, lng: Double, title: String = "Smart Farm") {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp)),
        factory = { mapView },
        update = { mv ->
            val p = GeoPoint(lat, lng)

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

private fun summarizeHealthData(list: List<String>?): String {
    if (list == null || list.isEmpty()) return "null"

    val counts = list.groupingBy { it }.eachCount()

    return counts.map { (item, count) -> "$item: $count" }.joinToString(", ")
}

private fun parseGpsString(gps: String?): Pair<Double?, Double?> {
    if (gps == null) return null to null
    val parts = gps.split(',')
    if (parts.size != 2) return null to null
    return parts[0].trim().toDoubleOrNull() to parts[1].trim().toDoubleOrNull()
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

                if (showGuide) {
                    AlertDialog(
                        onDismissRequest = { showGuide = false },
                        containerColor = Color(0xFFF2F2F5),
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
                            TextButton(onClick = { showGuide = false }) {
                                Text(
                                    text = "OK",
                                    color = Color(0xFF303F9F)
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.LightGray, thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trend Chart", fontSize = 16.sp, color = Color.Black)
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
                    Text("Statistics", fontWeight = FontWeight.Bold, color = Color.Black)
                }

                if (samples.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("📛 Unable to retrieve statistics.", color = Color.Red)
                } else {
                    val (avg, max, min) = vm.statsOf(remote).also {
                        Log.d("HIST_UI", "stats avg=${it.first}, max=${it.second}, min=${it.third}")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(String.format("Average: %.1f %s", avg, getUnit(key)), color = Color.Black)
                    Text(String.format("Max: %.1f %s", max, getUnit(key)), color = Color.Black)
                    Text(String.format("Min: %.1f %s", min, getUnit(key)), color = Color.Black)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    )
}

private fun generateDummySeries(sensorType: String): List<Float> {
    val base = when (sensorType.lowercase()) {
        "temperature" -> 26f
        "humid"       -> 55f
        "co2"         -> 420f
        "soil"        -> 350f
        else          -> 10f
    }

    return List(20) { i ->
        val wave = kotlin.math.sin(i / 3f) * 1.2f
        val noise = (-8..8).random() / 10f
        (base + wave + noise).coerceAtLeast(0f)
    }
}


fun actuatorColor(type: String): Color {
    return when (type.uppercase()) {
        "WATER" -> Color(0xFF6495ED)
        "LED" -> Color(0xFFFFC107)
        "FAN" -> Color(0xFF66CDAA)
        "DOOR" -> Color(0xFF8B4513)
        else -> Color.Gray
    }
}

fun actuatorIcon(type: String): ImageVector {
    return when (type.uppercase()) {
        "WATER" -> Icons.Default.InvertColors
        "LED" -> Icons.Default.Lightbulb
        "FAN" -> Icons.Default.Cached
        "DOOR" -> Icons.Default.MeetingRoom
        else -> Icons.Default.Build
    }
}

fun sensorColor(sensorType: String): Color {
    return when (sensorType.uppercase()) {
        "TEMPERATURE" -> Color(0xFFEF6C00)
        "HUMIDITY" -> Color(0xFF42A5F5)
        "CO2" -> Color(0xFF66BB6A)
        "SOIL" -> Color(0xFF8D6E63)
        "GPS" -> Color(0xFFD32F2F)
        else -> Color.Gray
    }
}

fun sensorIcon(sensorType: String): ImageVector {
    return when (sensorType.uppercase()) {
        "TEMPERATURE" -> Icons.Default.Thermostat
        "HUMIDITY" -> Icons.Default.WaterDrop
        "CO2"  -> Icons.Default.Cloud
        "SOIL" -> Icons.Default.Agriculture
        "GPS" -> Icons.Default.LocationOn
        else -> Icons.Default.Sensors
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

private fun pickSensorKey(
    requested: String,
    sensors: Map<String, Float>
): String {
    if (sensors.containsKey(requested)) return requested

    sensors["Humidity"]?.let { return "Humidity" }
    sensors["humidity"]?.let { return "humidity" }

    sensors.keys.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }

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
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = farm.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                LocationText(lat = farm.lat, lng = farm.lng)
            }

            Spacer(Modifier.height(8.dp))

            Row {
                Text("🌡 $tempText", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text("💧 $humiText", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}
