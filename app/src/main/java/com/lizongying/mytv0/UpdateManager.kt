package com.lizongying.mytv0

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) :
    ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    var release: ReleaseResponse? = null

    private suspend fun getRelease(): ReleaseResponse? {
        // 先尝试从 version.json 获取
        val urls = getUrls(VERSION_URL)

        for (u in urls) {
            Log.i(TAG, "request $u")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        response.bodyAlias()?.let {
                            val releaseResponse = gson.fromJson(
                                it.string(),
                                ReleaseResponse::class.java
                            )
                            
                            // 如果 apk_url 为空，尝试从 GitHub Release API 获取
                            if (releaseResponse.apk_url.isNullOrEmpty()) {
                                val ghRelease = getGitHubRelease()
                                if (ghRelease != null) {
                                    releaseResponse.apk_url = ghRelease.apk_url
                                    releaseResponse.apk_name = ghRelease.apk_name
                                }
                            }
                            
                            return@withContext releaseResponse
                        }
                    } else {
                        Log.e(TAG, "getRelease $u ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getRelease $u error: ${e.message}")
                }
            }
        }

        return null
    }
    
    /**
     * 从 GitHub Release API 获取实际的 APK 下载地址
     */
    private suspend fun getGitHubRelease(): ReleaseResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/ericwucn/my-tv-0/releases/latest")
                    .build()
                val response = HttpClient.okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    response.bodyAlias()?.let { body ->
                        val json = com.google.gson.JsonParser.parseString(body.string()).asJsonObject
                        
                        // 获取版本号
                        val versionName = json.get("tag_name")?.asString ?: return@withContext null
                        
                        // 获取第一个 APK 资产
                        val assets = json.getAsJsonArray("assets")
                        for (asset in assets) {
                            val assetObj = asset.asJsonObject
                            val name = assetObj.get("name")?.asString ?: continue
                            if (name.endsWith(".apk")) {
                                val downloadUrl = assetObj.get("browser_download_url")?.asString ?: continue
                                
                                // 从版本名解析版本号 (v1.5.1.8 -> 17105158)
                                val versionCode = parseVersionCode(versionName)
                                
                                val release = ReleaseResponse(
                                    version_code = versionCode,
                                    version_name = versionName,
                                    apk_name = name,
                                    apk_url = downloadUrl
                                )
                                
                                Log.i(TAG, "GitHub Release: $versionName, apk=$name")
                                return@withContext release
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getGitHubRelease error: ${e.message}")
            }
            null
        }
    }
    
    private fun parseVersionCode(versionName: String): Long {
        // v1.5.1.8 -> 17105158
        try {
            val parts = versionName.removePrefix("v").split(".")
            if (parts.size == 4) {
                return ((parts[0].toLong() * 10000000) +
                        (parts[1].toLong() * 100000) +
                        (parts[2].toLong() * 1000) +
                        parts[3].toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseVersionCode error: ${e.message}")
        }
        return 0
    }

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        // 先显示检测中提示
        "正在检测版本...".showToast()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                release = getRelease()
                Log.i(TAG, "versionCode $versionCode ${release?.version_code}")
                if (release?.version_code != null) {
                    if (release?.version_code!! > versionCode) {
                        // 有新版本，显示更新确认对话框
                        val text = "发现新版本：${release?.version_name}，是否更新？"
                        showConfirmDialog(text)
                    } else {
                        // 已是最新或相同版本
                        "已是最新版本：${release?.version_name}".showToast()
                    }
                } else {
                    "版本获取失败".showToast()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
                "版本获取失败".showToast()
            }
        }
    }

    private fun showConfirmDialog(message: String) {
        val dialog = ConfirmationFragment(this@UpdateManager, message, true)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    private fun startDownload(release: ReleaseResponse) {
        val apkName = release.apk_name ?: return
        val apkUrl = release.apk_url ?: return
        if (apkName.isEmpty() || apkUrl.isEmpty()) {
            return
        }

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request =
            Request(Uri.parse(apkUrl))
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
        Log.i(TAG, "save dir ${Environment.DIRECTORY_DOWNLOADS}")
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            apkName
        )
        request.setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverRoaming(false)
        request.setMimeType("application/vnd.android.package-archive")

        // 获取下载任务的引用
        val downloadReference = downloadManager.enqueue(request)

        downloadReceiver = DownloadReceiver(context, apkName, downloadReference)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
        }

        getDownloadProgress(context, downloadReference) { progress ->
            println("Download progress: $progress%")
        }
    }

    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        val intervalMillis: Long = 1000

        handler.post(object : Runnable {
            override fun run() {
                Log.i(TAG, "search")
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val bytesDownloadedIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        // 检查列名是否存在
                        if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1) {
                            val bytesDownloaded = it.getInt(bytesDownloadedIndex)
                            val bytesTotal = it.getInt(bytesTotalIndex)

                            if (bytesTotal != -1) {
                                val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                                progressListener(progress)
                                if (progress == 100) {
                                    return
                                }
                            }
                        }
                    }
                }

//                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.i(TAG, "reference $reference")

            if (reference == downloadReference) {
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadReference)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex < 0) {
                        Log.i(TAG, "Download failure")
                        return
                    }
                    val status = cursor.getInt(statusIndex)

                    val progressIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    if (progressIndex < 0) {
                        Log.i(TAG, "Download failure")
                        return
                    }
                    val progress = cursor.getInt(progressIndex)

                    val totalSizeIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val totalSize = cursor.getInt(totalSizeIndex)

                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            installNewVersion()
                        }

                        DownloadManager.STATUS_FAILED -> {
                            // Handle download failure
                            Log.i(TAG, "Download failure")
                        }

                        else -> {
                            // Update UI with download progress
                            val percentage = progress * 100 / totalSize
                            Log.i(TAG, "Download progress: $percentage%")
                        }
                    }
                }
            }
        }

        private fun installNewVersion() {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            Log.i(TAG, "apkFile $apkFile")

            if (apkFile.exists()) {
                val apkUri = Uri.parse("file://$apkFile")
                Log.i(TAG, "apkUri $apkUri")
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(installIntent)
            } else {
                Log.e(TAG, "APK file does not exist!")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/ericwucn/my-tv-0/main/version.json"
    }

    override fun onConfirm() {
        Log.i(TAG, "onConfirm $release")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
    }

    fun destroy() {
        if (downloadReceiver != null) {
            context.unregisterReceiver(downloadReceiver)
            Log.i(TAG, "destroy downloadReceiver")
        }
    }
}