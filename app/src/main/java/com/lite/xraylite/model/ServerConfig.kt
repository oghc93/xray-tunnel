package com.lite.xraylite.model

/** Jenis koneksi yang didukung. SSH di sini = SSH murni (port-forward), bukan Xray. */
enum class ConnectionType { VLESS, VMESS, TROJAN, SSH }

/**
 * Representasi satu profil server. Field yang tidak relevan untuk suatu
 * ConnectionType boleh dibiarkan default (misal password tidak dipakai VLESS).
 */
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val address: String,
    val port: Int,

    // Xray (VLESS/VMess/Trojan) fields
    val uuidOrPassword: String = "",
    val network: String = "ws",          // ws, tcp, grpc
    val wsPath: String = "/",
    val wsHost: String = "",
    val security: String = "tls",        // none, tls, reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",        // uTLS fingerprint: chrome/firefox/safari/ios/android/random (param "fp")
    val allowInsecure: Boolean = false,
    val flow: String = "",               // dipakai VLESS (xtls-rprx-vision dll)

    // REALITY fields (dipakai kalau security == "reality")
    val realityPublicKey: String = "",   // param "pbk"
    val realityShortId: String = "",     // param "sid"
    val realitySpiderX: String = "",     // param "spx"

    // SSH murni fields
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshPrivateKeyPem: String = "",
    val localSocksPort: Int = 1080,

    // SSH murni - mode tambahan (opsional, boleh dikombinasikan):
    //  - "Mode SNI": sshUseTls=true + sshSni diisi -> socket dibungkus TLS dulu (SNI custom)
    //    sebelum handshake SSH mulai, mirip kolom "TLS type"+"Server name indication" di NetMod.
    //  - "Mode payload + proxy": sshPayload diisi -> kirim HTTP request custom dulu ke socket
    //    (opsional lewat sshProxyHost/sshProxyPort sebagai "bug host" perantara, kalau kosong
    //    payload dikirim langsung ke [address]:[port]) sebelum handshake SSH mulai.
    // Semua field ini boleh kosong sekaligus -> balik ke SSH polos seperti sebelumnya.
    val sshUseTls: Boolean = false,
    val sshSni: String = "",
    val sshPayload: String = "",
    val sshProxyHost: String = "",
    val sshProxyPort: Int = 0
)
