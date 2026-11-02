package com.routescout.mobile.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class NetworkDiagnosticsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun runQuickCheck(): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        val internetAvailable = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        val dnsStart = SystemClock.elapsedRealtime()
        val dnsLatency = try {
            val addresses = InetAddress.getAllByName("example.com")
            if (addresses.isNotEmpty()) SystemClock.elapsedRealtime() - dnsStart else null
        } catch (_: Exception) {
            null
        }

        val httpsStart = SystemClock.elapsedRealtime()
        val httpsLatency = try {
            val connection = URL("https://example.com/").openConnection() as HttpsURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.useCaches = false
            val responseCode = connection.responseCode
            connection.disconnect()
            if (responseCode in 200..499) {
                SystemClock.elapsedRealtime() - httpsStart
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val message = when {
            !internetAvailable -> "Android не подтвердил доступ к интернету"
            dnsLatency == null -> "Не удалось выполнить DNS-запрос"
            httpsLatency == null -> "Не удалось выполнить HTTPS-проверку"
            else -> null
        }

        DiagnosticSnapshot(
            internetAvailable = internetAvailable,
            dnsLatencyMs = dnsLatency,
            httpsLatencyMs = httpsLatency,
            checkedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            error = message
        )
    }
}
