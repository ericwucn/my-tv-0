import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonSyntaxException
import com.lizongying.mytv0.ImageHelper
import com.lizongying.mytv0.MyTVApplication
import com.lizongying.mytv0.R
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.bodyAlias
import com.lizongying.mytv0.codeAlias
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeEPGMap
import com.lizongying.mytv0.data.Global.typeTvList
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream


class MainViewModel : ViewModel() {
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        viewModelScope.launch {
            var success = false
            if (!epgUrl.isNullOrEmpty()) {
                success = updateEPG(epgUrl!!)
            }
            if (!success && !SP.epg.isNullOrEmpty()) {
                updateEPG(SP.epg!!)
            }
        }
    }

    fun updateConfig() {
        if (SP.configAutoLoad) {
            SP.configUrl?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch {
                        Log.i(TAG, "update config url: $it")
                        importFromUrl(it)
                        updateEPG()
                    }
                }
            }
        }
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        val application = context.applicationContext as MyTVApplication
        imageHelper = application.imageHelper

        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部频道", 1))

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        if (!cacheFile!!.exists()) {
            cacheFile!!.createNewFile()
        }

        cacheChannels = getCache()

        // 检测旧版本缓存（不含 catchupSource 字段），清除后重新从网络加载
        if (cacheChannels.isNotEmpty() && !cacheChannels.contains("catchupSource")) {
            Log.i(TAG, "检测到旧版本缓存，清除以重新加载（支持回看功能）")
            cacheFile!!.writeText("")
            cacheChannels = ""
        }

        if (cacheChannels.isEmpty()) {
            Log.i(TAG, "cacheChannels isEmpty, 尝试从默认URL加载")
            // 【新增】先尝试从网络加载默认视频源
            viewModelScope.launch {
                try {
                    importFromUrl(DEFAULT_CHANNELS_URL)
                    Log.i(TAG, "从默认URL加载完成")
                    // 加载默认频道后，设置配置URL（EPG URL会在 importFromUrl -> tryStr2Channels 中自动检测并加载）
                    SP.configUrl = DEFAULT_CHANNELS_URL
                } catch (e: Exception) {
                    Log.w(TAG, "从默认URL加载失败: ${e.message}")
                    // 网络加载失败，使用本地默认
                    val localChannels = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                        .use { it.readText() }
                    tryStr2Channels(localChannels, null, "", "")
                }
            }
        } else {
            Log.i(TAG, "cacheChannels $cacheFile ${cacheChannels.take(100)}...")

            try {
                str2Channels(cacheChannels)
            } catch (e: Exception) {
                Log.e(TAG, "init", e)
                cacheFile!!.deleteOnExit()
                R.string.channel_read_error.showToast()
            }
        }

        viewModelScope.launch {
            cacheEPG = File(appDirectory, CACHE_EPG)
            if (!cacheEPG.exists()) {
                cacheEPG.createNewFile()
            } else {
                Log.i(TAG, "cacheEPG exists")
                if (readEPG(cacheEPG.readText())) {
                    Log.i(TAG, "cacheEPG success")
                } else {
                    Log.i(TAG, "cacheEPG failure")
                }
            }
        }

        initialized = true

        _channelsOk.value = true
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Log.w(TAG, "imageHelper is not initialized")
            return
        }

        for (tvModel in listModel) {
            var name = tvModel.tv.name
            if (name.isEmpty()) {
                name = tvModel.tv.title
            }
            val url = tvModel.tv.logo
            var urls =
                listOf(
                    "https://live.fanmingming.cn/tv/$name.png"
                ) + getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$name.png")
            if (url.isNotEmpty()) {
                urls = (getUrls(url) + urls).distinct()
            }

            imageHelper.preloadImage(
                name,
                urls,
            )
        }
    }

    /**
     * 解析 EPG InputStream，返回频道-节目映射表
     * 不触发任何 LiveData/UI 操作，IO 线程完成
     */
    suspend fun parseEPG(input: InputStream): Map<String, List<EPG>> = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)
            Log.i(TAG, "parseEPG parsed ${res.size} channels")
            res
        } catch (e: Exception) {
            Log.e(TAG, "parseEPG", e)
            emptyMap()
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
                }
            }
            Log.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    /**
     * 将解析后的 EPG 数据写入缓存文件
     * 纯 IO 操作，不触发 LiveData/UI
     */
    private suspend fun writeEpgCache(epgMap: Map<String, List<EPG>>) = withContext(Dispatchers.IO) {
        try {
            cacheEPG.writeText(gson.toJson(epgMap))
            Log.i(TAG, "writeEpgCache written")
        } catch (e: Exception) {
            Log.e(TAG, "writeEpgCache", e)
        }
    }

    /**
     * 将 EPG 数据应用到 ViewModel 的 LiveData
     * 必须在 Dispatchers.Main 或 Dispatchers.IO 上下文中调用
     * 不在 onCreate 阶段调用，避免与 SplashScreen 死锁
     * 
     * 【增强】智能 EPG 匹配：
     * 1. 精确匹配 tvg-name（如 CCTV1）
     * 2. 匹配去掉 "-" 后的名称（如 cctv1 匹配 CCTV-1）
     * 3. 模糊匹配（EPG 名称包含频道名，或频道名包含 EPG 名称）
     * 4. 匹配中文名称（如 东方卫视）
     * 5. 纯中文频道名匹配（去掉所有英文后的中文名）
     * 6. 提取频道名中的数字匹配（如 "CCTV-4" 提取 "4" 匹配 EPG 中的 "CCTV4"）
     */
    private fun applyEPG(epgMap: Map<String, List<EPG>>) {
        // 创建小写 key 的映射用于查找
        val epgMapLower = epgMap.mapKeys { it.key.lowercase() }
        
        var matchedCount = 0
        var totalCount = 0
        
        for (m in listModel) {
            val name = m.tv.name.ifEmpty { m.tv.title }
            val nameLower = name.lowercase()
            val titleLower = m.tv.title.lowercase()
            totalCount++
            
            var epg = epgMapLower[nameLower]
            
            // 策略2：尝试去掉 "-" 的名称
            if (epg == null) {
                val nameWithoutDash = nameLower.replace("-", "")
                epg = epgMapLower[nameWithoutDash]
                if (epg != null) {
                    Log.d(TAG, "EPG 匹配(${m.tv.title}): '$nameLower' -> '$nameWithoutDash'")
                }
            }
            
            // 策略3：模糊匹配（EPG 名称包含频道名，或频道名包含 EPG 名称）
            if (epg == null) {
                for ((epgNameLower, epgList) in epgMapLower) {
                    if (epgNameLower.contains(nameLower) || nameLower.contains(epgNameLower)) {
                        epg = epgList
                        Log.d(TAG, "EPG 模糊匹配(${m.tv.title}): '$nameLower' -> '$epgNameLower'")
                        break
                    }
                }
            }
            
            // 策略4：尝试 title（显示名称）
            if (epg == null) {
                epg = epgMapLower[titleLower]
                if (epg != null) {
                    Log.d(TAG, "EPG 匹配(${m.tv.title}): 使用 title '$titleLower'")
                }
            }
            
            // 策略5：纯中文频道名匹配（去掉所有英文和特殊字符后的中文名）
            if (epg == null) {
                val pureChineseName = name.replace(Regex("[a-zA-Z0-9\\-]"), "")
                if (pureChineseName.isNotEmpty()) {
                    epg = epgMapLower[pureChineseName.lowercase()]
                    if (epg != null) {
                        Log.d(TAG, "EPG 匹配(${m.tv.title}): 使用纯中文名 '$pureChineseName'")
                    }
                }
            }
            
            // 策略6：提取频道名中的编号匹配（如 "CCTV-4中文" 提取 "4" 匹配 EPG 中的 "CCTV4"）
            if (epg == null) {
                val numberMatch = Regex("\\d+").find(nameLower)
                if (numberMatch != null) {
                    val number = numberMatch.value
                    // 尝试 "cctv$number" 格式
                    val cctvNum = "cctv$number"
                    epg = epgMapLower[cctvNum]
                    if (epg == null) {
                        // 尝试从 EPG 中找包含编号的名称
                        for ((epgNameLower, epgList) in epgMapLower) {
                            if (epgNameLower.contains("cctv") && epgNameLower.contains(number)) {
                                epg = epgList
                                Log.d(TAG, "EPG 编号匹配(${m.tv.title}): '$nameLower' -> '$epgNameLower'")
                                break
                            }
                        }
                    } else {
                        Log.d(TAG, "EPG 编号匹配(${m.tv.title}): '$nameLower' -> '$cctvNum'")
                    }
                }
            }
            
            // 策略7：反向模糊匹配 - 用 title 的中文部分匹配 EPG
            if (epg == null) {
                val titleChinese = m.tv.title.replace(Regex("[a-zA-Z0-9\\-]"), "")
                if (titleChinese.isNotEmpty()) {
                    for ((epgNameLower, epgList) in epgMapLower) {
                        if (epgNameLower.contains(titleChinese.lowercase())) {
                            epg = epgList
                            Log.d(TAG, "EPG 反向匹配(${m.tv.title}): '$titleChinese' -> '$epgNameLower'")
                            break
                        }
                    }
                }
            }
            
            if (epg != null) {
                m.setEpg(epg)
                matchedCount++
            } else if (name.isNotEmpty()) {
                Log.w(TAG, "EPG 未匹配: ${m.tv.title} (tvg-name=$name)")
            }
        }
        
        Log.i(TAG, "EPG 匹配结果: $matchedCount / $totalCount 个频道")
    }

    /**
     * 更新 EPG 数据
     * 支持多个 EPG 源地址（逗号分隔）
     * 【修改】增加超时时间到 120 秒（gzip EPG 文件较大）
     */
    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            Log.i(TAG, "request $a")
            // 【修改】增加超时时间到 120 秒（gzip EPG 文件较大）
            val result = withTimeoutOrNull(120_000) {
                withContext(Dispatchers.IO) {
                    try {
                        val request = okhttp3.Request.Builder().url(a).build()
                        val response = HttpClient.okHttpClient.newCall(request).execute()

                        if (response.isSuccessful) {
                            // 纯 IO 解析，返回 Map
                            val epgMap = parseEPG(response.bodyAlias()!!.byteStream())
                            if (epgMap.isNotEmpty()) {
                                writeEpgCache(epgMap)
                                Log.i(TAG, "EPG $a success, ${epgMap.size} channels")
                                // 【修复】在主线程更新 LiveData
                                withContext(Dispatchers.Main) {
                                    applyEPG(epgMap)
                                }
                                true
                            } else {
                                false
                            }
                        } else {
                            Log.e(TAG, "EPG $a ${response.codeAlias()}")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "EPG $a error: ${e.message}")
                        false
                    }
                }
            }

            if (result == true) {
                success = true
                break
            } else if (result == null) {
                Log.w(TAG, "EPG $a timeout after 120s")
            }
        }

        return success
    }

    private suspend fun importFromUrl(url: String, id: String = "") {
        val urls = getUrls(url).map { Pair(it, url) }

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.bodyAlias()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id)
                        }
                        err = 0
                        shouldBreak = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                        err = R.string.channel_status_error
                    }
                } catch (e: JsonSyntaxException) {
                    e.printStackTrace()
                    Log.e(TAG, "JSON Parse Error", e)
                    err = R.string.channel_format_error
                    shouldBreak = true
                } catch (e: NullPointerException) {
                    e.printStackTrace()
                    Log.e(TAG, "Null Pointer Error", e)
                    err = R.string.channel_read_error
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, "Request error $e")
                    err = R.string.channel_request_error
                }
            }
            if (shouldBreak) break
        }

        if (err != 0) {
            err.showToast()
        }
    }

    fun reset(context: Context) {
        val str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
            .use { it.readText() }

        try {
            str2Channels(str)
        } catch (e: Exception) {
            e.printStackTrace()
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id)
            }
        }
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        try {
            // 检查是否包含 m3u 的 EPG URL
            val epgMatch = Regex("""x-tvg-url="([^"]+)"""").find(str)
            val m3uEpgUrl = epgMatch?.groupValues?.get(1)
            if (!m3uEpgUrl.isNullOrEmpty()) {
                Log.i(TAG, "发现 m3u 内嵌 EPG URL: $m3uEpgUrl")
                // 设置 EPG URL 并更新 EPG
                viewModelScope.launch {
                    updateEPG(m3uEpgUrl)
                }
            }
            
            if (str2Channels(str)) {
                Log.i(TAG, "write to cacheFile $cacheFile $str")
                cacheFile!!.writeText(str)
                Log.i(TAG, "cacheFile ${getCache()}")
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url
                    )
                    sources.addSource(
                        source
                    )
                }
                _channelsOk.value = true
                R.string.channel_import_success.showToast()
                Log.i(TAG, "channel import success")
            } else {
                R.string.channel_import_error.showToast()
                Log.w(TAG, "channel import error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryStr2Channels", e)
            file?.deleteOnExit()
            R.string.channel_read_error.showToast()
        }
    }

    private fun str2Channels(str: String): Boolean {
        var string = str
        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val g = Gua()
        if (g.verify(str)) {
            string = g.decode(str)
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty")
            return false
        }

        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when (string[0]) {
            '[' -> {
                try {
                    list = gson.fromJson(string, typeTvList)
                    Log.i(TAG, "json解析完成 ${list.size} 个频道")
                } catch (e: Exception) {
                    Log.e(TAG, "str2Channels", e)
                    return false
                }
            }

            '#' -> {
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val numRegex = Regex("""tvg-chno="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")
                // 回看支持：解析每个频道的独立 catchup-source
                val catchupSourceRegex = Regex("""catchup-source="([^"]+)"""")

                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, MutableList<TV>>()
                var globalCatchupSource = ""

                var tv = TV()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    if (trimmedLine.startsWith("#EXTM3U")) {
                        // 解析全局 EPG URL
                        epgUrl = epgRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                        // 解析全局回看源模板（作为默认值）
                        globalCatchupSource = catchupSourceRegex.find(trimmedLine)?.groupValues?.get(1)?.trim() ?: ""
                        Log.d(TAG, "全局 catchupSource: $globalCatchupSource")
                    } else if (trimmedLine.startsWith("#EXTINF")) {
                        // 保存上一个 TV 到 map
                        val key = tv.group + tv.name
                        if (key.isNotEmpty()) {
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = mutableListOf()
                            }
                            tvMap[key]!!.add(tv)
                        }
                        
                        // 创建新的 TV 对象
                        tv = TV()
                        val info = trimmedLine.split(",")
                        tv.title = info.last().trim()
                        var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        tv.name = if (name.isNullOrEmpty()) tv.title else name
                        tv.logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        tv.number = numRegex.find(info.first())?.groupValues?.get(1)?.trim()?.toInt() ?: -1
                        tv.group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        
                        // 【修复】解析每个频道独立的 catchup-source，若无则使用全局默认值
                        val lineCatchupSource = catchupSourceRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        tv.catchupSource = lineCatchupSource ?: globalCatchupSource
                        
                        if (tv.catchupSource.isNotEmpty()) {
                            Log.d(TAG, "频道 ${tv.name} catchupSource: ${tv.catchupSource}")
                        }
                    } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                        val keyValue =
                            trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                        if (keyValue.size == 2) {
                            tv.headers = if (tv.headers == null) {
                                mapOf<String, String>(keyValue[0] to keyValue[1])
                            } else {
                                tv.headers!!.toMutableMap().apply {
                                    this[keyValue[0]] = keyValue[1]
                                }
                            }
                        }
                    } else if (!trimmedLine.startsWith("#")) {
                        tv.uris = if (tv.uris.isEmpty()) {
                            listOf(trimmedLine)
                        } else {
                            tv.uris.toMutableList().apply {
                                this.add(trimmedLine)
                            }
                        }
                    }
                }
                
                // 保存最后一个 TV
                val key = tv.group + tv.name
                if (key.isNotEmpty()) {
                    if (!tvMap.containsKey(key)) {
                        tvMap[key] = mutableListOf()
                    }
                    tvMap[key]!!.add(tv)
                }
                
                // 合并同名频道（保留每个频道独立的 catchupSource）
                for ((_, tvList) in tvMap) {
                    val uris = tvList.flatMap { t -> t.uris }
                    val t0 = tvList[0]
                    
                    // 查找第一个有 catchupSource 的频道作为默认
                    val catchupSource = tvList.firstOrNull { it.catchupSource.isNotEmpty() }?.catchupSource 
                        ?: globalCatchupSource
                    
                    val t1 = TV(
                        -1,
                        t0.name,
                        t0.title,
                        "",
                        t0.logo,
                        "",
                        uris,
                        0,
                        t0.headers,
                        t0.group,
                        SourceType.UNKNOWN,
                        t0.number,
                        emptyList(),
                        catchupSource,
                    )
                    l.add(t1)
                }
                list = l
                Log.i(TAG, "m3u解析完成 ${list.size} 个频道，EPG URL: $epgUrl")
            }

            else -> {
                val lines = string.lines()
                var group = ""
                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<String>>()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            val arr = trimmedLine.split(',').map { it.trim() }
                            val title = arr.first().trim()
                            val uris = arr.drop(1)

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = listOf(group)
                            }
                            tvMap[key] = tvMap[key]!! + uris
                        }
                    }
                }
                for ((title, uris) in tvMap) {
                    val channelGroup = uris.first();
                    uris.drop(1);
                    val tv = TV(
                        -1,
                        "",
                        title.removePrefix(channelGroup),
                        "",
                        "",
                        "",
                        uris,
                        0,
                        emptyMap(),
                        channelGroup,
                        SourceType.UNKNOWN,
                        -1,
                        emptyList(),
                    )

                    l.add(tv)
                }
                list = l
                Log.d(TAG, "txt解析 $list")
                Log.i(TAG, "txt解析完成 ${list.size} 个频道")
            }
        }

        groupModel.initTVGroup()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val listTVModel = TVListModel(k.ifEmpty { "??" }, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.setLike(SP.getLike(id))
                v1.setGroupIndex(groupIndex)
                v1.listIndex = listIndex
                listTVModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(listTVModel)
            groupIndex++
        }

        listModel = listModelNew

        // ????
        groupModel.tvGroupValue[1].setTVListModel(listModel)

        if (string != cacheChannels && g.encode(string) != cacheChannels) {
            groupModel.initPosition()
        }

        groupModel.setChange()

        viewModelScope.launch {
            preloadLogo()
        }

        return true
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels
        
        // 【第10个自选源】kwrt100 NAS 视频源（当本地缓存为空时尝试加载）
        const val DEFAULT_CHANNELS_URL = "https://kwrt100.diskstation.dynv6.net:5666/3.m3u"
        
        // 默认 EPG URL（用于默认视频源的节目指南）
        const val DEFAULT_EPG_URL = "http://e.erw.cc/all.xml.gz"
    }
}