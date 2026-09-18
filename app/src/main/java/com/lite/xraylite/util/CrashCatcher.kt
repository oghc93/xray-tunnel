package com.lite.xraylite.util

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tangkap crash (uncaught exception) dan simpan stack trace-nya ke SharedPreferences,
 * supaya bisa ditampilkan lagi pas app dibuka BERIKUTNYA -- tanpa perlu logcat/adb/root/
 * Shizuku sama sekali. Dipasang di [com.lite.xraylite.XrayLiteApp.onCreate] paling awal,
 * dan dibaca oleh [com.lite.xraylite.ui.MainActivity] tiap kali dibuka.
 *
 * Pakai commit() (SYNCHRONOUS), BUKAN apply() (async) -- proses akan segera mati setelah
 * uncaught exception handler ini selesai, jadi apply() berisiko belum sempat benar-benar
 * ke-flush ke disk kalau tidak ditunggu secara synchronous.
 */
object CrashCatcher {
    private const val FILE = "og_tunnel_crash"
    private const val KEY_TRACE = "last_crash"

    private lateinit var sp: SharedPreferences

    fun install(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                sp.edit().putString(KEY_TRACE, sw.toString()).commit()
            }
            // Tetap panggil handler bawaan sistem setelahnya, supaya perilaku default
            // Android (dialog "App has stopped", proses dimatikan, dsb) tidak berubah --
            // CrashCatcher ini murni "menguping" crash-nya, bukan menggantikannya.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Ambil crash terakhir (kalau ada) SEKALIGUS hapus dari penyimpanan (sekali tampil, habis). */
    fun consumeLastCrash(): String? {
        if (!::sp.isInitialized) return null
        val trace = sp.getString(KEY_TRACE, null)
        if (trace != null) sp.edit().remove(KEY_TRACE).apply()
        return trace
    }
}
