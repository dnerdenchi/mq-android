package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/dnerdenchi/mq-android/releases"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val REQUEST_TIMEOUT_MS = 15_000
        private const val PREFS_NAME = "AppUpdatePrefs"
        private const val KEY_LAST_AUTO_CHECK_AT = "last_auto_check_at"
        private const val KEY_INCLUDE_PRERELEASES = "include_prereleases"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    fun includePrereleases(): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_INCLUDE_PRERELEASES, false)

    fun setIncludePrereleases(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INCLUDE_PRERELEASES, enabled)
            .apply()
    }

    suspend fun checkForUpdate(
        includePrereleases: Boolean = includePrereleases(),
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val candidates = getReleaseCandidates(includePrereleases)
            if (candidates.isEmpty()) {
                UpdateCheckResult.Error("GitHub Releases に APK が見つかりませんでした")
            } else {
                val latestUpdate = candidates
                    .filter {
                        SemanticVersion.compare(it.versionName, BuildConfig.VERSION_NAME)
                            ?.let { order -> order > 0 } == true
                    }
                    .maxWithOrNull { left, right ->
                        SemanticVersion.compare(left.versionName, right.versionName) ?: 0
                    }
                if (latestUpdate == null) UpdateCheckResult.NoUpdate
                else UpdateCheckResult.UpdateAvailable(latestUpdate)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(toUserMessage(e))
        }
    }

    suspend fun downloadApk(updateInfo: UpdateInfo): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val safeFileName = updateInfo.fileName.substringAfterLast('/')
            .takeIf { it.matches(Regex("[A-Za-z0-9._-]+\\.apk", RegexOption.IGNORE_CASE)) }
            ?: throw IOException("GitHub Releases の APK 名が不正です")
        val outputFile = File(outputDir, safeFileName)
        val connection = openConnection(updateInfo.apkUrl)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(connection.inputStream, digest).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val actualDigest = digest.digest().toHexString()
            if (!actualDigest.equals(updateInfo.sha256, ignoreCase = true)) {
                throw IOException(
                    "APKの検証に失敗しました。ダウンロード中にファイルが破損した可能性があります。もう一度お試しください",
                )
            }
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        outputFile
    }

    fun shouldRunAutomaticCheck(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckAt = prefs.getLong(KEY_LAST_AUTO_CHECK_AT, 0L)
        return System.currentTimeMillis() - lastCheckAt >= AUTO_CHECK_INTERVAL_MS
    }

    fun markAutomaticCheckStarted() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    fun showUpdateDialog(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo,
        scope: CoroutineScope
    ) {
        val message = buildString {
            append("新しいバージョン ${updateInfo.versionName} が利用できます。")
            if (updateInfo.isPrerelease) append("\n\nプレリリース版です。")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(updateInfo.releaseNotes.take(700))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(if (updateInfo.isPrerelease) "プレリリース版があります" else "アップデートがあります")
            .setMessage(message)
            .setPositiveButton("ダウンロードして更新") { _, _ ->
                if (!canRequestPackageInstalls(activity)) {
                    openInstallPermissionSettings(activity)
                    return@setPositiveButton
                }
                scope.launch {
                    installDownloadedUpdate(activity, updateInfo)
                }
            }
            .setNegativeButton("あとで", null)
            .setNeutralButton("リリースページ") { _, _ ->
                openReleasePage(activity, updateInfo.releasePageUrl)
            }
            .show()
    }

    private suspend fun installDownloadedUpdate(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo
    ) {
        try {
            Toast.makeText(activity, "APK をダウンロードしています", Toast.LENGTH_SHORT).show()
            val apkFile = downloadApk(updateInfo)
            startInstall(activity, apkFile)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                e.message ?: "APK のダウンロードに失敗しました",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startInstall(activity: AppCompatActivity, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(installIntent)
    }

    private fun canRequestPackageInstalls(activity: AppCompatActivity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings(activity: AppCompatActivity) {
        Toast.makeText(
            activity,
            "このアプリからのインストールを許可してから、もう一度更新してください",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    private fun openReleasePage(activity: AppCompatActivity, releasePageUrl: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl)))
    }

    private fun getText(url: String): String {
        val connection = openConnection(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "BondVPN/${BuildConfig.VERSION_NAME}")
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("GitHub Releases への接続に失敗しました (${connection.responseCode})")
        }
        return connection
    }

    private fun toUserMessage(error: Exception): String {
        return when {
            error is IOException && error.message?.contains("404") == true ->
                "GitHub Releases に公開済みリリースがありません"
            error.message?.contains("(404)") == true ->
                "GitHub Releases に公開済みリリースがありません"
            else -> error.message ?: "アップデート確認に失敗しました"
        }
    }

    private fun getReleaseCandidates(includePrereleases: Boolean): List<UpdateInfo> {
        val candidates = mutableListOf<UpdateInfo>()
        var page = 1

        while (true) {
            val releases = JSONArray(getText("$RELEASES_API_URL?per_page=100&page=$page"))
            candidates += parseReleaseList(releases, includePrereleases)
            if (releases.length() < 100) return candidates
            page++
        }
    }

    private fun parseReleaseList(releases: JSONArray, includePrereleases: Boolean): List<UpdateInfo> {
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (!includePrereleases && release.optBoolean("prerelease", false)) return@mapNotNull null
            parseRelease(release)
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        val assets = json.optJSONArray("assets") ?: return null
        var apkName = ""
        var apkUrl = ""
        var apkSha256 = ""

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue

            val candidateUrl = asset.optString("browser_download_url")
            val candidateDigest = asset.optString("digest").removePrefix("sha256:")
            val candidateUri = Uri.parse(candidateUrl)
            val hasValidDigest = candidateDigest.matches(Regex("[0-9a-fA-F]{64}"))
            val isGitHubReleaseAsset = candidateUri.scheme == "https" &&
                candidateUri.host.equals("github.com", ignoreCase = true) &&
                candidateUri.path?.startsWith("/dnerdenchi/mq-android/releases/download/") == true
            if (!hasValidDigest || !isGitHubReleaseAsset) continue

            apkName = name
            apkUrl = candidateUrl
            apkSha256 = candidateDigest
            break
        }

        if (apkName.isBlank()) return null

        val tagName = json.optString("tag_name").trim()
        val versionName = SemanticVersion.extract(tagName) ?: tagName.removePrefix("v")
        return UpdateInfo(
            versionName = versionName,
            releaseName = json.optString("name").ifBlank { tagName },
            releaseNotes = json.optString("body"),
            releasePageUrl = json.optString("html_url"),
            apkUrl = apkUrl,
            fileName = apkName,
            sha256 = apkSha256,
            isPrerelease = json.optBoolean("prerelease", false),
        )
    }
}

data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val fileName: String,
    val sha256: String,
    val isPrerelease: Boolean = false,
)

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
    data object NoUpdate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
