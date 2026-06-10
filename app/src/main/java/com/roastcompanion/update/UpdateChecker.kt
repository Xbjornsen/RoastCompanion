package com.roastcompanion.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.roastcompanion.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub Releases for a newer APK and installs it.
 * The repo is public, so the API needs no token.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Xbjornsen/RoastCompanion/releases/latest"
    }

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** Returns info for a newer release, or null if this build is current. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode == 404) return@withContext null // no releases yet
            if (conn.responseCode != 200) {
                throw IllegalStateException("GitHub API returned ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val remote = json.getString("tag_name").removePrefix("v")
            if (!isNewer(remote, BuildConfig.VERSION_NAME)) return@withContext null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            apkUrl?.let {
                UpdateInfo(
                    versionName = remote,
                    apkUrl = it,
                    notes = json.optString("body", "")
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release APK to app cache and returns the file. */
    suspend fun downloadApk(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "roastcompanion-${info.versionName}.apk")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } finally {
            conn.disconnect()
        }
    }

    /** Hands the APK to the system package installer. */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", file
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Numeric semver comparison: "1.10.0" > "1.9.2". */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
