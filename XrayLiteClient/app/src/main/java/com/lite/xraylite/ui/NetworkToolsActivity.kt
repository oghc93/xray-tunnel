package com.lite.xraylite.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lite.xraylite.R
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Satu Activity buat 3 tool jaringan ringan yang ada di drawer menu ("What's my IP?",
 * "Host to IP", "Host checker") — dibuat satu kelas supaya tidak menduplikasi
 * boilerplate input+button+result 3x. Mode ditentukan lewat EXTRA_MODE.
 */
class NetworkToolsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_WHATS_MY_IP = "whats_my_ip"
        const val MODE_HOST_TO_IP = "host_to_ip"
        const val MODE_HOST_CHECKER = "host_checker"
        const val MODE_SPEED_TEST = "speed_test"
    }

    private val executor = Executors.newCachedThreadPool()
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvResult: TextView
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_tools)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_WHATS_MY_IP

        val toolbar = findViewById<Toolbar>(R.id.toolbarTools)
        toolbar.title = titleFor(mode)
        toolbar.setNavigationOnClickListener { finish() }

        etInput = findViewById(R.id.etToolInput)
        btnRun = findViewById(R.id.btnToolRun)
        progress = findViewById(R.id.progressTool)
        tvResult = findViewById(R.id.tvToolResult)

        when (mode) {
            MODE_WHATS_MY_IP -> {
                etInput.visibility = View.GONE
                btnRun.text = "Check my IP"
            }
            MODE_HOST_TO_IP -> {
                etInput.hint = "Hostname, mis. google.com"
                btnRun.text = "Resolve"
            }
            MODE_HOST_CHECKER -> {
                etInput.hint = "host:port, mis. idn.dontol.ccwu.cc:443"
                btnRun.text = "Check host"
            }
            MODE_SPEED_TEST -> {
                etInput.visibility = View.GONE
                btnRun.text = "Mulai Tes Kecepatan"
            }
        }

        btnRun.setOnClickListener { run() }
        if (mode == MODE_WHATS_MY_IP) run()
    }

    private fun titleFor(mode: String) = when (mode) {
        MODE_WHATS_MY_IP -> "What's my IP?"
        MODE_HOST_TO_IP -> "Host to IP"
        MODE_HOST_CHECKER -> "Host checker"
        MODE_SPEED_TEST -> "Tes Kecepatan"
        else -> "Network tools"
    }

    private fun run() {
        when (mode) {
            MODE_WHATS_MY_IP -> runWhatsMyIp()
            MODE_HOST_TO_IP -> runHostToIp(etInput.text.toString().trim())
            MODE_HOST_CHECKER -> runHostChecker(etInput.text.toString().trim())
            MODE_SPEED_TEST -> runSpeedTest()
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnRun.isEnabled = !busy
    }

    private fun runWhatsMyIp() {
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val result = try {
                val conn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json: JsonObject = JsonParser.parseString(body).asJsonObject
                buildString {
                    appendLine("IP       : ${json.get("ip")?.asString ?: "-"}")
                    appendLine("Kota     : ${json.get("city")?.asString ?: "-"}")
                    appendLine("Wilayah  : ${json.get("region")?.asString ?: "-"}")
                    appendLine("Negara   : ${json.get("country_name")?.asString ?: "-"}")
                    append("ISP      : ${json.get("org")?.asString ?: "-"}")
                }
            } catch (e: Exception) {
                "Gagal ambil info IP: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }

    private fun runHostToIp(host: String) {
        if (host.isBlank()) {
            tvResult.text = "Isi hostname dulu"
            return
        }
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val result = try {
                val addrs = java.net.InetAddress.getAllByName(host)
                addrs.joinToString("\n") { it.hostAddress ?: it.toString() }
            } catch (e: Exception) {
                "Gagal resolve $host: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }

    private fun runHostChecker(input: String) {
        if (input.isBlank()) {
            tvResult.text = "Isi host:port dulu"
            return
        }
        val (host, port) = if (input.contains(":")) {
            val parts = input.split(":", limit = 2)
            parts[0] to (parts[1].toIntOrNull() ?: 443)
        } else {
            input to 443
        }
        setBusy(true)
        tvResult.text = ""
        executor.execute {
            val start = System.currentTimeMillis()
            val result = try {
                Socket().use { it.connect(InetSocketAddress(host, port), 5000) }
                val elapsed = System.currentTimeMillis() - start
                "OK — $host:$port terjangkau (${elapsed}ms)"
            } catch (e: Exception) {
                "GAGAL — $host:$port tidak terjangkau: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }

    /**
     * Tes kecepatan sederhana: download dari endpoint speed-test RESMI Cloudflare
     * (speed.cloudflare.com/__down, didokumentasikan publik, dipakai juga oleh
     * speedtest browser resmi Cloudflare) selama maksimal ~10 detik atau 20MB
     * (mana yang lebih dulu tercapai), lalu hitung throughput rata-rata dalam Mbps.
     * Ini mengukur kecepatan internet device APA ADANYA saat itu -- kalau VPN sedang
     * terhubung, otomatis lewat tunnel yang aktif karena seluruh trafik device
     * (termasuk app ini sendiri) memang dirutekan lewat VpnService saat konek.
     */
    private fun runSpeedTest() {
        setBusy(true)
        tvResult.text = "Menyiapkan tes..."
        executor.execute {
            val urlStr = "https://speed.cloudflare.com/__down?bytes=20000000"
            var totalBytes = 0L
            val maxDurationMs = 10_000L
            val start = System.currentTimeMillis()
            val result = try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val elapsed = System.currentTimeMillis() - start
                        if (elapsed > maxDurationMs) break
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (elapsed % 500 < 50) {
                            val mbpsNow = (totalBytes * 8.0) / (elapsed.coerceAtLeast(1) / 1000.0) / 1_000_000.0
                            runOnUiThread { tvResult.text = "Mengukur... ${"%.1f".format(mbpsNow)} Mbps" }
                        }
                    }
                }
                conn.disconnect()
                val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                val mbps = (totalBytes * 8.0) / elapsedSec.coerceAtLeast(0.1) / 1_000_000.0
                val mb = totalBytes / 1_000_000.0
                "Download: ${"%.1f".format(mbps)} Mbps\n(${"%.1f".format(mb)} MB dalam ${"%.1f".format(elapsedSec)} detik)"
            } catch (e: Exception) {
                "Tes gagal: ${e.message}"
            }
            runOnUiThread {
                setBusy(false)
                tvResult.text = result
            }
        }
    }
}
