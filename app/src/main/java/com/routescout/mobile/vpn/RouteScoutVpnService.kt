// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/** Sole Android TUN owner for imported sing-box profiles. */
class RouteScoutVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private val platformInterface by lazy { RouteScoutPlatformInterface(this) }

    private val commandHandler = object : CommandServerHandler {
        override fun serviceStop() {
            serviceScope.launch { stopCore(stopSelfAfter = true) }
        }

        override fun serviceReload() {
            serviceScope.launch {
                runCatching {
                    val profile = SecureSingBoxConfigStore(this@RouteScoutVpnService).load()
                        ?: error("Profile is missing")
                    startCore(profile)
                }.onFailure {
                    RouteScoutVpnRuntime.markFailure(
                        this@RouteScoutVpnService,
                        "LIBBOX_RELOAD_FAILED"
                    )
                }
            }
        }

        override fun getSystemProxyStatus(): SystemProxyStatus? =
            SystemProxyStatus().apply {
                available = false
                enabled = false
            }

        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
        override fun triggerNativeCrash() = Unit

        override fun writeDebugMessage(message: String?) {
            // Native messages may contain user-supplied configuration values.
            // Never copy raw libbox messages into Android logs or diagnostic reports.
        }

        override fun connectSSHAgent(): Int = -1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { stopCore(stopSelfAfter = true) }
            ACTION_START, null -> {
                promoteToForeground("Подготовка защищённого туннеля…")
                serviceScope.launch {
                    try {
                        val profile = SecureSingBoxConfigStore(this@RouteScoutVpnService).load()
                            ?: error("No sing-box profile is stored")
                        startCore(profile)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        stopCore(stopSelfAfter = true)
                        RouteScoutVpnRuntime.markFailure(
                            this@RouteScoutVpnService,
                            "LIBBOX_START_FAILED"
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        runBlocking(Dispatchers.IO) { stopCore(stopSelfAfter = false) }
        super.onRevoke()
    }

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) { stopCore(stopSelfAfter = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun startCore(profile: String) = withContext(Dispatchers.IO) {
        check(VpnService.prepare(this@RouteScoutVpnService) == null) {
            "Android VPN permission is not granted"
        }
        RouteScoutVpnRuntime.markStarting(this@RouteScoutVpnService)
        ensureLibboxSetup()

        val server = commandServer ?: CommandServer(commandHandler, platformInterface).also {
            it.start()
            commandServer = it
        }
        server.startOrReloadService(profile, OverrideOptions())
        RouteScoutVpnRuntime.markRunning(this@RouteScoutVpnService)
        updateForegroundNotification("VPN-движок sing-box запущен")
        startDataPathMonitor()
    }

    private fun ensureLibboxSetup() {
        synchronized(LIBBOX_LOCK) {
            if (libboxInitialized) return
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            val workingDirectory = getExternalFilesDir(null) ?: filesDir
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val options = SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = workingDirectory.absolutePath
                tempPath = cacheDir.absolutePath
                debug = false
                logMaxLines = 1000
                appVersion = packageInfo.versionCode.toString()
                appMarketingVersion = packageInfo.versionName ?: "0.7.0"
            }
            Libbox.setup(options)
            libboxInitialized = true
        }
    }

    private fun startDataPathMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            delay(8_000L)
            while (true) {
                val network = findVpnNetwork()
                val verified = network != null && runCatching {
                    com.routescout.mobile.diagnostics.NetworkDiagnosticsRepository(
                        this@RouteScoutVpnService
                    ).probeHttpsOn(network)
                }.getOrDefault(false)
                RouteScoutVpnRuntime.markVerification(this@RouteScoutVpnService, verified)
                updateForegroundNotification(
                    if (verified) "Туннель активен · HTTPS через VPN подтверждён"
                    else "Туннель активен · проверка передачи данных не пройдена"
                )
                delay(30_000L)
            }
        }
    }

    private fun findVpnNetwork(): android.net.Network? {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private suspend fun stopCore(stopSelfAfter: Boolean) = withContext(Dispatchers.IO) {
        monitorJob?.cancelAndJoin()
        monitorJob = null

        val descriptor = tunDescriptor
        tunDescriptor = null
        runCatching { descriptor?.close() }

        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.closeService() }
            runCatching { server.close() }
        }

        RouteScoutVpnRuntime.markStopped(this@RouteScoutVpnService)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopSelfAfter) stopSelf()
    }

    fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(this) == null) { "Android VPN permission is not granted" }
        val builder = Builder()
            .setSession("RouteScout")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val ipv4Addresses = options.inet4Address
        while (ipv4Addresses.hasNext()) {
            val address = ipv4Addresses.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val ipv6Addresses = options.inet6Address
        while (ipv6Addresses.hasNext()) {
            val address = ipv6Addresses.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServers = options.dnsServerAddress
                while (dnsServers.hasNext()) builder.addDnsServer(dnsServers.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ipv4Routes = options.inet4RouteAddress
                if (ipv4Routes.hasNext()) {
                    while (ipv4Routes.hasNext()) {
                        val route = ipv4Routes.next()
                        builder.addRoute(IpPrefix("\${route.address()}/\${route.prefix()}"))
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }

                val ipv6Routes = options.inet6RouteAddress
                if (ipv6Routes.hasNext()) {
                    while (ipv6Routes.hasNext()) {
                        val route = ipv6Routes.next()
                        builder.addRoute(IpPrefix("\${route.address()}/\${route.prefix()}"))
                    }
                } else {
                    // Capture IPv6 even if the remote outbound cannot route it: fail closed.
                    builder.addRoute("::", 0)
                }

                val ipv4Exclusions = options.inet4RouteExcludeAddress
                while (ipv4Exclusions.hasNext()) {
                    val route = ipv4Exclusions.next()
                    builder.excludeRoute(IpPrefix("\${route.address()}/\${route.prefix()}"))
                }
                val ipv6Exclusions = options.inet6RouteExcludeAddress
                while (ipv6Exclusions.hasNext()) {
                    val route = ipv6Exclusions.next()
                    builder.excludeRoute(IpPrefix("\${route.address()}/\${route.prefix()}"))
                }
            } else {
                val ipv4Routes = options.inet4RouteRange
                while (ipv4Routes.hasNext()) {
                    val route = ipv4Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val ipv6Routes = options.inet6RouteRange
                while (ipv6Routes.hasNext()) {
                    val route = ipv6Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }

            val allowedPackages = options.includePackage
            while (allowedPackages.hasNext()) {
                val packageName = allowedPackages.next()
                try {
                    builder.addAllowedApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore stale package selections.
                }
            }
            val excludedPackages = options.excludePackage
            while (excludedPackages.hasNext()) {
                val packageName = excludedPackages.next()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore stale package selections.
                }
            }
        }

        val descriptor = builder.establish()
            ?: error("Android refused to establish the VPN interface")
        runCatching { tunDescriptor?.close() }
        tunDescriptor = descriptor
        return descriptor.fd
    }

    fun showLibboxNotification(title: String?, body: String?) {
        updateForegroundNotification(
            listOfNotNull(title?.takeIf { it.isNotBlank() }, body?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .take(120)
                .ifBlank { "VPN-движок sing-box активен" }
        )
    }

    private fun promoteToForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("RouteScout VPN")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "RouteScout VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val ACTION_START = "com.routescout.mobile.vpn.START_SINGBOX"
        private const val ACTION_STOP = "com.routescout.mobile.vpn.STOP_SINGBOX"
        private const val CHANNEL_ID = "routescout-vpn"
        private const val NOTIFICATION_ID = 4201
        private val LIBBOX_LOCK = Any()
        @Volatile private var libboxInitialized = false

        fun start(context: android.content.Context) {
            val intent = Intent(context, RouteScoutVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, RouteScoutVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
