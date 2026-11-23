package com.cerniera.officina

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Fold
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Screen
import com.cerniera.kit.Sheet
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.eyebrow
import com.cerniera.kit.field
import com.cerniera.kit.pressable
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import com.cerniera.kit.withAlpha
import java.util.concurrent.Executors

class OfficinaApp : Application() {
    override fun onCreate() { super.onCreate(); Crash.install(this, "Officina") }
}

/** One app on the rack: its repo, its builds, its last run, its checklist and its package name. */
data class Bench(val repo: String, val title: String, val appId: String?, val releases: List<Release>, val run: Run?, val checks: ChecksFile?, val retired: Boolean) {
    val newest get() = releases.firstOrNull()
    val onMain get() = releases.firstOrNull { !it.prerelease }
}

/**
 * Officina (16 Nov 2026): the studio's workshop on the phone. Every Cerniera app's latest build and
 * the state of its last run, one tap to install, and the list of things still to see on the Fold,
 * ticked as they are seen and sent back to the repo.
 */
class MainActivity : Activity() {
    private val io = Executors.newFixedThreadPool(4)
    private var benches: List<Bench> = emptyList()
    private var error: String? = null
    private var loading = false
    private var loadedAt = 0L
    private var chosen: String? = null
    private var sheet: Sheet? = null
    private lateinit var content: FrameLayout
    private var overlay: View? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Officina")) return
        Kit.follow(this)
        content = FrameLayout(this)
        setContentView(content)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - loadedAt > 120_000) load() else render()
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); Kit.follow(this); sheet = null; render() }

    // ---- Reading the studio ------------------------------------------------------------------

    private fun load() {
        if (loading) return
        loading = true; render()
        io.execute {
            val result = runCatching {
                val retired = RETIRED
                val known = benches.associateBy { it.repo }
                // One request lists every repo with its last push. Only apps pushed to since they were
                // last read, or whose run was still going, are asked about again; the rest come from
                // what was kept. A quiet refresh costs one request instead of thirty-odd.
                GitHub.repos(this).filter { !it.archived && it.name !in NOT_APPS }.map { r ->
                    io.submit<Bench?> {
                        val name = r.name
                        val fresh = Read.needed(this, name, r.pushed, known[name]?.run)
                        val read = {
                            val releases = GitHub.releases(this, name)
                            if (releases.isEmpty()) null
                            else Bench(name, title(name), GitHub.appId(this, name), releases, runCatching { GitHub.latestRun(this, name) }.getOrNull(),
                                runCatching { GitHub.checks(this, name) }.getOrNull(), name.lowercase() in retired)
                        }
                        // One app failing to read keeps what was last known of it, never sinks the rest
                        runCatching {
                            val b = if (fresh) read() else GitHub.kept(read)
                            if (fresh && !GitHub.limited) Read.done(this, name, r.pushed)
                            b
                        }.getOrElse { known[name] }
                    }
                }.mapNotNull { it.get() }.sortedByDescending { it.newest?.published ?: 0L }
            }
            runOnUiThread {
                loading = false
                result.onSuccess { benches = it; error = null; if (!GitHub.limited) loadedAt = System.currentTimeMillis() }
                    .onFailure { e -> error = if (e is GitHub.Limited || e.cause is GitHub.Limited) limitWords((e as? GitHub.Limited ?: e.cause as GitHub.Limited).resetAt) else (e.message ?: "Couldn't reach GitHub") }
                if (!isDestroyed) render()
            }
        }
    }

    private fun limitWords(at: Long) = "GitHub's hourly limit is reached until " +
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(at)) + ". A token raises it (below)."

    private fun installed(b: Bench): Long? = b.appId?.let { id -> runCatching { packageManager.getPackageInfo(id, 0).longVersionCode }.getOrNull() }

    private fun shown() = benches.filter { !it.retired || Prefs.showRetired(this) }

    // ---- Drawing -----------------------------------------------------------------------------

    private fun wide() = resources.configuration.let { it.screenWidthDp > it.screenHeightDp || it.smallestScreenWidthDp >= 600 }

    private fun render() {
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        content.removeAllViews()
        val screen = Screen(this, "Officina", "Cerniera studio")
        // The pill says what is really the matter: the hourly limit is not being offline
        val limited = GitHub.limited
        screen.status.set(when {
            loading -> "Reading"
            limited -> "Limit until " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(GitHub.limitedUntil))
            error != null -> "Offline"
            else -> "${shown().size} apps"
        }, if (limited || error != null) Kit.WARN else Kit.MUTED)
        val rack = rack()
        screen.add(rack)
        (error ?: if (GitHub.limited) "GitHub's hourly limit is spent, so this is the list as last read. It resets at " +
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(GitHub.limitedUntil)) + "; a token raises the limit (below)." else null)
            ?.let { screen.add(text(it, 13.5f, Kit.WARN).apply { setPadding(px(4), px(10), px(4), 0) }) }
        workshop(screen)

        val panes = Panes(this, screen)
        if (!wide()) {
            content.addView(screen.root)
            panes.record(screen.root, mapOf("rack" to rack, "first-app" to rack.getChildAt(0)))
            return
        }
        // Wide: the rack, and beside it the chosen app's bench, each scrolling on its own
        val pick = shown().firstOrNull { it.repo == chosen } ?: shown().firstOrNull()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Kit.GROUND)
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
            }
        }
        row.addView(screen.root, LinearLayout.LayoutParams(0, -1, 1f))
        val bench = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(28), px(24), px(40)) }
        if (pick != null) bench(pick, bench) else bench.addView(text(if (loading) "Reading the studio…" else "Nothing to show yet.", 15f, Kit.MUTED))
        row.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(bench) }, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(row)
        panes.record(row, mapOf("rack" to rack, "first-app" to rack.getChildAt(0), "bench" to bench))
    }

    /** The rack: one row per app, newest build first, each with the lamp of its last run. */
    private fun rack(): LinearLayout {
        val card = Card(this)
        val list = shown()
        if (list.isEmpty()) card.row(Row(this, if (loading) "Reading the studio…" else "No builds found", if (loading) null else "Pull the latest with Refresh below"))
        list.forEach { b ->
            val newest = b.newest!!
            val have = installed(b)
            val ready = newest.number?.let { n -> have == null || n > have } == true
            val seen = Prefs.seen(this, b.repo) == newest.id
            val end = endValue(when { have == null -> "Install  ›"; ready -> "Update  ›"; else -> "Installed" }, if (ready) Kit.CHAMPAGNE else Kit.MUTED)
            if (ready) end.setOnClickListener { install(b, newest) }
            val sub = buildString {
                append("Build ${newest.number ?: newest.tag}")
                newest.branch?.let { append(" · $it") }
                append(" · ${ago(newest.published)}")
                if (have != null && ready) append("\nOn the phone: build $have")
                checksLeft(b)?.let { if (it > 0) append("\n$it still to see on the Fold") }
            }
            val r = Row(this, b.title, sub, end) { choose(b) }
            card.row(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The lamp of the last run: green passed, amber running, rosso failed
                addView(View(this@MainActivity).apply { background = rounded(lamp(b.run), px(5).toFloat()) },
                    LinearLayout.LayoutParams(px(10), px(10)).apply { marginStart = px(16) })
                addView(r, LinearLayout.LayoutParams(0, -2, 1f))
                // A build not yet looked at carries a champagne dot
                if (!seen) addView(View(this@MainActivity).apply { background = rounded(Kit.CHAMPAGNE, px(3).toFloat()) },
                    LinearLayout.LayoutParams(px(6), px(6)).apply { marginEnd = px(10) })
            })
        }
        return card
    }

    private fun lamp(r: Run?): Int = when {
        r == null -> withAlpha(Kit.MUTED, 0.5f)
        r.status != "completed" -> Kit.WARN
        r.conclusion == "success" -> Kit.GOOD
        r.conclusion == "failure" -> Kit.ROSSO
        else -> Kit.MUTED
    }

    private fun checksLeft(b: Bench): Int? = b.checks?.let { f -> Checks.parse(f.text).count { !Checks.ticked(this, b.repo, it) } }

    private fun choose(b: Bench) {
        chosen = b.repo
        b.newest?.let { Prefs.setSeen(this, b.repo, it.id) }
        if (wide()) { render(); return }
        sheet = Sheet.show(this, b.title, onClose = { sheet = null; render() }) { s -> bench(b, s.body) }
    }

    /** One app's bench: its builds, what is still to see on the Fold, and the emulator's pictures. */
    private fun bench(b: Bench, into: LinearLayout) {
        val have = installed(b)
        into.addView(eyebrow("Builds").apply { setPadding(px(4), px(6), 0, px(10)) })
        val builds = Card(this)
        listOfNotNull(b.newest, b.onMain.takeIf { it != b.newest }).forEach { rel ->
            val n = rel.number
            val canInstall = n != null && (have == null || n > have)
            val end = endValue(when { n != null && have == n -> "Installed"; canInstall -> "Install  ›"; else -> "Older" }, if (canInstall) Kit.CHAMPAGNE else Kit.MUTED)
            builds.row(Row(this, "Build ${n ?: rel.tag}" + (rel.branch?.let { " · $it" } ?: if (!rel.prerelease) " · main" else ""),
                (if (rel.prerelease) "Branch build, " else "Release, ") + ago(rel.published), end) { if (canInstall) install(b, rel) })
        }
        b.run?.let { run ->
            builds.row(Row(this, "Last run: " + when {
                run.status != "completed" -> "running"
                run.conclusion == "success" -> "passed"
                else -> run.conclusion ?: run.status
            }, "${run.name} on ${run.branch}, ${ago(run.created)}", endValue("Open  ›", Kit.CHAMPAGNE)) { view(run.url) })
        }
        if (have != null) builds.row(Row(this, "On the phone", "Build $have"))
        into.addView(builds)
        b.newest?.body?.takeIf { it.isNotBlank() }?.let { notes ->
            into.addView(Fold(this, "Release notes", b.newest!!.name).apply { body.addView(text(notes.trim(), 13.5f, Kit.MUTED)) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        }

        // ---- Still to see on the Fold
        val file = b.checks
        into.addView(eyebrow("Still to see on the Fold"))
        if (file == null) {
            into.addView(text("No FOLD_CHECKS.md in this repo yet.", 13.5f, Kit.MUTED).apply { setPadding(px(4), 0, px(4), 0) })
        } else {
            val items = Checks.parse(file.text)
            items.groupBy { it.section }.forEach { (section, list) ->
                if (section.isNotBlank()) into.addView(text(section, 14f, Kit.MUTED, 450).apply { setPadding(px(4), px(10), 0, px(6)) })
                val card = Card(this)
                list.forEach { ch ->
                    val toggle = Toggle(this, Checks.ticked(this, b.repo, ch)) { on -> Checks.tick(this, b.repo, ch, on) }
                    if (ch.doneInFile) toggle.isEnabled = false
                    card.row(Row(this, ch.text, if (ch.doneInFile) "Ticked in the repo" else null, toggle).apply { if (ch.doneInFile) alpha = 0.55f })
                }
                into.addView(card)
            }
            val build = b.newest?.let { "build ${it.number ?: it.tag}" } ?: ""
            into.addView(primary("Share the report", filled = false) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, Checks.report(this, b.title, build, b.repo, items)), "Share the report"))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
            if (Token.get(this) != null) into.addView(primary("Save ticks to GitHub") { saveTicks(b, file, build) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }

        // ---- The emulator's pictures from the newest build
        val shots = b.newest?.shots.orEmpty().take(8)
        if (shots.isNotEmpty()) {
            into.addView(eyebrow("From the emulator"))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            shots.forEach { a ->
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(Kit.RAISED, px(12).toFloat())
                    contentDescription = a.name
                    setOnClickListener { view(a.url) }
                    pressable()
                }
                strip.addView(img, LinearLayout.LayoutParams(px(96), px(180)).apply { marginEnd = px(10) })
                io.execute {
                    val bmp = runCatching { thumb(a.url) }.getOrNull()
                    runOnUiThread { if (bmp != null) img.setImageBitmap(bmp) }
                }
            }
            into.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        into.addView(Row(this, "Open on GitHub", "github.com/${GitHub.ORG}/${b.repo}", endValue("›", Kit.CHAMPAGNE)) { view("https://github.com/${GitHub.ORG}/${b.repo}") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
    }

    private fun thumb(url: String): android.graphics.Bitmap? {
        val bytes = java.net.URL(url).openStream().use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (o.outHeight / (s * 2) >= px(180)) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun saveTicks(b: Bench, file: ChecksFile, build: String) {
        val items = Checks.parse(file.text)
        val n = Checks.pending(this, b.repo, items)
        if (n == 0) { toastLine("Nothing new to save"); return }
        io.execute {
            val r = runCatching { GitHub.putChecks(this, b.repo, Checks.withTicks(this, b.repo, file.text), file.sha, "Fold checks: $n ticked in Officina ($build)") }
            runOnUiThread {
                toastLine(r.fold({ "Saved $n to ${b.repo}" }, { "Not saved: ${it.message}" }))
                if (r.isSuccess) { sheet?.dismiss(); loadedAt = 0; load() }
            }
        }
    }

    private fun toastLine(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Installing, with everything else stepping back (Concentrato) ------------------------

    private fun install(b: Bench, rel: Release) {
        val asset = rel.apk ?: return
        if (!packageManager.canRequestPackageInstalls()) { askInstalls(); return }
        sheet?.dismiss()
        val wrap = content.getChildAt(0)
        wrap?.animate()?.alpha(0.06f)?.setDuration(Kit.GLIDE)?.start()
        val line = View(this).apply { background = rounded(Kit.CHAMPAGNE, px(2).toFloat()); pivotX = 0f; scaleX = 0f }
        val words = text("Fetching build ${rel.number ?: rel.tag}", 13.5f, Kit.MUTED)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(40), 0, px(40), 0)
            addView(text(b.title, 40f, Kit.INK, displayFace = true))
            addView(FrameLayout(this@MainActivity).apply {
                addView(View(this@MainActivity).apply { background = rounded(Kit.HAIRLINE, px(2).toFloat()) }, FrameLayout.LayoutParams(-1, px(3)))
                addView(line, FrameLayout.LayoutParams(-1, px(3)))
            }, LinearLayout.LayoutParams(px(240), px(3)).apply { topMargin = px(18); bottomMargin = px(12) })
            addView(words)
            isClickable = true
        }
        overlay = box
        content.addView(box, FrameLayout.LayoutParams(-1, -1))
        Installer.watcher = object : Installer.Watcher {
            override fun progress(f: Float) {
                line.animate().scaleX(f).setDuration(Kit.QUICK).start()
                if (f >= 0.9f) words.text = "Installing"
            }
            override fun done(error: String?) {
                line.animate().scaleX(1f).setDuration(Kit.QUICK).start()
                words.text = error ?: "Installed"
                words.setTextColor(if (error == null) Kit.GOOD else Kit.WARN)
                box.postDelayed({
                    content.removeView(box); overlay = null
                    wrap?.animate()?.alpha(1f)?.setDuration(Kit.SETTLE)?.start()
                    Installer.watcher = null
                    render()
                }, if (error == null) 1200 else 3600)
            }
        }
        Installer.install(this, b.title, asset)
    }

    private fun askInstalls() {
        Sheet.show(this, "Allow installs", action = "Open" to { s ->
            s.dismiss(); startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }) { s ->
            s.body.addView(text("Android asks once before Officina may install apps. Switch on Allow from this source, then come back and tap Install again.", 14f, Kit.MUTED))
        }
    }

    // ---- The workshop's own settings, at the foot ---------------------------------------------

    private fun workshop(screen: Screen) {
        screen.section("Workshop")
        screen.add(Card(this).apply {
            row(Row(this@MainActivity, "Refresh", if (loadedAt > 0) "Read ${ago(loadedAt)}" else null, endValue("›", Kit.CHAMPAGNE)) { loadedAt = 0; load() })
            if (!packageManager.canRequestPackageInstalls()) row(Row(this@MainActivity, "Allow installs", "Android asks once before Officina may install apps", endValue("Open  ›", Kit.CHAMPAGNE)) { askInstalls() })
            row(Row(this@MainActivity, "GitHub token", if (Token.get(this@MainActivity) != null) "Saved, kept encrypted on the phone" else "Optional: raises the hourly limit, and lets ticks be saved to the repos",
                endValue(if (Token.get(this@MainActivity) != null) "Change  ›" else "Add  ›", Kit.CHAMPAGNE)) { editToken() })
            row(Row(this@MainActivity, "Show retired apps", "Ferro, Giano and Curva", Toggle(this@MainActivity, Prefs.showRetired(this@MainActivity)) { Prefs.setShowRetired(this@MainActivity, it); render() }))
        })
        screen.add(doctrineCard(NOTES), px(24))
        screen.add(text("Version " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?"), 12f, Kit.MUTED).apply { setPadding(px(4), px(16), 0, px(24)) })
    }

    private fun editToken() {
        val f = field("github_pat_…", "")
        Sheet.show(this, "GitHub token", action = "Save" to { s ->
            Token.set(this, f.text.toString().ifBlank { null }); s.dismiss(); loadedAt = 0; load()
        }) { s ->
            s.body.addView(text("A fine-grained token for cerniera-studio with read access to Actions and Contents, and write access to Contents if ticks are to be saved back. It is encrypted with a key held in Android's keystore. Leave empty and save to remove it.", 13.5f, Kit.MUTED).apply { setPadding(0, 0, 0, px(12)) })
            s.body.addView(f)
        }
    }

    private fun view(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            t <= 0 -> "unknown"
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    companion object {
        /** Repos that hold no app. */
        val NOT_APPS = setOf("kit", ".github")
        /** Apps Mechack has retired: hidden unless asked for. */
        val RETIRED = setOf("ferro", "giano", "curva")

        fun title(repo: String) = repo.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        val NOTES = listOf(
            "Membrane" to "The apps stand in one rack. The newest build comes forward to the top and the rest settle back beneath it; a build you haven't looked at carries a champagne dot.",
            "Concentrato" to "While a build installs, everything else steps back to almost nothing: the app's name and one line of progress.",
            "The Giulietta principle" to "Release notes, the checklist and the emulator's pictures stay inside each app's bench until you open it. Install appears only where there is something newer than the phone has.",
            "Still to see on the Fold" to "Each repo can keep a FOLD_CHECKS.md: \"## Heading\" lines and \"- [ ] item\" lines. Tick items as you see them on the phone; share the report, or, with a token, save the ticks back to the file.",
        )
    }
}

/**
 * When each app was last read in full, and the push it saw then. An app is asked about again only when
 * it has been pushed to since, when its last run was still going, or when the push was recent enough
 * that its build may still have been publishing (a build takes about ten minutes; 40 allows the
 * emulator test too).
 */
object Read {
    private fun of(c: android.content.Context) = c.getSharedPreferences("officina_read", android.content.Context.MODE_PRIVATE)
    fun needed(c: android.content.Context, repo: String, pushed: Long, run: Run?): Boolean {
        val p = of(c)
        val sawPush = p.getLong("pushed:$repo", -1L)
        val readAt = p.getLong("at:$repo", 0L)
        return sawPush != pushed || (run != null && run.status != "completed") || readAt - pushed < 40 * 60_000L
    }
    fun done(c: android.content.Context, repo: String, pushed: Long) =
        of(c).edit().putLong("pushed:$repo", pushed).putLong("at:$repo", System.currentTimeMillis()).apply()
}

object Prefs {
    private fun of(c: android.content.Context) = c.getSharedPreferences("officina", android.content.Context.MODE_PRIVATE)
    fun showRetired(c: android.content.Context) = of(c).getBoolean("show_retired", false)
    fun setShowRetired(c: android.content.Context, v: Boolean) = of(c).edit().putBoolean("show_retired", v).apply()
    fun seen(c: android.content.Context, repo: String) = of(c).getLong("seen:$repo", -1L)
    fun setSeen(c: android.content.Context, repo: String, id: Long) = of(c).edit().putLong("seen:$repo", id).apply()
}
