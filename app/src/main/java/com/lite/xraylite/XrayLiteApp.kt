package com.lite.xraylite

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.util.Logger

class XrayLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ServerRepository.init(this)
        applyThemePref()

        var coreEnvReady = true
        runCatching {
            go.Seq.setContext(applicationContext)
            libv2ray.Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        }.onFailure {
            coreEnvReady = false
            Logger.error("Gagal inisialisasi Xray-core native: ${it.javaClass.simpleName}: ${it.message}")
        }

        logStartupInfo()
        if (!coreEnvReady) {
            Logger.error("Xray-core belum siap -- koneksi VLESS/VMess/Trojan/SSH kemungkinan akan gagal sampai ini diperbaiki")
        }
        setupNetworkLogIfEnabled()
    }

    private fun setupNetworkLogIfEnabled() {
        if (!Prefs.networkLogEnabled) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val type = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
                    else -> "UNKNOWN"
                }
                Logger.log("Network up ($type)")
            }

            override fun onLost(network: Network) {
                Logger.log("Network lost")
            }
        })
    }

    private fun applyThemePref() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    private fun logStartupInfo() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        Logger.log("App version $versionName build $versionCode")

        runCatching { libv2ray.Libv2ray.checkVersionX() }
            .onSuccess { Logger.log("Xray/Core: $it") }
            .onFailure { Logger.error("Gagal ambil versi core: ${it.message}") }

        Logger.log(
            "Running on ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}); " +
                "Android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}); " +
                "ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
        )
    }
}
