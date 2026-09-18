package com.lite.xraylite

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.util.CrashCatcher
import com.lite.xraylite.util.Logger

/**
 * go.Seq.setContext(...) WAJIB dipanggil sekali sebelum method Libv2ray/CoreController
 * apapun dipanggil — ini syarat runtime dari binding gomobile (gobind), bukan langkah
 * opsional. Paling aman dipanggil sedini mungkin di Application.onCreate(), bukan di
 * XrayVpnService (yang bisa saja belum tentu jadi komponen pertama yang start).
 *
 * Libv2ray.initCoreEnv(assetPath, xudpBaseKey) menyiapkan path asset/cert Xray-core.
 * xudpBaseKey dikosongkan karena app ini tidak pakai fitur XUDP encryption khusus.
 */
class XrayLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // WAJIB paling pertama, sebelum baris lain apa pun -- supaya kalau ADA kode di
        // bawah ini yang crash (termasuk Prefs.init/ServerRepository.init/native init),
        // tetap ke-tangkap.
        CrashCatcher.install(this)

        Prefs.init(this)
        ServerRepository.init(this)
        applyThemePref()

        // Dibungkus try-catch: ini panggilan JNI/native ke libv2ray.aar. Kalau karena
        // alasan apa pun native library-nya gagal dimuat (mis. UnsatisfiedLinkError akibat
        // ABI device tidak ke-cover AAR, atau initCoreEnv gagal siapkan asset), SEBELUMNYA
        // ini bikin app force-close total tanpa jejak apa pun begitu dibuka (crash di
        // Application.onCreate() terjadi SEBELUM Activity manapun sempat tampil, jadi tidak
        // ada UI sama sekali buat nunjukkin error-nya). Sekarang minimal app tetap bisa
        // dibuka; fitur connect baru gagal belakangan (dgn pesan jelas) kalau memang ini
        // akar masalahnya.
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

    /** Settings > General > "Network log": catat network up/down (tipe: WIFI/MOBILE) ke Logger. */
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
                else -> AppCompatDelegate.MODE_NIGHT_YES // "dark"
            }
        )
    }

    /** Meniru baris log NetMod: versi app, versi core Xray, info device — muncul di "Show logs". */
    private fun logStartupInfo() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        Logger.log("App version $versionName build $versionCode")

        // checkVersionX() dari AndroidLibXrayLite mengembalikan string versi
        // libv2ray-binding + versi Xray-core sekaligus (dicek ke source resminya,
        // fungsi Go: `func CheckVersionX() string`). Formatnya bebas ditentukan upstream,
        // jadi ditampilkan apa adanya, tidak diparse manual.
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
