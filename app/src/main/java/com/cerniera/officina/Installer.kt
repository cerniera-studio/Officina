package com.cerniera.officina

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Installs a build straight from its release (16 Nov 2026): the APK is fetched, then handed to
 * Android's PackageInstaller in a session. Android asks once per app before Officina may update it;
 * after that, updates to apps Officina installed itself can go through without asking.
 */
object Installer {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** What the screen shows while a build installs: progress 0 to 1, or the end with an error or none. */
    interface Watcher { fun progress(f: Float); fun done(error: String?) }
    @Volatile var watcher: Watcher? = null

    fun install(c: Context, title: String, asset: Asset) {
        val app = c.applicationContext
        io.execute {
            runCatching {
                val file = File(app.cacheDir, "build.apk")
                var url = URL(asset.url)
                var conn: HttpURLConnection
                // Release files redirect to GitHub's file host: followed by hand, so nothing is lost on the way
                var hops = 0
                while (true) {
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                        setRequestProperty("User-Agent", "Cerniera-Officina")
                    }
                    if (conn.responseCode in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    break
                }
                if (conn.responseCode != 200) throw java.io.IOException("Download answered ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var got = 0L; var last = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0 && got - last > 128 * 1024) { last = got; val f = got.toFloat() / total * 0.85f; main.post { watcher?.progress(f) } }
                        }
                    }
                }
                conn.disconnect()
                main.post { watcher?.progress(0.9f) }

                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    file.inputStream().use { input -> s.openWrite("base.apk", 0, file.length()).use { out -> input.copyTo(out); s.fsync(out) } }
                    val back = Intent(app, Installed::class.java).putExtra("title", title)
                    s.commit(PendingIntent.getBroadcast(app, id, back, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
                }
            }.onFailure { e -> main.post { watcher?.done(e.message ?: e.javaClass.simpleName) } }
        }
    }
}

/** Android's answer to an install: asks the person where it must, and reports the end. */
class Installed : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.watcher?.done(null)
            else -> Installer.watcher?.done(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install stopped ($status)")
        }
    }
}

/** One item of a checklist: its heading, its words, and whether the file already has it ticked. */
data class Check(val section: String, val text: String, val doneInFile: Boolean)

/**
 * FOLD_CHECKS.md: "## Heading" lines, then "- [ ] item" or "- [x] item". Ticks made on the phone
 * are kept here per app and item, until they are saved back to the file.
 */
object Checks {
    private val item = Regex("^\\s*[-*] \\[( |x|X)] (.+)$")

    fun parse(md: String): List<Check> {
        var section = ""
        return md.lines().mapNotNull { line ->
            if (line.startsWith("## ")) { section = line.removePrefix("## ").trim(); null }
            else item.find(line)?.let { m -> Check(section, m.groupValues[2].trim(), m.groupValues[1] != " ") }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("officina_ticks", Context.MODE_PRIVATE)
    private fun key(repo: String, text: String) = "$repo|${text.hashCode()}"

    fun ticked(c: Context, repo: String, ch: Check) = ch.doneInFile || prefs(c).getBoolean(key(repo, ch.text), false)
    fun tick(c: Context, repo: String, ch: Check, on: Boolean) = prefs(c).edit().putBoolean(key(repo, ch.text), on).apply()

    /** The file with this phone's ticks written into it. */
    fun withTicks(c: Context, repo: String, md: String): String = md.lines().joinToString("\n") { line ->
        val m = item.find(line)
        if (m != null && m.groupValues[1] == " " && prefs(c).getBoolean(key(repo, m.groupValues[2].trim()), false)) line.replaceFirst("[ ]", "[x]") else line
    }

    /** Ticks made here and not yet in the file. */
    fun pending(c: Context, repo: String, list: List<Check>) = list.count { !it.doneInFile && prefs(c).getBoolean(key(repo, it.text), false) }

    /** Plain words, to paste into a chat: what was seen on the Fold and what wasn't yet. */
    fun report(c: Context, title: String, build: String, repo: String, list: List<Check>): String = buildString {
        append("$title, $build, checked on the Fold (Officina, ")
        append(java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date()))
        append(")\n")
        list.groupBy { it.section }.forEach { (section, items) ->
            if (section.isNotBlank()) append("\n$section\n")
            items.forEach { append(if (ticked(c, repo, it)) "✓ " else "· ").append(it.text).append('\n') }
        }
    }
}
