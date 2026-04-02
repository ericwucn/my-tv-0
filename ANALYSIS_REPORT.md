# my-tv-0 代码审查报告

## 项目概述
- **项目**: Android TV 直播播放器
- **主要功能**: 支持 m3u/txt/json 格式视频源、EPG 节目单、回看功能
- **技术栈**: Kotlin, Media3 ExoPlayer, OkHttp, Coroutines

---

## 一、7天 EPG 支持分析

### 当前实现状态 ✅

`EPGXmlParser.kt` 已经实现了 7 天 EPG 历史：

```kotlin
private val now = getDateTimestamp()
private val sevenDaysAgo = now - 604800  // 7 天 (7*24*60*60)

// 保留最近 7 天的节目
val stopTime = formatFTime(stop)
if (stopTime > sevenDaysAgo) {
    val channelName = channelNames[channelId] ?: channelId
    epg.getOrPut(channelName) { mutableListOf() }
        .add(EPG(title, formatFTime(start), stopTime))
}
```

### 问题与改进建议

#### 问题 1: EPG 缓存过期机制缺失
**位置**: `MainViewModel.kt:97-106`

**问题**: EPG 缓存文件 `epg.xml` 没有过期检查，旧缓存可能导致节目单不更新。

**建议修复**:
```kotlin
// 在 readEPG 之前检查缓存时间
private fun isEPGCacheValid(): Boolean {
    val cacheFile = File(appDirectory, CACHE_EPG)
    if (!cacheFile.exists()) return false
    val lastModified = cacheFile.lastModified()
    val cacheAge = System.currentTimeMillis() - lastModified
    // 缓存有效期 6 小时
    return cacheAge < 6 * 60 * 60 * 1000
}
```

#### 问题 2: EPG 时间范围硬编码
**位置**: `EPGXmlParser.kt:20`

**问题**: `sevenDaysAgo` 在类初始化时计算，如果应用长时间运行，时间范围不会更新。

**建议修复**:
```kotlin
// 改为方法调用时计算
private fun getSevenDaysAgo(): Long {
    return getDateTimestamp() - 604800
}
```

---

## 二、m3u 回看支持分析

### 当前实现状态 ⚠️ 部分实现

已实现的功能：
1. `TV.kt` 有 `catchupSource` 字段
2. `MainViewModel.kt:264` 解析 `catchup-source` 属性
3. `ProgramFragment.kt` 和 `ProgramAdapter.kt` 实现回放 UI
4. `MainActivity.kt:173-192` 有 `playCatchup()` 和 `exitCatchupMode()` 方法

### 问题与改进建议

#### 问题 1: catchupSource 未正确传递给每个频道
**位置**: `MainViewModel.kt:264-295`

**严重程度**: 🔴 高

**问题描述**: 
全局 `catchupSource` 只赋值给合并后的 TV 对象，但 m3u 格式中每个频道可能有独立的 `catchup-source`。

```kotlin
// 当前代码 - 只使用全局 catchupSource
val t1 = TV(
    ...
    globalCatchupSource,  // 所有频道共用
)
```

**修复建议**:
```kotlin
// 在解析 #EXTINF 行时提取独立的 catchup-source
val catchupSourceRegex = Regex("""catchup-source="([^"]+)"""")
// 每个频道独立存储
tv.catchupSource = catchupSourceRegex.find(info.first())?.groupValues?.get(1)?.trim() 
    ?: globalCatchupSource
```

#### 问题 2: 回看 URL 拼接逻辑不完整
**位置**: `ProgramFragment.kt:103-122`

**问题描述**: 
`buildCatchupUrl` 只支持 `${(b)format}` 和 `${(e)format}` 占位符，但很多 IPTV 源使用其他格式：
- `{start}`, `{end}` (Unix 时间戳)
- `{timestamp}`, `{duration}`
- `(b)`, `(e)` 不带花括号

**修复建议**:
```kotlin
private fun buildCatchupUrl(baseUrl: String, catchupSource: String, beginTime: Int, endTime: Int): String {
    val beginDate = Date(beginTime.toLong() * 1000)
    val endDate = Date(endTime.toLong() * 1000)
    
    var result = catchupSource
    
    // 格式1: ${(b)yyyyMMddHHmmss}
    result = Regex("""\$\{(\([be]\))([^}]+)\}""").replace(result) { match ->
        val type = match.groupValues[1]
        val format = match.groupValues[2]
        val date = if (type == "(b)") beginDate else endDate
        SimpleDateFormat(format, Locale.getDefault()).format(date)
    }
    
    // 格式2: {start} {end} - Unix 时间戳
    result = result.replace("{start}", beginTime.toString())
    result = result.replace("{end}", endTime.toString())
    
    // 格式3: (b) (e) 不带花括号
    result = Regex("""\(([be])\)([a-zA-Z]+)""").replace(result) { match ->
        val type = match.groupValues[1]
        val format = match.groupValues[2]
        val date = if (type == "b") beginDate else endDate
        try {
            SimpleDateFormat(format, Locale.getDefault()).format(date)
        } catch (e: Exception) {
            match.value
        }
    }
    
    // 格式4: {timestamp} - 开始时间戳
    result = result.replace("{timestamp}", beginTime.toString())
    
    // 格式5: {duration} - 时长秒数
    result = result.replace("{duration}", (endTime - beginTime).toString())
    
    return baseUrl + result
}
```

#### 问题 3: 回看模式状态未持久化
**位置**: `TVModel.kt:18-19`

**问题描述**: `isInCatchupMode` 和 `catchupOriginalUris` 是运行时状态，频道切换时可能丢失。

**建议**: 这是预期行为，但应该在 UI 上有明确提示用户正在回看模式。

#### 问题 4: 回看 URL 可能需要独立的 headers
**位置**: `MainActivity.kt:173-185`

**问题描述**: 回看 URL 可能需要不同的请求头，当前直接使用原始频道的 headers。

---

## 三、启动卡死问题分析

### 潜在卡死点分析

#### 问题 1: 主线程网络请求
**位置**: `SimpleServer.kt:111-114`

**严重程度**: 🔴 高

**问题代码**:
```kotlin
private fun handleSources(): Response {
    val response = runBlocking(Dispatchers.IO) {
        fetchSources("https://raw.githubusercontent.com/...")
    }
    return newFixedLengthResponse(...)
}
```

**问题**: `runBlocking` 会阻塞当前线程，如果网络请求超时，会导致应用无响应。

**修复建议**:
```kotlin
// 设置合理的超时时间
private suspend fun fetchSources(url: String): String {
    return withTimeoutOrNull(10_000) {  // 10秒超时
        // ... 现有逻辑
    } ?: ""
}
```

#### 问题 2: 初始化时同步文件操作
**位置**: `MainViewModel.kt:81-95`

**问题代码**:
```kotlin
fun init(context: Context) {
    // ... 多个文件操作在主线程
    cacheFile = File(appDirectory, CACHE_FILE_NAME)
    if (!cacheFile!!.exists()) {
        cacheFile!!.createNewFile()
    }
    cacheChannels = getCache()  // 同步读取
    // ...
}
```

**修复建议**: 将初始化移到后台协程：
```kotlin
suspend fun initAsync(context: Context) = withContext(Dispatchers.IO) {
    // 所有文件操作
}
```

#### 问题 3: 时间同步网络请求
**位置**: `Utils.kt:53-61`

**问题代码**:
```kotlin
init {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val currentTimeMillis = getTimestampFromServer()
            // ...
        } catch (e: Exception) {
            // 静默失败
        }
    }
}
```

**问题**: 虽然在 IO 线程，但没有超时控制，可能长时间等待。

**修复建议**:
```kotlin
private suspend fun getTimestampFromServer(): Long {
    return withTimeoutOrNull(5_000) {  // 5秒超时
        // ... 现有逻辑
    } ?: 0
}
```

#### 问题 4: HttpClient 代理切换延迟
**位置**: `HttpClient.kt:28-47`

**问题描述**: 代理配置在 SP.init 后才生效，但 OkHttpClient 已经创建。虽然有缓存机制，但首次创建时可能阻塞。

#### 问题 5: EPG 加载阻塞
**位置**: `MainViewModel.kt:97-106`

**问题代码**:
```kotlin
viewModelScope.launch {
    cacheEPG = File(appDirectory, CACHE_EPG)
    if (!cacheEPG.exists()) {
        cacheEPG.createNewFile()
    } else {
        if (readEPG(cacheEPG.readText())) {  // 同步读取大文件
            // ...
        }
    }
}
```

**问题**: 大的 EPG 缓存文件可能导致加载延迟。

**修复建议**:
```kotlin
viewModelScope.launch(Dispatchers.IO) {
    // 文件操作在 IO 线程
    val epgText = withContext(Dispatchers.IO) {
        cacheEPG.readText()
    }
    withContext(Dispatchers.Main) {
        readEPG(epgText)
    }
}
```

#### 问题 6: Fragment 事务时机问题
**位置**: `MainActivity.kt:51-55`

**问题代码**:
```kotlin
if (savedInstanceState == null) {
    supportFragmentManager.beginTransaction()
        .add(R.id.main_browse_fragment, playerFragment)
        .add(R.id.main_browse_fragment, infoFragment)
        .add(R.id.main_browse_fragment, channelFragment)
        .commitNowAllowingStateLoss()  // 同步提交
}
```

**问题**: `commitNowAllowingStateLoss()` 同步执行，如果 Fragment 初始化有耗时操作会阻塞。

---

## 四、其他代码质量问题

### 问题 1: 协程作用域管理
**位置**: `Utils.kt:53`

**问题**: 使用 `CoroutineScope(Dispatchers.IO).launch` 创建协程，没有与生命周期绑定。

**建议**: 使用 `viewModelScope` 或 `lifecycleScope`。

### 问题 2: 错误处理不一致
**位置**: 多处

**问题**: 部分地方静默吞掉异常，部分地方只打印日志。

**建议**: 统一错误处理策略，关键错误应上报或提示用户。

### 问题 3: 内存泄漏风险
**位置**: `MainActivity.kt`

**问题**: `handler` 使用 `Looper.myLooper()!!`，如果 Activity 销毁时未清理，可能导致泄漏。

**建议**: 在 `onDestroy` 中移除所有 callbacks：
```kotlin
override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
}
```

---

## 五、修复优先级

| 优先级 | 问题 | 影响 | 修复难度 |
|--------|------|------|----------|
| P0 | 回看 catchupSource 未正确传递 | 回看功能不可用 | 中 |
| P0 | runBlocking 阻塞主线程 | 启动卡死 | 低 |
| P1 | 回看 URL 格式支持不全 | 部分源无法回看 | 中 |
| P1 | 网络请求无超时控制 | 启动卡死 | 低 |
| P2 | EPG 缓存过期检查 | 节目单不更新 | 低 |
| P2 | 内存泄漏风险 | 长时间运行问题 | 低 |

---

## 六、建议的修复代码

### 修复 1: MainViewModel - 正确传递 catchupSource

```kotlin
// MainViewModel.kt 第 220-250 行附近
} else if (trimmedLine.startsWith("#EXTINF")) {
    val key = tv.group + tv.name
    if (key.isNotEmpty()) {
        tvMap[key] = if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
    }
    tv = TV()
    val info = trimmedLine.split(",")
    tv.title = info.last().trim()
    var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
    tv.name = if (name.isNullOrEmpty()) tv.title else name
    tv.logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
    tv.number = numRegex.find(info.first())?.groupValues?.get(1)?.trim()?.toInt() ?: -1
    tv.group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
    
    // 添加：解析每个频道的独立 catchup-source
    val lineCatchupSource = catchupSourceRegex.find(info.first())?.groupValues?.get(1)?.trim()
    tv.catchupSource = lineCatchupSource ?: globalCatchupSource
}
```

### 修复 2: SimpleServer - 添加超时

```kotlin
// SimpleServer.kt
private fun handleSources(): Response {
    val response = runBlocking {
        withTimeoutOrNull(10_000) {  // 10秒超时
            fetchSources("https://raw.githubusercontent.com/...")
        } ?: ""
    }
    return newFixedLengthResponse(Response.Status.OK, "application/json", response)
}
```

### 修复 3: Utils - 添加网络超时

```kotlin
// Utils.kt
private suspend fun getTimestampFromServer(): Long {
    return withTimeoutOrNull(5_000) {
        withContext(Dispatchers.IO) {
            // ... 现有逻辑
        }
    } ?: 0
}
```

---

## 七、总结

### 7天 EPG 支持
✅ 基本功能已实现
⚠️ 需要增加缓存过期机制和时间动态计算

### m3u 回看支持
⚠️ 部分实现
🔴 关键问题：`catchupSource` 未正确从 m3u 传递到每个频道
🔴 关键问题：URL 格式支持不全

### 启动卡死问题
🔴 多处潜在卡死点：
1. `runBlocking` 阻塞
2. 网络请求无超时
3. 同步文件 I/O
4. Fragment 同步初始化

建议优先修复 P0 级别问题，特别是回看功能的 catchupSource 传递和网络请求超时控制。
