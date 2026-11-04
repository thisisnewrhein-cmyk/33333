// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections

/** Minimal unprivileged Android platform bridge. Root-only capabilities are disabled. */
class RouteScoutPlatformInterface(
    private val vpnService: RouteScoutVpnService
) : PlatformInterface {
    private val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "Could not protect sing-box upstream socket" }
    }

    override fun openTun(options: TunOptions): Int = vpnService.openTun(options)

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
            check(uid != Process.INVALID_UID) { "Connection owner not found" }
            ConnectionOwner().also { it.userId = uid }
        } else {
            Libbox.findConnectionOwner(
                ipProtocol,
                sourceAddress,
                sourcePort,
                destinationAddress,
                destinationPort
            )
        }
        val packages = vpnService.packageManager.getPackagesForUid(owner.userId)
        owner.userName = packages?.firstOrNull().orEmpty()
        owner.setAndroidPackageNames(
            StringArray(packages?.iterator() ?: emptyList<String>().iterator())
        )
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(listener)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultInterface(listener)
            override fun onLost(network: Network) = updateDefaultInterface(listener)
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = updateDefaultInterface(listener)
            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) = updateDefaultInterface(listener)
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        updateDefaultInterface(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = networkCallback ?: return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener) {
        val active = connectivity.activeNetwork
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return@mapNotNull null
            network to caps
        }
        val selected = candidates.firstOrNull { it.first == active }
            ?: candidates.firstOrNull {
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            ?: candidates.firstOrNull()
        val link = selected?.first?.let(connectivity::getLinkProperties)
        val name = link?.interfaceName
        val index = name?.let {
            runCatching { JavaNetworkInterface.getByName(it)?.index }.getOrNull()
        } ?: -1
        listener.updateDefaultInterface(name.orEmpty(), index, false, false)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces())
        }.getOrDefault(emptyList())
        val result = mutableListOf<LibboxNetworkInterface>()

        for (network in connectivity.allNetworks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue
            val item = LibboxNetworkInterface()
            item.name = name
            item.dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress }.iterator())
            item.dnsSearchDomain = StringArray(
                link.domains?.split(' ')?.filter { it.isNotBlank() }.orEmpty().iterator()
            )
            item.gateway = StringArray(
                link.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.hostAddress }.iterator()
            )
            item.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.index = javaInterface.index
            item.mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
            item.addresses = StringArray(
                javaInterface.interfaceAddresses.map {
                    "\${it.address.hostAddress}/\${it.networkPrefixLength}"
                }.iterator()
            )
            var flags = 0
            if (runCatching { javaInterface.isUp }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (runCatching { javaInterface.supportsMulticast() }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_MULTICAST
            }
            item.flags = flags
            item.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result += item
        }
        return InterfaceArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException("Shell integration is disabled")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Shell integration is disabled")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("SSH host key access is disabled")

    override fun lookupSFTPServer(): String = ""
    override fun tailscaleHostname(): String = "\${Build.MANUFACTURER} \${Build.MODEL}"
    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Root bridge is not available")

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray?,
        handler: AutoRedirectHandler?
    ): AutoRedirectSession = throw UnsupportedOperationException("Root auto-redirect is not available")

    override fun lookupUser(username: String?): PlatformUser =
        PlatformUser().also {
            it.username = username ?: vpnService.packageName
            it.uid = Process.myUid()
            it.gid = Process.myUid()
            it.homeDir = vpnService.filesDir.absolutePath
        }

    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: Notification) {
        vpnService.showLibboxNotification(notification.title, notification.body)
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class InterfaceArray(
        private val iterator: Iterator<LibboxNetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }
}
