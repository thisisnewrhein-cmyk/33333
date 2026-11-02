package com.routescout.mobile.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.routescout.mobile.diagnostics.DiagnosticSnapshot
import com.routescout.mobile.diagnostics.NetworkDiagnosticsRepository
import com.routescout.mobile.ui.theme.RouteBackground
import com.routescout.mobile.ui.theme.RouteBlue
import com.routescout.mobile.ui.theme.RouteCoral
import com.routescout.mobile.ui.theme.RouteCoralSoft
import com.routescout.mobile.ui.theme.RouteCyan
import com.routescout.mobile.ui.theme.RouteMuted
import com.routescout.mobile.ui.theme.RoutePurple
import com.routescout.mobile.ui.theme.RouteSurfaceRaised
import com.routescout.mobile.ui.theme.RouteText
import com.routescout.mobile.vpn.SecureConfigStore
import com.routescout.mobile.vpn.WireGuardManager
import com.routescout.mobile.vpn.FreeVpnServer
import com.routescout.mobile.vpn.VpnGateRepository
import com.routescout.mobile.vpn.VpnGateProfileExporter
import com.routescout.mobile.vpn.ServerSelectionEngine
import com.routescout.mobile.vpn.ServerHealthChecker
import com.routescout.mobile.vpn.ServerHealthResult
import com.routescout.mobile.vpn.ProtonFreeProvider
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class AppTab(val title: String) {
    OVERVIEW("Обзор"),
    SERVERS("Серверы"),
    DIAGNOSTICS("Диагностика"),
    SETTINGS("Настройки")
}

@Composable
fun RouteScoutApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configStore = remember(context) { SecureConfigStore(context) }
    val wireGuardManager = remember(context) { WireGuardManager(context) }
    val vpnGateRepository = remember { VpnGateRepository() }
    val profileExporter = remember(context) { VpnGateProfileExporter(context) }
    val serverHealthChecker = remember { ServerHealthChecker() }

    var selectedTab by remember { mutableStateOf(AppTab.OVERVIEW) }
    var serverHealthResults by remember { mutableStateOf<Map<String, ServerHealthResult>>(emptyMap()) }
    var checkingServerKey by remember { mutableStateOf<String?>(null) }
    var isAutoSelectingServer by remember { mutableStateOf(false) }
    var freeServers by remember { mutableStateOf<List<FreeVpnServer>>(emptyList()) }
    var isLoadingFreeServers by remember { mutableStateOf(false) }
    var freeServersError by remember { mutableStateOf<String?>(null) }
    var freeServersUpdated by remember { mutableStateOf<String?>(null) }
    var freeServerActionMessage by remember { mutableStateOf<String?>(null) }
    var exportingServerKey by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(DiagnosticSnapshot()) }
    var isChecking by remember { mutableStateOf(false) }
    var profileImported by remember { mutableStateOf(false) }
    var profileEndpoint by remember { mutableStateOf<String?>(null) }
    var isVpnConnected by remember { mutableStateOf(false) }
    var isVpnTunnelUp by remember { mutableStateOf(false) }
    var isVpnBusy by remember { mutableStateOf(false) }
    var vpnMessage by remember { mutableStateOf("Сервер не настроен") }

    val connectTunnel: () -> Unit = {
        if (!isVpnBusy) {
            scope.launch {
                isVpnBusy = true
                try {
                    val configText = withContext(Dispatchers.IO) {
                        configStore.load() ?: error("No WireGuard profile")
                    }
                    val previousTunnel = wireGuardManager.getSnapshot()
                    wireGuardManager.connect(configText)

                    // Generate real traffic through the VPN before checking peer statistics.
                    snapshot = NetworkDiagnosticsRepository(context).runQuickCheck()
                    val verifiedTunnel = wireGuardManager.waitForHandshake(previousTunnel)
                    isVpnTunnelUp = verifiedTunnel.isUp
                    isVpnConnected =
                        verifiedTunnel.isUp &&
                        verifiedTunnel.hasRecentHandshake() &&
                        verifiedTunnel.hasTrafficEvidenceSince(previousTunnel)

                    vpnMessage = if (isVpnConnected) {
                        "WireGuard handshake подтверждён"
                    } else if (verifiedTunnel.isUp) {
                        "Туннель запущен, но сервер не подтвердил handshake. Проверь Endpoint, ключи и доступность сервера."
                    } else {
                        "VPN-туннель не запущен. Проверь профиль и разрешение Android VPN."
                    }
                } catch (_: Exception) {
                    isVpnConnected = false
                    isVpnTunnelUp = try {
                        wireGuardManager.getSnapshot().isUp
                    } catch (_: Exception) {
                        false
                    }
                    vpnMessage = "Не удалось подключиться. Проверьте профиль и доступность сервера."
                } finally {
                    isVpnBusy = false
                }
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connectTunnel()
        } else {
            vpnMessage = "Разрешение VPN не предоставлено"
        }
    }

    val requestConnect: () -> Unit = {
        val permissionIntent = GoBackend.VpnService.prepare(context)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            connectTunnel()
        }
    }

    val disconnectTunnel: () -> Unit = {
        if (!isVpnBusy) {
            scope.launch {
                isVpnBusy = true
                try {
                    wireGuardManager.disconnect()
                    isVpnConnected = false
                    isVpnTunnelUp = false
                    vpnMessage = "Соединение отключено"
                } catch (_: Exception) {
                    vpnMessage = "Не удалось корректно отключить туннель"
                } finally {
                    isVpnBusy = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val configText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().toString(Charsets.UTF_8)
                        } ?: error("Cannot read selected file")
                    }
                    withContext(Dispatchers.IO) {
                        Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                        configStore.save(configText)
                    }
                    profileImported = true
                    profileEndpoint = extractEndpoint(configText)
                    vpnMessage = "Профиль WireGuard сохранён в защищённом хранилище"
                } catch (_: Exception) {
                    vpnMessage = "Не удалось импортировать файл. Нужен корректный WireGuard .conf"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val savedConfig = withContext(Dispatchers.IO) { configStore.load() }
            profileImported = savedConfig != null
            profileEndpoint = savedConfig?.let { extractEndpoint(it) }
            val previousTunnel = wireGuardManager.getSnapshot()
            isVpnTunnelUp = previousTunnel.isUp
            if (previousTunnel.isUp) {
                snapshot = NetworkDiagnosticsRepository(context).runQuickCheck()
                val verifiedTunnel = wireGuardManager.waitForHandshake(previousTunnel)
                isVpnTunnelUp = verifiedTunnel.isUp
                isVpnConnected =
                    verifiedTunnel.isUp &&
                    verifiedTunnel.hasRecentHandshake() &&
                    verifiedTunnel.hasTrafficEvidenceSince(previousTunnel)
            } else {
                isVpnConnected = false
                isVpnTunnelUp = false
            }
            vpnMessage = when {
                isVpnConnected -> "WireGuard handshake подтверждён"
                previousTunnel.isUp -> "Туннель запущен, но handshake сервера не подтверждён"
                profileImported -> "Профиль готов к подключению"
                else -> "Сервер не настроен"
            }
        } catch (_: Exception) {
            vpnMessage = "Не удалось прочитать сохранённый профиль"
        }
    }

    val refreshFreeServers: () -> Unit = {
        if (!isLoadingFreeServers) {
            scope.launch {
                isLoadingFreeServers = true
                freeServersError = null
                try {
                    freeServers = vpnGateRepository.refresh()
                    freeServersUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                } catch (error: Exception) {
                    freeServersError = error.message?.take(160) ?: "Не удалось обновить каталог VPN Gate"
                } finally {
                    isLoadingFreeServers = false
                }
            }
        }
    }

    val openFreeServerProfile: (FreeVpnServer) -> Unit = { server ->
        if (exportingServerKey == null) {
            scope.launch {
                exportingServerKey = server.key
                freeServerActionMessage = null
                try {
                    val profileUri = withContext(Dispatchers.IO) {
                        profileExporter.export(server)
                    }
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(profileUri, "application/x-openvpn-profile")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(
                            context.contentResolver,
                            "RouteScout OpenVPN profile",
                            profileUri
                        )
                    }
                    context.startActivity(Intent.createChooser(openIntent, "Открыть профиль в OpenVPN"))
                    freeServerActionMessage =
                        "Профиль подготовлен. Если клиент запросит учётные данные, введи vpn / vpn."
                } catch (_: ActivityNotFoundException) {
                    freeServerActionMessage =
                        "OpenVPN-клиент не найден. Установи OpenVPN for Android или OpenVPN Connect."
                } catch (error: Exception) {
                    freeServerActionMessage =
                        error.message?.take(160) ?: "Не удалось подготовить OpenVPN-профиль."
                } finally {
                    exportingServerKey = null
                }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.SERVERS && freeServers.isEmpty() && !isLoadingFreeServers) {
            refreshFreeServers()
        }
    }

    val checkFreeServer: (FreeVpnServer) -> Unit = { server ->
        if (checkingServerKey == null && !isAutoSelectingServer) {
            scope.launch {
                checkingServerKey = server.key
                try {
                    val result = serverHealthChecker.check(server)
                    serverHealthResults = serverHealthResults + (server.key to result)
                } catch (error: Exception) {
                    serverHealthResults = serverHealthResults + (
                        server.key to ServerHealthResult.TcpUnreachable(
                            error.message ?: "Проверка не выполнена"
                        )
                    )
                } finally {
                    checkingServerKey = null
                }
            }
        }
    }

    val autoSelectFreeServer: () -> Unit = {
        if (!isAutoSelectingServer && exportingServerKey == null && freeServers.isNotEmpty()) {
            scope.launch {
                isAutoSelectingServer = true
                freeServerActionMessage = "AUTO: проверяю до пяти серверов по рейтингу каталога…"
                checkingServerKey = null
                var selectedTcpServer: FreeVpnServer? = null
                var fallbackUdpServer: FreeVpnServer? = null
                try {
                    val candidates = ServerSelectionEngine.rankedServers(freeServers).take(5)
                    for (server in candidates) {
                        checkingServerKey = server.key
                        val result = serverHealthChecker.check(server)
                        serverHealthResults = serverHealthResults + (server.key to result)
                        when (result) {
                            is ServerHealthResult.TcpReachable -> {
                                selectedTcpServer = server
                                break
                            }
                            ServerHealthResult.UdpRequiresHandshake -> {
                                if (fallbackUdpServer == null) fallbackUdpServer = server
                            }
                            is ServerHealthResult.TcpUnreachable,
                            ServerHealthResult.InvalidProfile -> Unit
                        }
                    }

                    val selectedServer = selectedTcpServer ?: fallbackUdpServer
                    if (selectedServer != null) {
                        freeServerActionMessage = if (selectedTcpServer != null) {
                            "Найден TCP endpoint, доступный с этого устройства. Передаю профиль в OpenVPN-клиент…"
                        } else {
                            "У первых серверов нет проверяемого TCP endpoint. Выбран UDP-сервер; его доступность подтвердится только после реального handshake."
                        }
                        openFreeServerProfile(selectedServer)
                    } else {
                        freeServerActionMessage = "Первые пять серверов не прошли TCP-проверку, а проверяемый UDP-профиль не найден."
                    }
                } catch (error: Exception) {
                    freeServerActionMessage = error.message?.take(160) ?: "AUTO не смог проверить серверы."
                } finally {
                    checkingServerKey = null
                    isAutoSelectingServer = false
                }
            }
        }
    }

    val runDiagnostics: () -> Unit = {
        if (!isChecking) {
            scope.launch {
                isChecking = true
                snapshot = NetworkDiagnosticsRepository(context).runQuickCheck()
                isChecking = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(RouteBackground)) {
        AmbientBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                BottomNavigationBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppHeader()
                when (selectedTab) {
                    AppTab.OVERVIEW -> OverviewScreen(
                        snapshot = snapshot,
                        isChecking = isChecking,
                        isVpnConnected = isVpnConnected,
                        isVpnTunnelUp = isVpnTunnelUp,
                        profileImported = profileImported,
                        isVpnBusy = isVpnBusy,
                        vpnMessage = vpnMessage,
                        onRunDiagnostics = runDiagnostics,
                        onConnect = requestConnect,
                        onDisconnect = disconnectTunnel,
                        onOpenServers = { selectedTab = AppTab.SERVERS }
                    )
                    AppTab.SERVERS -> ServersScreen(
                        freeServers = freeServers,
                        isLoadingFreeServers = isLoadingFreeServers,
                        freeServersError = freeServersError,
                        freeServersUpdated = freeServersUpdated,
                        freeServerActionMessage = freeServerActionMessage,
                        exportingServerKey = exportingServerKey,
                        serverHealthResults = serverHealthResults,
                        checkingServerKey = checkingServerKey,
                        isAutoSelectingServer = isAutoSelectingServer,
                        onRefreshFreeServers = refreshFreeServers,
                        onCheckFreeServer = checkFreeServer,
                        onAutoSelectFreeServer = autoSelectFreeServer,
                        onOpenFreeServerProfile = openFreeServerProfile,
                        profileImported = profileImported,
                        profileEndpoint = profileEndpoint,
                        isVpnConnected = isVpnConnected,
                        isVpnTunnelUp = isVpnTunnelUp,
                        isVpnBusy = isVpnBusy,
                        vpnMessage = vpnMessage,
                        onImport = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        onConnect = requestConnect,
                        onDisconnect = disconnectTunnel,
                        onDeleteProfile = {
                            scope.launch {
                                withContext(Dispatchers.IO) { configStore.delete() }
                                profileImported = false
                                profileEndpoint = null
                                vpnMessage = "Профиль удалён"
                            }
                        }
                    )
                    AppTab.DIAGNOSTICS -> DiagnosticsScreen(
                        snapshot = snapshot,
                        isChecking = isChecking,
                        onRunDiagnostics = runDiagnostics
                    )
                    AppTab.SETTINGS -> SettingsScreen()
                }
                Text(
                    text = "ROUTESCOUT MOBILE  •  PRIVATE BY DESIGN",
                    color = RouteMuted.copy(alpha = 0.55f),
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                )
            }
        }
    }
}

private fun extractEndpoint(configText: String): String? =
    configText.lineSequence()
        .firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

@Composable
private fun AmbientBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF080B15), Color(0xFF090D18), Color(0xFF070A12))
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x44FF887E), Color(0x00FF887E)),
                center = Offset(size.width * 0.94f, size.height * 0.12f),
                radius = size.width * 0.72f
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.94f, size.height * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x3373E6D6), Color(0x0073E6D6)),
                center = Offset(size.width * 0.02f, size.height * 0.62f),
                radius = size.width * 0.7f
            ),
            radius = size.width * 0.7f,
            center = Offset(size.width * 0.02f, size.height * 0.62f)
        )
        val ribbon = Path().apply {
            moveTo(size.width * 0.55f, 0f)
            cubicTo(size.width * 0.78f, size.height * 0.06f, size.width * 0.65f, size.height * 0.16f, size.width, size.height * 0.19f)
        }
        drawPath(
            path = ribbon,
            brush = Brush.linearGradient(listOf(RouteCoral.copy(alpha = 0.5f), RoutePurple.copy(alpha = 0.02f))),
            style = Stroke(width = 1.4.dp.toPx())
        )
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF28334A), Color(0xFF111827))))
                    .border(1.dp, RouteCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = RouteCyan, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("ROUTESCOUT", color = RouteText, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("NETWORK INTELLIGENCE", color = RouteMuted, fontSize = 8.sp, letterSpacing = 1.3.sp)
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF172033))
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                .padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            Text("BETA", color = RouteCoralSoft, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OverviewScreen(
    snapshot: DiagnosticSnapshot,
    isChecking: Boolean,
    isVpnConnected: Boolean,
    isVpnTunnelUp: Boolean,
    profileImported: Boolean,
    isVpnBusy: Boolean,
    vpnMessage: String,
    onRunDiagnostics: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Сетевой обзор",
            color = RouteText,
            fontSize = 31.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium
        )
        Text("Контроль подключения и состояния сети", color = RouteMuted, fontSize = 13.sp)

        ConnectionPanel(
            isVpnConnected = isVpnConnected,
            isVpnTunnelUp = isVpnTunnelUp,
            profileImported = profileImported,
            isVpnBusy = isVpnBusy,
            vpnMessage = vpnMessage,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onOpenServers = onOpenServers
        )

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricPanel(
                modifier = Modifier.weight(1f),
                title = "DNS",
                value = snapshot.dnsLatencyMs?.let { it.toString() + " ms" } ?: "—",
                hint = "Отклик",
                accent = RouteCyan,
                icon = Icons.Outlined.Dns
            )
            MetricPanel(
                modifier = Modifier.weight(1f),
                title = "HTTPS",
                value = snapshot.httpsLatencyMs?.let { it.toString() + " ms" } ?: "—",
                hint = "Проверка",
                accent = RouteCoral,
                icon = Icons.Outlined.Speed
            )
            MetricPanel(
                modifier = Modifier.weight(1f),
                title = "Интернет",
                value = when (snapshot.internetAvailable) {
                    true -> "Есть"
                    false -> "Нет"
                    null -> "—"
                },
                hint = "Доступ",
                accent = RouteBlue,
                icon = Icons.Outlined.Wifi
            )
        }

        NetworkHealthPanel(
            snapshot = snapshot,
            isChecking = isChecking,
            onRunDiagnostics = onRunDiagnostics
        )

        ServerPreviewPanel(onOpenServers = onOpenServers)

        if (snapshot.hasResults) {
            Panel {
                Text("Последняя проверка", color = RouteText, fontSize = 17.sp, fontFamily = FontFamily.Serif)
                Spacer(Modifier.height(8.dp))
                Text("Время: " + (snapshot.checkedAt ?: "—"), color = RouteMuted, fontSize = 12.sp)
                snapshot.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = RouteCoralSoft, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    isVpnConnected: Boolean,
    isVpnTunnelUp: Boolean,
    profileImported: Boolean,
    isVpnBusy: Boolean,
    vpnMessage: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit
) {
    Panel(
        modifier = Modifier.shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = RouteCoral.copy(alpha = 0.12f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(RouteCoral.copy(alpha = 0.3f), Color(0x22151B2A))))
                    .border(1.dp, if (isVpnConnected) RouteCyan.copy(alpha = 0.6f) else if (isVpnTunnelUp) RouteBlue.copy(alpha = 0.6f) else RouteCoral.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = if (isVpnConnected) RouteCyan else if (isVpnTunnelUp) RouteBlue else RouteCoralSoft,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ", color = RouteMuted, fontSize = 9.sp, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        isVpnConnected -> "Подключено"
                        isVpnTunnelUp -> "Туннель запущен"
                        else -> "Не подключено"
                    },
                    color = RouteText,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Serif
                )
                Text(
                    when {
                        isVpnConnected -> "WireGuard handshake подтверждён"
                        isVpnTunnelUp -> "Сервер ещё не подтвердил handshake"
                        profileImported -> "Профиль готов к запуску"
                        else -> "Сервер ещё не настроен"
                    },
                    color = RouteMuted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(vpnMessage, color = if (isVpnConnected) RouteCyan else if (isVpnTunnelUp) RouteCoralSoft else RouteMuted, fontSize = 10.sp)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = when {
                isVpnBusy -> ({})
                isVpnTunnelUp -> onDisconnect
                profileImported -> onConnect
                else -> onOpenServers
            },
            enabled = !isVpnBusy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RouteCoral, contentColor = RouteBackground)
        ) {
            if (isVpnBusy) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = RouteBackground)
                Spacer(Modifier.width(8.dp))
                Text("Подождите…", fontWeight = FontWeight.Bold)
            } else {
                Icon(
                    if (isVpnTunnelUp) Icons.Outlined.Shield else Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isVpnTunnelUp -> "Отключить"
                        profileImported -> "Подключить"
                        else -> "Добавить VPN-профиль"
                    },
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun MetricPanel(
    modifier: Modifier,
    title: String,
    value: String,
    hint: String,
    accent: Color,
    icon: ImageVector
) {
    Panel(modifier = modifier) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(11.dp))
        Text(title, color = RouteMuted, fontSize = 10.sp)
        Text(
            value,
            color = RouteText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Text(hint, color = RouteMuted.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

@Composable
private fun NetworkHealthPanel(
    snapshot: DiagnosticSnapshot,
    isChecking: Boolean,
    onRunDiagnostics: () -> Unit
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Состояние сети", color = RouteText, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                Text("Базовая проверка доступности", color = RouteMuted, fontSize = 11.sp)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(RouteCyan.copy(alpha = 0.12f))
                    .padding(9.dp)
            ) {
                Icon(Icons.Outlined.Wifi, contentDescription = null, tint = RouteCyan, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        HealthRow("Интернет", when (snapshot.internetAvailable) {
            true -> "Доступен"
            false -> "Нет доступа"
            null -> "Не проверен"
        }, snapshot.internetAvailable == true)
        HealthRow("DNS", if (snapshot.dnsLatencyMs != null) snapshot.dnsLatencyMs.toString() + " ms" else "Не проверен", snapshot.dnsLatencyMs != null)
        HealthRow("HTTPS", if (snapshot.httpsLatencyMs != null) snapshot.httpsLatencyMs.toString() + " ms" else "Не проверен", snapshot.httpsLatencyMs != null)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onRunDiagnostics,
            enabled = !isChecking,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RouteSurfaceRaised, contentColor = RouteCyan)
        ) {
            if (isChecking) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = RouteCyan)
                Spacer(Modifier.width(9.dp))
                Text("Проверяем сеть…")
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Запустить диагностику")
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, isGood: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape)
                .background(if (isGood) RouteCyan else RouteMuted.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(9.dp))
        Text(label, color = RouteMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (isGood) RouteCyan else RouteText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ServerPreviewPanel(onOpenServers: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Активный сервер", color = RouteText, fontSize = 19.sp, fontFamily = FontFamily.Serif)
                Text("Пока не выбран", color = RouteMuted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.Public, contentDescription = null, tint = RoutePurple, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Можно импортировать WireGuard-профиль или открыть бесплатный общественный OpenVPN-сервер из каталога VPN Gate.",
            color = RouteMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenServers,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x22B89BFF), contentColor = RoutePurple)
        ) {
            Text("Управление серверами")
        }
    }
}

@Composable
private fun ServersScreen(
    freeServers: List<FreeVpnServer>,
    isLoadingFreeServers: Boolean,
    freeServersError: String?,
    freeServersUpdated: String?,
    freeServerActionMessage: String?,
    exportingServerKey: String?,
    serverHealthResults: Map<String, ServerHealthResult>,
    checkingServerKey: String?,
    isAutoSelectingServer: Boolean,
    onRefreshFreeServers: () -> Unit,
    onCheckFreeServer: (FreeVpnServer) -> Unit,
    onAutoSelectFreeServer: () -> Unit,
    onOpenFreeServerProfile: (FreeVpnServer) -> Unit,
    profileImported: Boolean,
    profileEndpoint: String?,
    isVpnConnected: Boolean,
    isVpnTunnelUp: Boolean,
    isVpnBusy: Boolean,
    vpnMessage: String,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDeleteProfile: () -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Серверы", color = RouteText, fontSize = 31.sp, fontFamily = FontFamily.Serif)
        Text("Управление точками подключения", color = RouteMuted, fontSize = 13.sp)
        Panel {
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape)
                    .background(RoutePurple.copy(alpha = 0.13f))
                    .border(1.dp, RoutePurple.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, tint = RoutePurple, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.height(15.dp))
            Text(
                if (profileImported) "WireGuard-профиль добавлен" else "Нет добавленных серверов",
                color = RouteText,
                fontSize = 21.sp,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (profileImported) "Профиль сохранён локально в зашифрованном виде." else "Импортируй конфигурацию WireGuard (.conf), полученную от своего VPS или VPN-провайдера.",
                color = RouteMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            if (profileEndpoint != null) {
                Spacer(Modifier.height(10.dp))
                Text("Endpoint: " + profileEndpoint, color = RouteCyan, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(vpnMessage, color = if (isVpnConnected) RouteCyan else if (isVpnTunnelUp) RouteCoralSoft else RouteMuted, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RouteSurfaceRaised, contentColor = RouteCyan)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (profileImported) "Заменить профиль" else "Импортировать WireGuard .conf")
            }
            if (profileImported) {
                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = if (isVpnTunnelUp) onDisconnect else onConnect,
                    enabled = !isVpnBusy,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RouteCoral, contentColor = RouteBackground)
                ) {
                    if (isVpnBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = RouteBackground)
                        Spacer(Modifier.width(8.dp))
                        Text("Подождите…")
                    } else {
                        Text(if (isVpnTunnelUp) "Отключить VPN" else "Подключиться")
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Удалить сохранённый профиль",
                    color = RouteCoralSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onDeleteProfile).padding(8.dp)
                )
            }
        }
        Panel {
            Text("Proton VPN Free", color = RouteText, fontSize = 20.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(6.dp))
            Text(
                "Официальный бесплатный тариф: без лимита трафика и времени, но требуется бесплатный аккаунт. Одновременно разрешено одно устройство.",
                color = RouteMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Доступных стран: " + ProtonFreeProvider.freeCountries.size,
                color = RouteCyan,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Бесплатные страны: Нидерланды, Япония, Румыния, Польша, Норвегия, Швейцария, Сингапур, Мексика, Канада и США.",
                color = RouteMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(ProtonFreeProvider.CONFIG_PORTAL_URL))
                    )
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x2238D7D0),
                    contentColor = RouteCyan
                )
            ) {
                Text("Получить бесплатный WireGuard-профиль")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Порядок: открой портал Proton → Downloads → WireGuard configuration → скачай .conf → вернись сюда и нажми «Импортировать WireGuard .conf». После импорта RouteScout подключается собственным WireGuard-движком. Отдельное VPN-приложение не требуется.",
                color = RouteMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Бесплатные серверы", color = RouteText, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                    Text("VPN Gate · общественные OpenVPN-реле", color = RouteMuted, fontSize = 11.sp)
                }
                Button(
                    onClick = onRefreshFreeServers,
                    enabled = !isLoadingFreeServers,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RouteSurfaceRaised,
                        contentColor = RouteCyan
                    )
                ) {
                    if (isLoadingFreeServers) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = RouteCyan
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Каталог загружается из официального API VPN Gate. Ping и рейтинг — данные каталога. Для TCP доступности есть отдельная проверка с устройства; UDP можно подтвердить только реальным handshake.",
                color = RouteMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            if (freeServersUpdated != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Обновлено: $freeServersUpdated · найдено: ${freeServers.size}",
                    color = RouteCyan,
                    fontSize = 10.sp
                )
            }
            freeServersError?.let {
                Spacer(Modifier.height(8.dp))
                Text("Ошибка каталога: $it", color = RouteCoralSoft, fontSize = 11.sp)
            }
            freeServerActionMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RouteCyan, fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (freeServers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val bestServer = ServerSelectionEngine.bestServer(freeServers)
                if (bestServer != null) {
                    Button(
                        onClick = onAutoSelectFreeServer,
                        enabled = exportingServerKey == null && !isAutoSelectingServer && checkingServerKey == null,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RouteCoral,
                            contentColor = RouteBackground
                        )
                    ) {
                        Text(
                            if (isAutoSelectingServer) "AUTO · проверяю серверы…" else "AUTO · проверить и открыть лучший",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(freeServers, key = { it.key }) { server ->
                        FreeVpnServerCard(
                            server = server,
                            healthResult = serverHealthResults[server.key],
                            isBusy = exportingServerKey != null || isAutoSelectingServer,
                            isCheckingHealth = checkingServerKey == server.key,
                            onCheckHealth = { onCheckFreeServer(server) },
                            onOpenProfile = { onOpenFreeServerProfile(server) }
                        )
                    }
                }
            } else if (!isLoadingFreeServers && freeServersError == null) {
                Spacer(Modifier.height(8.dp))
                Text("Список серверов ещё не загружен. Нажми обновить.", color = RouteMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Важно: это общественные серверы добровольцев. Оператор может видеть незашифрованный трафик и собирать метаданные. Не используй их для банков, почты и важных аккаунтов. Для подключения профиль пока передаётся в отдельный OpenVPN-клиент.",
                color = RouteCoralSoft,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Panel {
            Text("Безопасность профиля", color = RouteText, fontSize = 18.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text(
                "Конфигурация шифруется ключом Android Keystore и хранится только в приватной памяти приложения. Не загружай её в GitHub.",
                color = RouteMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun FreeVpnServerCard(
    server: FreeVpnServer,
    healthResult: ServerHealthResult?,
    isBusy: Boolean,
    isCheckingHealth: Boolean,
    onCheckHealth: () -> Unit,
    onOpenProfile: () -> Unit
) {
    Surface(
        color = RouteSurfaceRaised.copy(alpha = 0.62f),
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RouteBlue.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countryFlag(server.countryCode), fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.countryName, color = RouteText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(server.hostName, color = RouteMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("VPN GATE", color = RoutePurple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerMetric("PING", server.pingMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
                ServerMetric("СКОРОСТЬ", server.speedBitsPerSecond?.let { formatServerSpeed(it) } ?: "—", Modifier.weight(1f))
                ServerMetric("СЕССИИ", server.activeSessions.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "IP ${server.ipAddress} · рейтинг ${server.score} · uptime ${formatServerUptime(server.uptimeSeconds)}",
                color = RouteMuted,
                fontSize = 10.sp
            )
            if (server.logType.isNotBlank()) {
                Text("Политика логов в каталоге: ${server.logType}", color = RouteMuted, fontSize = 9.sp)
            }
            healthResult?.let { result ->
                val resultText = when (result) {
                    is ServerHealthResult.TcpReachable -> "TCP доступен с устройства · ${result.latencyMs} ms"
                    is ServerHealthResult.TcpUnreachable -> "TCP недоступен: ${result.reason.take(70)}"
                    ServerHealthResult.UdpRequiresHandshake -> "UDP: проверка возможна только после реального handshake"
                    ServerHealthResult.InvalidProfile -> "Некорректный OpenVPN-профиль"
                }
                val resultColor = when (result) {
                    is ServerHealthResult.TcpReachable -> RouteCyan
                    is ServerHealthResult.TcpUnreachable, ServerHealthResult.InvalidProfile -> RouteCoralSoft
                    ServerHealthResult.UdpRequiresHandshake -> RouteMuted
                }
                Text(resultText, color = resultColor, fontSize = 10.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(7.dp))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onCheckHealth,
                enabled = !isBusy && !isCheckingHealth,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RouteSurfaceRaised,
                    contentColor = RouteBlue
                )
            ) {
                if (isCheckingHealth) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = RouteBlue)
                    Spacer(Modifier.width(7.dp))
                    Text("Проверяю endpoint…", fontSize = 10.sp)
                } else {
                    Text(if (healthResult == null) "Проверить доступность" else "Проверить ещё раз", fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(7.dp))
            Button(
                onClick = onOpenProfile,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x2238D7D0),
                    contentColor = RouteCyan
                )
            ) {
                Text(if (isBusy) "Подготовка профиля…" else "Открыть профиль в OpenVPN", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ServerMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = RouteMuted, fontSize = 8.sp, letterSpacing = 0.7.sp)
        Text(value, color = RouteText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2 || countryCode.any { !it.isLetter() }) return "🌐"
    return countryCode.uppercase().map { character ->
        String(Character.toChars(0x1F1E6 + character.code - 'A'.code))
    }.joinToString("")
}

private fun formatServerSpeed(bitsPerSecond: Long): String =
    String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)

private fun formatServerUptime(seconds: Long): String {
    if (seconds <= 0L) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    return if (days > 0) "$days д. $hours ч." else "$hours ч."
}

@Composable
private fun DiagnosticsScreen(
    snapshot: DiagnosticSnapshot,
    isChecking: Boolean,
    onRunDiagnostics: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Диагностика", color = RouteText, fontSize = 31.sp, fontFamily = FontFamily.Serif)
        Text("Проверки выполняются на устройстве", color = RouteMuted, fontSize = 13.sp)
        NetworkHealthPanel(snapshot, isChecking, onRunDiagnostics)
        Panel {
            Text("Результаты последней проверки", color = RouteText, fontSize = 19.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(10.dp))
            HealthRow("Интернет", if (snapshot.internetAvailable == true) "Доступен" else if (snapshot.internetAvailable == false) "Недоступен" else "Нет данных", snapshot.internetAvailable == true)
            HealthRow("DNS example.com", snapshot.dnsLatencyMs?.toString()?.plus(" ms") ?: "Нет данных", snapshot.dnsLatencyMs != null)
            HealthRow("HTTPS example.com", snapshot.httpsLatencyMs?.toString()?.plus(" ms") ?: "Нет данных", snapshot.httpsLatencyMs != null)
            snapshot.checkedAt?.let {
                Spacer(Modifier.height(8.dp))
                Text("Время проверки: " + it, color = RouteMuted, fontSize = 11.sp)
            }
            snapshot.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RouteCoralSoft, fontSize = 12.sp)
            }
        }
        Text(
            "Важно: это базовая проверка DNS и HTTPS, а не полный тест VPN, скорости или утечек.",
            color = RouteMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun SettingsScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Настройки", color = RouteText, fontSize = 31.sp, fontFamily = FontFamily.Serif)
        Text("Приватность и параметры приложения", color = RouteMuted, fontSize = 13.sp)
        Panel {
            Text("Конфиденциальность", color = RouteText, fontSize = 19.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text(
                "Базовая диагностика выполняется локально. Приложение не отправляет диагностические отчёты автоматически.",
                color = RouteMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        Panel {
            Text("Версия приложения", color = RouteText, fontSize = 18.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(6.dp))
            Text("RouteScout Mobile 0.3.0", color = RouteMuted, fontSize = 12.sp)
            Text("VPN Gate и Proton Free · WireGuard", color = RouteMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xEE151B2A), Color(0xE90D1320), Color(0xEE101522))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(RouteCoral.copy(alpha = 0.42f), Color.White.copy(alpha = 0.11f), RouteCyan.copy(alpha = 0.24f))
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun BottomNavigationBar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit
) {
    Surface(color = Color(0xF20B101A), shadowElevation = 14.dp) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding()
        ) {
            AppTab.values().forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tabIcon(tab),
                            contentDescription = tab.title,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = { Text(tab.title, fontSize = 9.sp, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RouteCoralSoft,
                        selectedTextColor = RouteCoralSoft,
                        indicatorColor = RouteCoral.copy(alpha = 0.12f),
                        unselectedIconColor = RouteMuted,
                        unselectedTextColor = RouteMuted
                    )
                )
            }
        }
    }
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.OVERVIEW -> Icons.Outlined.Home
    AppTab.SERVERS -> Icons.Outlined.Public
    AppTab.DIAGNOSTICS -> Icons.Outlined.Speed
    AppTab.SETTINGS -> Icons.Outlined.Settings
}
