package com.lite.xraylite.ssh

import android.content.Context
import android.os.PowerManager
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.util.Logger
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Tunnel SSH murni: bikin local SOCKS/port-forward via SSH direct-tcpip channel
 * (`LocalPortForwarder` bawaan sshj), DENGAN dukungan tambahan buat 2 mode "bug host"
 * yang umum dipakai app tunnel SSH (HTTP Injector, NapsternetV, dkk):
 *
 *  - **Mode SNI**: [ServerConfig.sshUseTls] + [ServerConfig.sshSni] -> socket dibungkus TLS
 *    dengan SNI custom SEBELUM handshake protokol SSH dimulai. Berguna kalau ISP/DPI
 *    memblokir berdasarkan SNI, jadi kita "menyamar" pakai SNI domain lain yang tidak diblokir.
 *  - **Mode payload + proxy**: [ServerConfig.sshPayload] (+ opsional
 *    [ServerConfig.sshProxyHost]/[ServerConfig.sshProxyPort] sebagai host perantara/"bug host")
 *    -> kirim request HTTP custom dulu ke socket sebelum handshake SSH, memanfaatkan bug host
 *    yang meloloskan trafik awal secara gratis/prioritas di jaringan operator tertentu.
 *
 * Kedua mode ini BISA digabung (TLS dulu, baru payload dikirim di dalam socket TLS itu), atau
 * dikosongkan semua -> balik ke SSH polos seperti sebelumnya. Implementasinya lewat custom
 * `javax.net.SocketFactory` yang di-inject ke SSHClient (`ssh.socketFactory = ...`) — ini cara
 * yang didokumentasikan resmi sshj sendiri untuk connect via proxy/socket custom (lihat
 * commit hierynomus/sshj#fc535a5 & issue #170), BUKAN reka-reka sendiri.
 */
class SshTunnelManager(private val appContext: Context) {

    private var client: SSHClient? = null
    private var forwarder: LocalPortForwarder? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectAttempt = 0

    /**
     * Konek & buka local port-forward. Blocking di thread background sendiri.
     * [onConnected] dipanggil sekali begitu forward mulai listen di 127.0.0.1:[ServerConfig.localSocksPort]
     * (siap dijadikan outbound SOCKS oleh XrayVpnService). [onError] dipanggil kalau gagal
     * konek/auth dan sudah kehabisan jatah reconnect, ATAU forward terputus bukan karena
     * [disconnect] dipanggil.
     */
    fun connect(cfg: ServerConfig, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        stopRequested = false
        reconnectAttempt = 0
        acquireWakeLock()
        attemptConnect(cfg, onConnected, onError)
    }

    private fun attemptConnect(cfg: ServerConfig, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        executor.execute {
            var serverSocket: ServerSocket? = null
            try {
                Logger.log("Starting service")
                val modeDesc = buildString {
                    append("protocol: ssh")
                    if (cfg.sshUseTls) append(", tls type: tls, sni: ${cfg.sshSni.ifBlank { cfg.address }}")
                    if (cfg.sshPayload.isNotBlank()) append(", payload: aktif")
                    if (cfg.sshProxyHost.isNotBlank()) append(", proxy: ${cfg.sshProxyHost}:${cfg.sshProxyPort}")
                }
                Logger.log("Using config remarks: ${cfg.name}, address: ${cfg.address}:${cfg.port}, $modeDesc")

                val ssh = SSHClient()
                ssh.addHostKeyVerifier(PromiscuousVerifier()) // TODO: ganti verifier fingerprint asli
                ssh.connectTimeout = 15000

                // Inject SocketFactory custom HANYA kalau ada mode tambahan yang perlu socket
                // "disiapkan" dulu (TLS/SNI dan/atau payload+proxy). Kalau tidak, biarkan sshj
                // pakai SocketFactory bawaannya (plain TCP) supaya perilaku SSH polos tidak berubah.
                if (cfg.sshUseTls || cfg.sshPayload.isNotBlank()) {
                    ssh.socketFactory = FrontingSocketFactory(cfg)
                }
                ssh.connect(cfg.address, cfg.port)

                if (cfg.sshPrivateKeyPem.isNotBlank()) {
                    // cfg.sshPrivateKeyPem berisi ISI PEM (bukan path file) -> WAJIB lewat overload
                    // loadKeys(privateKey, publicKey, passwordFinder). Overload loadKeys(String
                    // location) memperlakukan argumennya sebagai PATH FILE DI DISK, bukan konten
                    // key, dan bakal selalu gagal (FileNotFound) kalau dikasih isi PEM langsung.
                    val keyProvider = ssh.loadKeys(cfg.sshPrivateKeyPem, null, null)
                    ssh.authPublickey(cfg.sshUsername, keyProvider)
                } else {
                    ssh.authPassword(cfg.sshUsername, cfg.sshPassword)
                }

                // remoteHost/remotePort = layanan di sisi VPS yang mau ditembus lewat channel SSH
                // (default asumsi VPS sudah jalankan SOCKS lokal di 127.0.0.1:1080).
                val params = Parameters("127.0.0.1", cfg.localSocksPort, "127.0.0.1", 1080)

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", cfg.localSocksPort))
                }

                client = ssh
                val fwd = ssh.newLocalPortForwarder(params, serverSocket)
                forwarder = fwd

                reconnectAttempt = 0
                Logger.success("Connecting to server")
                onConnected()
                fwd.listen() // blocking; balik normal begitu forwarder.close() dipanggil dari disconnect()
            } catch (t: Throwable) {
                if (!stopRequested) {
                    handleDisconnected(cfg, t, onConnected, onError)
                }
            } finally {
                runCatching { serverSocket?.close() }
                runCatching { client?.disconnect() }
                client = null
                forwarder = null
            }
        }
    }

    /** Putus tak terduga (bukan user disconnect) -> coba reconnect sesuai Settings, kalau habis jatah baru lapor error. */
    private fun handleDisconnected(cfg: ServerConfig, t: Throwable, onConnected: () -> Unit, onError: (Throwable) -> Unit) {
        val maxAttempt = Prefs.sshReconnectMaxAttempt
        if (reconnectAttempt >= maxAttempt) {
            releaseWakeLock()
            Logger.error("Gagal konek setelah $reconnectAttempt percobaan: ${t.message}")
            onError(t)
            return
        }
        reconnectAttempt++
        Logger.error("Koneksi SSH putus (${t.message}), reconnect percobaan $reconnectAttempt/$maxAttempt...")
        Thread.sleep(Prefs.sshReconnectIntervalMs.toLong().coerceAtLeast(0))
        if (!stopRequested) attemptConnect(cfg, onConnected, onError)
    }

    private fun acquireWakeLock() {
        if (!Prefs.sshWakeLockEnabled) return
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OGTunnel:SshWakeLock")
            wl.acquire(6 * 60 * 60 * 1000L) // batas aman 6 jam, dilepas manual saat disconnect
            wakeLock = wl
            Logger.success("WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        if (wakeLock != null) Logger.success("WakeLock released")
        wakeLock = null
    }

    fun disconnect() {
        stopRequested = true
        // forwarder.close() meng-interrupt thread listen() DAN menutup ServerSocket-nya, jadi
        // accept() yang lagi ngeblok langsung keluar dan loop-nya benar-benar berhenti.
        runCatching { forwarder?.close() }
        runCatching { client?.disconnect() }
        releaseWakeLock()
        Logger.log("Service stopped")
    }

    /** true selama forwarder SSH masih listen (dipakai XrayVpnService buat nunggu sebelum start TUN). */
    fun isForwarderActive(): Boolean = forwarder != null

    // -----------------------------------------------------------------------
    // SocketFactory custom: menyiapkan socket (opsional TLS+SNI, opsional lewat
    // proxy/bug-host, opsional kirim payload HTTP) SEBELUM diserahkan ke sshj
    // sebagai "socket TCP polos" tempat handshake SSH akan dimulai.
    // -----------------------------------------------------------------------
    private class FrontingSocketFactory(private val cfg: ServerConfig) : SocketFactory() {

        override fun createSocket(host: String, port: Int): Socket = buildSocket()
        override fun createSocket(host: String, port: Int, localAddr: java.net.InetAddress, localPort: Int): Socket = buildSocket()
        override fun createSocket(address: java.net.InetAddress, port: Int): Socket = buildSocket()
        override fun createSocket(address: java.net.InetAddress, port: Int, localAddr: java.net.InetAddress, localPort: Int): Socket = buildSocket()

        private fun buildSocket(): Socket {
            // Kalau sshProxyHost diisi -> itu "bug host"/proxy perantara yang dituju duluan.
            // Kalau kosong -> payload/TLS langsung diarahkan ke address:port SSH aslinya.
            val connectHost = cfg.sshProxyHost.ifBlank { cfg.address }
            val connectPort = if (cfg.sshProxyHost.isNotBlank() && cfg.sshProxyPort > 0) cfg.sshProxyPort else cfg.port

            var socket: Socket = Socket()
            socket.connect(InetSocketAddress(connectHost, connectPort), 15000)

            if (cfg.sshUseTls) {
                socket = wrapTls(socket, connectHost, connectPort, cfg.sshSni.ifBlank { connectHost })
            }

            if (cfg.sshPayload.isNotBlank()) {
                sendPayloadAndDrainResponse(socket, cfg.sshPayload, cfg.address, cfg.port)
            }

            return socket
        }

        private fun wrapTls(plain: Socket, host: String, port: Int, sni: String): SSLSocket {
            // TrustManager "terima semua sertifikat" -- konsisten dengan PromiscuousVerifier()
            // yang sudah dipakai untuk host key SSH-nya sendiri; SNI-fronting memang lazimnya
            // menembus lewat domain depan yang sertifikatnya bukan milik SSH server aslinya,
            // jadi verifikasi sertifikat penuh di sini tidak relevan buat kasus ini.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            val ssl = ctx.socketFactory.createSocket(plain, sni, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            ssl.sslParameters = params
            ssl.startHandshake()
            return ssl
        }

        /** Kirim payload custom lalu buang baris respons HTTP awal (kalau ada) sebelum SSH mulai. */
        private fun sendPayloadAndDrainResponse(socket: Socket, rawPayload: String, sshHost: String, sshPort: Int) {
            val payload = rawPayload
                .replace("[host]", sshHost)
                .replace("[port]", sshPort.toString())
                .replace("[crlf]", "\r\n")
                .replace("[crlfcrlf]", "\r\n\r\n")
            val out: OutputStream = socket.getOutputStream()
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()

            // Baca & buang sampai ketemu "\r\n\r\n" (akhir header respons HTTP dari bug host) --
            // byte SSH banner ("SSH-2.0-...") yang sesungguhnya menyusul PERSIS setelah ini di
            // stream yang sama. CATATAN JUJUR: java.net.Socket tidak punya "unread"/pushback,
            // jadi fungsi ini murni ASUMSI bug host-nya benar-benar membalas dengan header HTTP
            // dulu (kontrak umum trik payload+bug-host). Kalau ternyata bug host kamu langsung
            // meneruskan byte SSH mentah tanpa respons HTTP apa pun, pendekatan ini akan salah
            // makan sebagian banner SSH -- kalau itu terjadi, kosongkan saja field Payload
            // (mode SNI/proxy saja tanpa payload tetap jalan seperti biasa).
            val input: InputStream = socket.getInputStream()
            var prev = intArrayOf(-1, -1, -1, -1)
            var totalRead = 0
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline && totalRead < 8192) {
                val b = input.read()
                if (b == -1) break
                prev[0] = prev[1]; prev[1] = prev[2]; prev[2] = prev[3]; prev[3] = b
                totalRead++
                if (prev[0] == '\r'.code && prev[1] == '\n'.code && prev[2] == '\r'.code && prev[3] == '\n'.code) break
            }
        }
    }
}
