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
        private const val DEVELOPMENT_RELEASE_TAG = "development"
        private val RELEASE_VERSION_CODE_PATTERN =
            Regex("<!--\\s*updater-version-code:\\s*(\\d+)\\s*-->")
    }

    fun includePrereleases(): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_INCLUDE_PRERELEASES, BuildConfig.DEVELOPMENT_BUILD)

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
                val installedApkSha256 = installedApkSha256()
                val installedVersionCode = installedVersionCode()
                val latestUpdate = candidates
                    .filter {
                        if (it.isDevelopmentRelease) {
                            isDevelopmentUpdate(it, installedApkSha256, installedVersionCode)
                        } else {
                            isRegularReleaseInstallable(it, installedVersionCode) &&
                                SemanticVersion.compare(it.versionName, BuildConfig.VERSION_NAME)
                                    ?.let { order -> order > 0 } == true
                        }
                    }
                    .maxWithOrNull { left, right ->
                        when {
                            left.isDevelopmentRelease && !right.isDevelopmentRelease ->
                                SemanticVersion.compare(left.versionName, right.versionName) ?: -1
                            !left.isDevelopmentRelease && right.isDevelopmentRelease ->
                                SemanticVersion.compare(left.versionName, right.versionName) ?: 1
                            else -> SemanticVersion.compare(left.versionName, right.versionName) ?: 0
                        }
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
        val releaseNotes = updateInfo.releaseNotes
            .replace(RELEASE_VERSION_CODE_PATTERN, "")
            .trim()
        val message = buildString {
            if (updateInfo.isDevelopmentRelease) {
                append("新しい開発版が利用できます。")
            } else {
                append("新しいバージョン ${updateInfo.versionName} が利用できます。")
            }
            if (updateInfo.isPrerelease) append("\n\nプレリリース版です。")
            if (releaseNotes.isNotBlank()) {
                append("\n\n")
                append(releaseNotes.take(700))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(
                when {
                    updateInfo.isDevelopmentRelease -> "開発版のアップデートがあります"
                    updateInfo.isPrerelease -> "プレリリース版があります"
                    else -> "アップデートがあります"
                },
            )
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
            validateUpdatePackage(activity, apkFile)
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

    private fun validateUpdatePackage(activity: AppCompatActivity, apkFile: File) {
        val downloadedPackage = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IOException("ダウンロードしたファイルをAPKとして読み取れません")
        if (downloadedPackage.packageName != activity.packageName) {
            throw IOException("ダウンロードしたAPKはBondVPNの更新ファイルではありません")
        }

        val installedPackage = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val downloadedVersionCode = downloadedPackage.compatVersionCode()
        val installedVersionCode = installedPackage.compatVersionCode()
        if (downloadedVersionCode < installedVersionCode) {
            throw IOException("ダウンロードしたAPKはインストール済みのアプリより古いため更新できません")
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.compatVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

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

    private fun installedApkSha256(): String? = runCatching {
        val installedApk = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        installedApk.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexString()
    }.getOrNull()

    private fun isDevelopmentUpdate(
        updateInfo: UpdateInfo,
        installedApkSha256: String?,
        installedVersionCode: Long?,
    ): Boolean {
        val downloadedVersionCode = updateInfo.versionCode ?: return false
        installedVersionCode ?: return false
        if (downloadedVersionCode < installedVersionCode) return false
        if (!installedApkSha256.isNullOrBlank()) {
            return !updateInfo.sha256.equals(installedApkSha256, ignoreCase = true)
        }
        return downloadedVersionCode > installedVersionCode
    }

    private fun isRegularReleaseInstallable(updateInfo: UpdateInfo, installedVersionCode: Long?): Boolean {
        val downloadedVersionCode = updateInfo.versionCode ?: return true
        return installedVersionCode == null || downloadedVersionCode >= installedVersionCode
    }

    private fun installedVersionCode(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).compatVersionCode()
    }.getOrNull()

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
            val isDevelopmentRelease = release.optString("tag_name") == DEVELOPMENT_RELEASE_TAG
            if (!includePrereleases && (release.optBoolean("prerelease", false) || isDevelopmentRelease)) {
                return@mapNotNull null
            }
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
        val isDevelopmentRelease = tagName == DEVELOPMENT_RELEASE_TAG
        val releaseNotes = json.optString("body")
        val versionName = if (isDevelopmentRelease) {
            extractApkVersion(apkName) ?: DEVELOPMENT_RELEASE_TAG
        } else {
            SemanticVersion.extract(tagName) ?: tagName.removePrefix("v")
        }
        val versionCode = RELEASE_VERSION_CODE_PATTERN.find(releaseNotes)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        return UpdateInfo(
            versionName = versionName,
            releaseName = json.optString("name").ifBlank { tagName },
            releaseNotes = releaseNotes,
            releasePageUrl = json.optString("html_url"),
            apkUrl = apkUrl,
            fileName = apkName,
            sha256 = apkSha256,
            isPrerelease = json.optBoolean("prerelease", false) || isDevelopmentRelease,
            isDevelopmentRelease = isDevelopmentRelease,
            versionCode = versionCode,
        )
    }

    private fun extractApkVersion(apkName: String): String? {
        val withoutExtension = apkName.substringBeforeLast('.', apkName)
        val withoutBuildType = withoutExtension.replace(
            Regex("-(debug|release)$", RegexOption.IGNORE_CASE),
            "",
        )
        return SemanticVersion.extract(withoutBuildType)
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
    val isDevelopmentRelease: Boolean = false,
    val versionCode: Long? = null,
)

private fun ByteArray.toHexString(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
    data object NoUpdate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
