package com.lite.xraylite.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.lite.xraylite.BuildConfig
import com.lite.xraylite.R
import com.lite.xraylite.config.ConfigParser
import com.lite.xraylite.config.ServerRepository
import com.lite.xraylite.databinding.ActivityMainBinding
import com.lite.xraylite.model.ConnectionType
import com.lite.xraylite.model.ServerConfig
import com.lite.xraylite.settings.Prefs
import com.lite.xraylite.util.HttpPingTester
import com.lite.xraylite.util.Logger
import com.lite.xraylite.vpn.XrayVpnService
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ServerListAdapter
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var gestureDetector: GestureDetector
    private var connected = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val monitorTick = object : Runnable {
        override fun run() {
            if (connected) {
                val elapsedMs = System.currentTimeMillis() - ServerRepository.SessionStats.connectedSinceMs
                binding.tvDuration.text = formatDuration(elapsedMs)
                binding.tvUpload.text = formatBytes(ServerRepository.SessionStats.uploadBytes)
                binding.tvDownload.text = formatBytes(ServerRepository.SessionStats.downloadBytes)
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startTunnel()
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawerLayout = findViewById(R.id.drawerLayout)
        setupToolbarAndDrawer()
        setupSwipeToLogs()

        adapter = ServerListAdapter(
            onSelect = { cfg -> onServerSelected(cfg) },
            onMenu = { cfg, anchor -> showItemMenu(cfg, anchor) }
        )
        applyListViewMode()
        binding.rvServers.adapter = adapter

        intent?.data?.toString()?.let { handleIncomingLink(it) }

        binding.btnConnect.setOnClickListener { toggleConnection() }

        refreshList()
        uiHandler.post(monitorTick)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(monitorTick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Toolbar + drawer + swipe ke log
    // ---------------------------------------------------------------------

    private fun setupToolbarAndDrawer() {
        val toolbar = findViewById<Toolbar>(R.id.toolbarMain)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionAddServer -> { showAddServerChooser(); true }
                R.id.actionImportFile -> { importFileLauncher.launch("text/plain"); true }
                R.id.actionSortBy -> { showSortByDialog(); true }
                R.id.actionViewAs -> { showViewAsDialog(); true }
                R.id.actionPingTool -> { pingAllServers(); true }
                R.id.actionFindSelected -> { scrollToActiveServer(); true }
                else -> false
            }
        }

        val navView = findViewById<NavigationView>(R.id.navView)
        navView.getHeaderView(0)?.findViewById<TextView>(R.id.tvNavVersion)?.text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        navView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawers()
            when (item.itemId) {
                R.id.navWhatsMyIp -> launchTool(NetworkToolsActivity.MODE_WHATS_MY_IP)
                R.id.navHostToIp -> launchTool(NetworkToolsActivity.MODE_HOST_TO_IP)
                R.id.navHostChecker -> launchTool(NetworkToolsActivity.MODE_HOST_CHECKER)
                R.id.navShowLogs -> openLogsWithSlide()
                R.id.navSettings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.navAbout -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.navShare -> shareApp()
            }
            true
        }
    }

    /** Geser layar utama ke kiri/kanan -> layar "Show logs" langsung terbuka (bukan cuma lewat drawer). */
    private fun setupSwipeToLogs() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 110 && abs(dx) > abs(dy) * 2 && abs(velocityX) > 400) {
                    openLogsWithSlide()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Jangan tangkap gesture selama drawer lagi kebuka, biar tidak tabrakan sama
        // gesture bawaan DrawerLayout sendiri (swipe dari tepi kiri buat buka drawer).
        if (!drawerLayout.isDrawerOpen(Gravity.START)) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openLogsWithSlide() {
        startActivity(Intent(this, LogActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun launchTool(mode: String) {
        startActivity(Intent(this, NetworkToolsActivity::class.java).putExtra(NetworkToolsActivity.EXTRA_MODE, mode))
    }

    private fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Saya pakai OG Tunnel buat konek ke VPS pribadi saya.")
        }
        startActivity(Intent.createChooser(intent, "Share OG Tunnel"))
    }

    private fun showSortByDialog() {
        val entries = resources.getStringArray(R.array.sort_mode_entries)
        val values = resources.getStringArray(R.array.sort_mode_values)
        val current = values.indexOf(Prefs.sortMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(entries, current) { dialog, which ->
                Prefs.sortMode = values[which]
                refreshList()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showViewAsDialog() {
        val options = arrayOf("List", "Grid")
        val current = if (Prefs.listViewMode == "grid") 1 else 0
        AlertDialog.Builder(this)
            .setTitle("View as")
            .setSingleChoiceItems(options, current) { dialog, which ->
                Prefs.listViewMode = if (which == 1) "grid" else "list"
                applyListViewMode()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyListViewMode() {
        binding.rvServers.layoutManager = if (Prefs.listViewMode == "grid") {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }
    }

    private fun pingAllServers() {
        val all = ServerRepository.all()
        if (all.isEmpty()) {
            Toast.makeText(this, "Belum ada server", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Nge-ping ${all.size} server...", Toast.LENGTH_SHORT).show()
        all.forEach { cfg ->
            com.lite.xraylite.util.PingTester.test(cfg) { success, ms ->
                ServerRepository.setPing(cfg.id, if (success) ms.toInt() else -1)
                runOnUiThread { refreshList() }
            }
        }
    }

    private fun scrollToActiveServer() {
        val activeId = ServerRepository.activeId
        if (activeId == null) {
            Toast.makeText(this, "Belum ada server yang dipilih", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = ServerRepository.all().indexOfFirst { it.id == activeId }
        if (pos >= 0) binding.rvServers.scrollToPosition(pos)
    }

    private fun importFromFile(uri: android.net.Uri) {
        val lines = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: emptyList()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal baca file: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        var imported = 0
        lines.map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            ConfigParser.parse(line)?.let {
                ServerRepository.add(it)
                imported++
            }
        }
        Toast.makeText(this, "$imported dari ${lines.size} baris berhasil diimport", Toast.LENGTH_LONG).show()
        Logger.log("Import from file: $imported/${lines.size} config berhasil ditambahkan")
        if (ServerRepository.activeId == null) ServerRepository.all().firstOrNull()?.let { ServerRepository.setActive(it.id) }
        refreshList()
    }

    // ---------------------------------------------------------------------
    // "+" pojok kanan atas -> pilih Import link / Tambah SSH manual
    // ---------------------------------------------------------------------

    private fun showAddServerChooser() {
        val options = arrayOf("Import link (VLESS/VMess/Trojan)", "Tambah SSH manual")
        AlertDialog.Builder(this)
            .setTitle("Tambah server")
            .setItems(options) { _, which ->
                if (which == 0) showImportLinkDialog() else showAddSshDialog(null)
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Menu per-item: Edit / Copy link / Delete
    // ---------------------------------------------------------------------

    private fun showItemMenu(cfg: ServerConfig, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Edit")
        popup.menu.add(0, 2, 1, "Copy link")
        popup.menu.add(0, 3, 2, "Hapus")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { if (cfg.type == ConnectionType.SSH) showAddSshDialog(cfg) else showEditXrayDialog(cfg); true }
                2 -> { copyShareLink(cfg); true }
                3 -> { confirmDelete(cfg); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyShareLink(cfg: ServerConfig) {
        val link = ConfigParser.toShareLink(cfg)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OG Tunnel share link", link))
        val note = if (cfg.type == ConnectionType.SSH && (cfg.sshUseTls || cfg.sshPayload.isNotBlank()))
            "Link disalin (mode TLS/SNI/payload tidak ikut ke link, cuma host/user/pass)"
        else "Link disalin ke clipboard"
        Toast.makeText(this, note, Toast.LENGTH_LONG).show()
    }

    private fun confirmDelete(cfg: ServerConfig) {
        AlertDialog.Builder(this)
            .setTitle("Hapus ${cfg.name}?")
            .setMessage("Server ini akan dihapus dari daftar.")
            .setPositiveButton("Hapus") { _, _ ->
                if (connected && ServerRepository.activeId == cfg.id) {
                    Toast.makeText(this, "Putuskan koneksi dulu sebelum menghapus server aktif", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ServerRepository.remove(cfg.id)
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Form generik edit VLESS/VMess/Trojan -- satu form dipakai buat ketiganya (label field sama, cuma penyebutan uuid/password beda). */
    private fun showEditXrayDialog(cfg: ServerConfig) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val idLabel = if (cfg.type == ConnectionType.TROJAN) "Password" else "UUID"
        val etName = EditText(this).apply { hint = "Remarks"; setText(cfg.name) }
        val etAddress = EditText(this).apply { hint = "Address"; setText(cfg.address) }
        val etPort = EditText(this).apply { hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER; setText(cfg.port.toString()) }
        val etId = EditText(this).apply { hint = idLabel; setText(cfg.uuidOrPassword) }
        val etNetwork = EditText(this).apply { hint = "Transfer protocol (ws/tcp/grpc)"; setText(cfg.network) }
        val etPath = EditText(this).apply { hint = "Path"; setText(cfg.wsPath) }
        val etHost = EditText(this).apply { hint = "Host header"; setText(cfg.wsHost) }
        val etSecurity = EditText(this).apply { hint = "Security (none/tls/reality)"; setText(cfg.security) }
        val etSni = EditText(this).apply { hint = "SNI"; setText(cfg.sni) }
        val etFlow = EditText(this).apply { hint = "Flow (khusus VLESS, boleh kosong)"; setText(cfg.flow) }
        val etFingerprint = EditText(this).apply { hint = "Fingerprint (fp)"; setText(cfg.fingerprint) }
        val etAlpn = EditText(this).apply { hint = "ALPN (pisah koma)"; setText(cfg.alpn) }
        val cbInsecure = CheckBox(this).apply { text = "Allow insecure (TLS)"; isChecked = cfg.allowInsecure }

        val fields = listOf(etName, etAddress, etPort, etId, etNetwork, etPath, etHost, etSecurity, etSni, etFlow, etFingerprint, etAlpn, cbInsecure)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            fields.forEach { addView(it); (it.layoutParams as LinearLayout.LayoutParams).topMargin = pad / 2 }
        }

        AlertDialog.Builder(this)
            .setTitle("Edit ${cfg.type}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Simpan") { _, _ ->
                val updated = cfg.copy(
                    name = etName.text.toString().trim().ifBlank { cfg.name },
                    address = etAddress.text.toString().trim(),
                    port = etPort.text.toString().toIntOrNull() ?: cfg.port,
                    uuidOrPassword = etId.text.toString().trim(),
                    network = etNetwork.text.toString().trim().ifBlank { "ws" },
                    wsPath = etPath.text.toString().trim().ifBlank { "/" },
                    wsHost = etHost.text.toString().trim(),
                    security = etSecurity.text.toString().trim().ifBlank { "tls" },
                    sni = etSni.text.toString().trim(),
                    flow = etFlow.text.toString().trim(),
                    fingerprint = etFingerprint.text.toString().trim(),
                    alpn = etAlpn.text.toString().trim(),
                    allowInsecure = cbInsecure.isChecked
                )
                ServerRepository.add(updated) // id sama -> menimpa entri lama
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Form SSH: dipakai buat TAMBAH ([existing] = null) MAUPUN EDIT ([existing] terisi).
     * Menyertakan mode tambahan: TLS+SNI ("mode SNI") dan Payload+Proxy ("mode payload+proxy"),
     * keduanya opsional dan bisa digabung.
     */
    private fun showAddSshDialog(existing: ServerConfig?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val etHost = EditText(this).apply { hint = "Host / IP VPS"; setText(existing?.address ?: "") }
        val etPort = EditText(this).apply {
            hint = "Port SSH (default 22)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.port ?: 22).toString())
        }
        val etUser = EditText(this).apply { hint = "Username"; setText(existing?.sshUsername ?: "") }
        val etPass = EditText(this).apply {
            hint = "Password (kosongkan kalau pakai private key)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.sshPassword ?: "")
        }
        val etKey = EditText(this).apply {
            hint = "Private key PEM (opsional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; isSingleLine = false
            setText(existing?.sshPrivateKeyPem ?: "")
        }
        val etLocalPort = EditText(this).apply {
            hint = "Local SOCKS port (default ${Prefs.socksLocalPort})"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.localSocksPort ?: Prefs.socksLocalPort).toString())
        }

        val sectionModeLabel = TextView(this).apply {
            text = "Mode tambahan (opsional, boleh dikosongkan semua)"
            setPadding(0, pad, 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val cbTls = CheckBox(this).apply { text = "Mode SNI (bungkus TLS + SNI custom)"; isChecked = existing?.sshUseTls ?: false }
        val etSni = EditText(this).apply {
            hint = "SNI (mis. domain CDN yang tidak diblokir)"
            setText(existing?.sshSni ?: "")
            visibility = if (cbTls.isChecked) View.VISIBLE else View.GONE
        }
        cbTls.setOnCheckedChangeListener { _, checked -> etSni.visibility = if (checked) View.VISIBLE else View.GONE }

        val etPayload = EditText(this).apply {
            hint = "Payload HTTP (mode payload+proxy). Token: [host] [port] [crlf] [crlfcrlf]"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; isSingleLine = false
            setText(existing?.sshPayload ?: "")
        }
        val etProxyHost = EditText(this).apply {
            hint = "Proxy / bug host (opsional, kosongkan = payload langsung ke host SSH di atas)"
            setText(existing?.sshProxyHost ?: "")
        }
        val etProxyPort = EditText(this).apply {
            hint = "Proxy port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if ((existing?.sshProxyPort ?: 0) > 0) existing?.sshProxyPort.toString() else "")
        }

        val allFields = listOf(etHost, etPort, etUser, etPass, etKey, etLocalPort, sectionModeLabel, cbTls, etSni, etPayload, etProxyHost, etProxyPort)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            allFields.forEach { addView(it); (it.layoutParams as LinearLayout.LayoutParams).topMargin = pad / 2 }
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Tambah server SSH murni" else "Edit SSH")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Simpan") { _, _ ->
                val host = etHost.text.toString().trim()
                val user = etUser.text.toString().trim()
                if (host.isEmpty() || user.isEmpty()) {
                    Toast.makeText(this, "Host & username wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cfg = ServerConfig(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = existing?.name?.takeIf { it.isNotBlank() } ?: host,
                    type = ConnectionType.SSH,
                    address = host,
                    port = etPort.text.toString().toIntOrNull() ?: 22,
                    sshUsername = user,
                    sshPassword = etPass.text.toString(),
                    sshPrivateKeyPem = etKey.text.toString().trim(),
                    localSocksPort = etLocalPort.text.toString().toIntOrNull() ?: Prefs.socksLocalPort,
                    sshUseTls = cbTls.isChecked,
                    sshSni = etSni.text.toString().trim(),
                    sshPayload = etPayload.text.toString(),
                    sshProxyHost = etProxyHost.text.toString().trim(),
                    sshProxyPort = etProxyPort.text.toString().toIntOrNull() ?: 0
                )
                ServerRepository.add(cfg)
                if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Daftar server + koneksi
    // ---------------------------------------------------------------------

    private fun refreshList() {
        adapter.submit(sortedServers(), ServerRepository.activeId)
        val active = ServerRepository.activeConfig()
        binding.tvServerName.text = active?.let { "${it.name} (${it.type})" } ?: "Belum ada server dipilih"
    }

    private fun sortedServers(): List<ServerConfig> {
        val all = ServerRepository.all()
        return when (Prefs.sortMode) {
            "ping" -> all.sortedBy { ServerRepository.getPing(it.id) ?: Int.MAX_VALUE }
            "type" -> all.sortedBy { it.type.name }
            else -> all.sortedBy { it.name.lowercase() }
        }
    }

    private fun onServerSelected(cfg: ServerConfig) {
        if (connected) return
        ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun showImportLinkDialog() {
        val input = EditText(this).apply {
            hint = "Tempel link vless:// vmess:// trojan://"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Import link")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ -> handleIncomingLink(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleIncomingLink(link: String) {
        val cfg = ConfigParser.parse(link)
        if (cfg == null) {
            Toast.makeText(this, "Link tidak valid", Toast.LENGTH_SHORT).show()
            return
        }
        ServerRepository.add(cfg)
        if (ServerRepository.activeId == null) ServerRepository.setActive(cfg.id)
        refreshList()
    }

    private fun toggleConnection() {
        val cfg = ServerRepository.activeConfig()
        if (cfg == null) {
            Toast.makeText(this, "Pilih atau tambah server dulu (tombol + di kanan atas)", Toast.LENGTH_SHORT).show()
            return
        }
        if (connected) {
            stopTunnel(cfg)
        } else {
            Logger.log("Menghubungkan ke ${cfg.name} (${cfg.type})...")
            openLogsWithSlide() // biar proses konek langsung kelihatan logny, tanpa harus buka drawer dulu
            requestVpnPermissionThenStart()
        }
    }

    /**
     * SEMUA tipe server (termasuk SSH) sekarang lewat izin VpnService + XrayVpnService --
     * sebelumnya SSH punya jalur sendiri (sshManager langsung di sini) yang TIDAK pernah
     * benar-benar merutekan trafik device (cuma bikin local port-forward doang, tidak ada
     * TUN sama sekali). Sekarang XrayVpnService yang urus SSH juga (lihat komentar di sana).
     */
    private fun requestVpnPermissionThenStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startTunnel()
    }

    private fun startTunnel() {
        val cfg = ServerRepository.activeConfig() ?: return
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_CONNECT
            putExtra(XrayVpnService.EXTRA_CONFIG_ID, cfg.id)
        }
        startForegroundService(intent)
        ServerRepository.SessionStats.reset()
        setConnectedUi(true)
        HttpPingTester.start()
    }

    private fun stopTunnel(cfg: ServerConfig) {
        HttpPingTester.stop()
        startService(Intent(this, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_DISCONNECT })
        Logger.log("Terputus dari ${cfg.name}")
        setConnectedUi(false)
    }

    private fun setConnectedUi(isConnected: Boolean) {
        connected = isConnected
        binding.tvStatus.text = if (isConnected) "Terhubung" else "Terputus"
        binding.btnConnect.text = if (isConnected) "DISCONNECT" else "CONNECT"
        if (!isConnected) {
            binding.tvDuration.text = "00:00:00"
            binding.tvUpload.text = "0 KB"
            binding.tvDownload.text = "0 KB"
        }
        refreshList()
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }
}
