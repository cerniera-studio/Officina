package com.cerniera.officina

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A published build: its tag, title, branch (for pre-releases), when, and its files. */
data class Release(
    val id: Long, val tag: String, val name: String, val prerelease: Boolean, val branch: String?,
    val published: Long, val body: String, val apk: Asset?, val shots: List<Asset>, val url: String,
) {
    /** The build number: the workflow run that made it, which is also its versionCode. */
    val number: Long? get() = tag.removePrefix("build-").toLongOrNull()
}

data class Asset(val name: String, val url: String, val size: Long)

/** The latest workflow run on a repo: its lamp. */
data class Run(val name: String, val status: String, val conclusion: String?, val branch: String, val created: Long, val url: String)

/** One repo's checklist file and the version GitHub holds (needed to save ticks back). */
data class ChecksFile(val text: String, val sha: String)

/**
 * GitHub's REST API, read-only unless a token is saved (16 Nov 2026). Every answer is kept with its
 * ETag and asked for again conditionally: an unchanged answer (304) doesn't count against the hourly
 * limit, which is 60 requests without a token and 5,000 with one.
 */
object GitHub {
    const val ORG = "cerniera-studio"
    private const val API = "https://api.github.com"

    class Limited(val resetAt: Long) : Exception("GitHub's hourly limit is reached")

    /** While GitHub's hourly limit is spent, the time it resets; every read is answered from what was kept. */
    @Volatile var limitedUntil = 0L
    val limited get() = System.currentTimeMillis() < limitedUntil

    /** Reads only what was kept, asking GitHub nothing (for apps unchanged since they were last read). */
    private val keptOnly = ThreadLocal<Boolean>()
    fun <T> kept(block: () -> T): T { keptOnly.set(true); try { return block() } finally { keptOnly.set(false) } }

    private fun cacheFile(c: Context, path: String) = File(File(c.cacheDir, "gh").apply { mkdirs() }, path.hashCode().toUInt().toString(16))

    /** A GET, with the ETag cache. Returns null on 404. */
    fun get(c: Context, path: String): String? {
        val cache = cacheFile(c, path)
        val tagFile = File(cache.path + ".etag")
        // Nothing asked of GitHub while the limit is spent, or for an app that hasn't changed
        if (limited || keptOnly.get() == true) {
            if (cache.exists()) return cache.readText()
            if (limited) throw Limited(limitedUntil)
        }
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Cerniera-Officina")
            Token.get(c)?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (cache.exists() && tagFile.exists()) setRequestProperty("If-None-Match", tagFile.readText())
        }
        try {
            return when (val code = conn.responseCode) {
                304 -> cache.readText()
                200 -> conn.inputStream.bufferedReader().readText().also { body ->
                    cache.writeText(body)
                    conn.getHeaderField("ETag")?.let { tagFile.writeText(it) }
                }
                404 -> null
                403, 429 -> {
                    val left = conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
                    if (left == 0) {
                        limitedUntil = (conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0L) * 1000
                        // The rest of this refresh, and any before the reset, reads what was kept
                        if (cache.exists()) return cache.readText()
                        throw Limited(limitedUntil)
                    }
                    // Over the limit before: the last answer still serves
                    if (cache.exists()) cache.readText() else throw java.io.IOException("GitHub answered $code")
                }
                else -> if (cache.exists()) cache.readText() else throw java.io.IOException("GitHub answered $code")
            }
        } finally { conn.disconnect() }
    }

    /** The studio's public repos, newest push first. */
    data class Repo(val name: String, val archived: Boolean, val pushed: Long)

    /** The studio's public repos, newest push first. One request answers for every app. */
    fun repos(c: Context): List<Repo> {
        val a = JSONArray(get(c, "/orgs/$ORG/repos?per_page=100&type=public") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Repo(it.getString("name"), it.optBoolean("archived"), time(it.optString("pushed_at"))) }
            .sortedByDescending { it.pushed }
    }

    fun releases(c: Context, repo: String): List<Release> {
        val a = JSONArray(get(c, "/repos/$ORG/$repo/releases?per_page=12") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map { r ->
            val assets = r.optJSONArray("assets") ?: JSONArray()
            val files = (0 until assets.length()).map { assets.getJSONObject(it) }
                .map { Asset(it.getString("name"), it.getString("browser_download_url"), it.optLong("size")) }
            val name = r.optString("name").ifBlank { r.getString("tag_name") }
            // Branch builds name their branch in brackets: "Sveglia build 13 (shapes)"
            val branch = Regex("\\(([^)]+)\\)\\s*$").find(name)?.groupValues?.get(1)
            Release(r.getLong("id"), r.getString("tag_name"), name, r.optBoolean("prerelease"), branch,
                time(r.optString("published_at")), r.optString("body"),
                files.firstOrNull { it.name.endsWith(".apk") }, files.filter { it.name.endsWith(".png") }, r.optString("html_url"))
        }.filter { it.apk != null }.sortedByDescending { it.published }
    }

    fun latestRun(c: Context, repo: String): Run? {
        val o = JSONObject(get(c, "/repos/$ORG/$repo/actions/runs?per_page=1") ?: return null)
        val r = o.optJSONArray("workflow_runs")?.optJSONObject(0) ?: return null
        return Run(r.optString("name"), r.optString("status"), r.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
            r.optString("head_branch"), time(r.optString("created_at")), r.optString("html_url"))
    }

    /** FOLD_CHECKS.md, if the repo keeps one. */
    fun checks(c: Context, repo: String): ChecksFile? {
        val o = JSONObject(get(c, "/repos/$ORG/$repo/contents/FOLD_CHECKS.md") ?: return null)
        val text = String(android.util.Base64.decode(o.optString("content"), android.util.Base64.DEFAULT))
        return ChecksFile(text, o.optString("sha"))
    }

    /** The app's package name, read once from its build file and kept. */
    fun appId(c: Context, repo: String): String? {
        val prefs = c.getSharedPreferences("officina", Context.MODE_PRIVATE)
        prefs.getString("app_id:$repo", null)?.let { return it }
        val o = JSONObject(get(c, "/repos/$ORG/$repo/contents/app/build.gradle.kts") ?: return null)
        val text = String(android.util.Base64.decode(o.optString("content"), android.util.Base64.DEFAULT))
        val id = Regex("applicationId\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
        prefs.edit().putString("app_id:$repo", id).apply()
        return id
    }

    /** Saves a new FOLD_CHECKS.md to the default branch. Needs a token with Contents write. */
    fun putChecks(c: Context, repo: String, text: String, sha: String, message: String) {
        val token = Token.get(c) ?: throw IllegalStateException("No token saved")
        val conn = (URL("$API/repos/$ORG/$repo/contents/FOLD_CHECKS.md").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "Cerniera-Officina")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("message", message).put("sha", sha)
            .put("content", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP))
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..201) throw java.io.IOException("GitHub answered ${conn.responseCode}")
        } finally { conn.disconnect() }
        // The next read must see the new file, not the cached one
        cacheFile(c, "/repos/$ORG/$repo/contents/FOLD_CHECKS.md").delete()
    }

    private fun time(iso: String): Long = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
}

/**
 * The GitHub token, if one is saved: encrypted with a key that lives in Android's keystore and never
 * leaves it. Read-only access to public repos works without one.
 */
object Token {
    private const val ALIAS = "officina-token"
    private fun prefs(c: Context) = c.getSharedPreferences("officina_token", Context.MODE_PRIVATE)

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return g.generateKey()
    }

    @Volatile private var cached: String? = null

    fun get(c: Context): String? {
        cached?.let { return it }
        val p = prefs(c)
        val data = p.getString("data", null) ?: return null
        val iv = p.getString("iv", null) ?: return null
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(iv, 0)))
            String(cipher.doFinal(android.util.Base64.decode(data, 0)))
        }.getOrNull().also { cached = it }
    }

    fun set(c: Context, token: String?) {
        // A new token (or none) has its own hourly limit: ask GitHub again rather than wait out the old one
        GitHub.limitedUntil = 0L
        cached = null
        if (token.isNullOrBlank()) { prefs(c).edit().clear().apply(); return }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        prefs(c).edit()
            .putString("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putString("data", android.util.Base64.encodeToString(cipher.doFinal(token.trim().toByteArray()), android.util.Base64.NO_WRAP))
            .apply()
        cached = token.trim()
    }
}
