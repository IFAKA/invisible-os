package com.faka.invisible

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the local AI model on first use (it is 2.6 GB, so it is not in the APK). Wi-Fi only, resumes after a stop. */
object ModelDownload {
    private const val TAG = "INVISIBLE_AI"
    const val URL_ = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val NEED_FREE = 3_300_000_000L   // model + room to move it

    /** What the app screen shows. Plain text so it can change without a redraw type. */
    @Volatile var status: String = ""
    @Volatile private var running = false

    fun file(ctx: Context) = File(ctx.filesDir, "gemma4.litertlm")
    fun ready(ctx: Context) = file(ctx).exists()

    /** "41%  (1.1 of 2.6 GB)" */
    fun progressText(done: Long, total: Long) = if (total <= 0) "Starting…" else "${(done * 100 / total)}%  (${"%.1f".format(done / 1e9)} of ${"%.1f".format(total / 1e9)} GB)"

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Starts (or resumes) the download on a worker thread. [changed] is called on that thread when [status] changes. */
    @Synchronized fun start(ctx: Context, changed: () -> Unit) {
        if (running || ready(ctx)) return
        if (!onWifi(ctx)) { status = "Connect to Wi-Fi first (2.6 GB)"; changed(); return }
        if (ctx.filesDir.usableSpace < NEED_FREE) { status = "Not enough free space (needs 3.3 GB)"; changed(); return }
        running = true
        Thread {
            val part = File(ctx.filesDir, "gemma4.litertlm.part")
            try {
                var have = if (part.exists()) part.length() else 0L
                val c = URL(URL_).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.connect()
                if (c.responseCode == 200) have = 0   // the server ignored the range: start over
                else if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = have + c.contentLengthLong
                RandomAccessFile(part, "rw").use { f ->
                    f.seek(have); if (have == 0L) f.setLength(0)
                    val buf = ByteArray(256 * 1024); var last = 0L
                    c.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            f.write(buf, 0, n); have += n
                            if (have - last > total / 100) { last = have; status = "Downloading " + progressText(have, total); changed() }
                            if (!onWifi(ctx)) throw java.io.IOException("Wi-Fi lost")
                        }
                    }
                }
                if (have < total) throw java.io.IOException("stopped early")
                if (!part.renameTo(file(ctx))) throw java.io.IOException("could not move the file")
                status = "Ready"
            } catch (e: Throwable) {
                Log.e(TAG, "model download stopped", e)
                status = "Stopped (${e.message}). Tap Download to continue."
            } finally { running = false; changed() }
        }.start()
    }
}
