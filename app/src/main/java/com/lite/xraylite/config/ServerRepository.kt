package com.lite.xraylite.config

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lite.xraylite.model.ServerConfig

object ServerRepository {

    private const val FILE = "og_tunnel_servers"
    private const val KEY_SERVERS = "servers_json"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val CURRENT_SCHEMA_VERSION = 2

    private lateinit var sp: SharedPreferences
    private val gson = Gson()
    private val servers = LinkedHashMap<String, ServerConfig>()

    var activeId: String? = null
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val savedVersion = sp.getInt(KEY_SCHEMA_VERSION, 1)
        if (savedVersion < CURRENT_SCHEMA_VERSION) {
            sp.edit().remove(KEY_SERVERS).remove(KEY_ACTIVE).putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
            return
        }
        val json = sp.getString(KEY_SERVERS, null)
        if (!json.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<List<ServerConfig>>() {}.type
                val list: List<ServerConfig> = gson.fromJson(json, type)
                list.forEach { servers[it.id] = it }
            }
        }
        activeId = sp.getString(KEY_ACTIVE, null)
    }

    private fun persist() {
        sp.edit()
            .putString(KEY_SERVERS, gson.toJson(servers.values.toList()))
            .putString(KEY_ACTIVE, activeId)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .apply()
    }

    object SessionStats {
        var uploadBytes: Long = 0L
        var downloadBytes: Long = 0L
        var connectedSinceMs: Long = 0L

        fun reset() {
            uploadBytes = 0L
            downloadBytes = 0L
            connectedSinceMs = System.currentTimeMillis()
        }
    }

    private val pingResults = HashMap<String, Int?>()

    fun setPing(id: String, ms: Int?) { pingResults[id] = ms }
    fun getPing(id: String): Int? = pingResults[id]

    fun add(cfg: ServerConfig) {
        servers[cfg.id] = cfg
        persist()
    }

    fun remove(id: String) {
        servers.remove(id)
        pingResults.remove(id)
        if (activeId == id) activeId = null
        persist()
    }

    fun all(): List<ServerConfig> = servers.values.toList()

    fun get(id: String?): ServerConfig? = id?.let { servers[it] }

    fun setActive(id: String) {
        activeId = id
        persist()
    }

    fun activeConfig(): ServerConfig? = get(activeId)
}
