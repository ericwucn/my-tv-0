package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.ProgramBinding
import com.lizongying.mytv0.models.TVModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val delay: Long = 5000

    private lateinit var programAdapter: ProgramAdapter

    private lateinit var viewModel: MainViewModel

    // 回放模式时记录选择的节目信息
    private var playbackBeginTime: Int = 0
    private var playbackEndTime: Int = 0

    // 外部传入的回放信息（优先于成员变量）
    private var externalPlaybackBegin: Int = 0
    private var externalPlaybackEnd: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ProgramBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.program.setOnClickListener {
            hideSelf()
        }

        onVisible()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    private val hideRunnable = Runnable {
        hideSelf()
    }

    fun onVisible() {
        val context = requireActivity()
        val now = Utils.getDateTimestamp()

        viewModel.groupModel.getCurrent()?.let { tvModel ->
            val catchupSource = tvModel.tv.catchupSource
            
            // 获取 EPG 列表（显示所有日期）
            val epgList = tvModel.epgValue.sortedBy { it.beginTime }
            if (epgList.isEmpty()) return
            
            // 确定目标位置：默认选中当前节目
            var targetPosition = 0
            
            if (tvModel.isInCatchupMode) {
                // 回放模式：优先使用外部传入的回放时间，否则用 URL 解析
                val playbackBegin = if (externalPlaybackBegin > 0) externalPlaybackBegin else playbackBeginTime
                val playTimestamp = if (playbackBegin > 0) {
                    playbackBegin.toLong()
                } else {
                    parseTimestamp(tvModel.tv.uris.firstOrNull())
                }

                if (playTimestamp > 0) {
                    targetPosition = epgList.indexOfFirst { epg ->
                        playTimestamp >= epg.beginTime && playTimestamp < epg.endTime
                    }
                    if (targetPosition < 0) targetPosition = 0
                }
            } else {
                // 直播/时移模式：定位到当前正在播放的节目
                targetPosition = epgList.indexOfFirst { epg ->
                    epg.beginTime <= now && epg.endTime > now
                }
                if (targetPosition < 0) targetPosition = 0
            }

            programAdapter = ProgramAdapter(
                context,
                binding.list,
                epgList,
                targetPosition,
                catchupSource,
            )
            binding.list.adapter = programAdapter
            binding.list.layoutManager = LinearLayoutManager(context)

            programAdapter.setItemListener(this)

            // 滚动到中央位置
            programAdapter.scrollToPositionAndSelect(targetPosition)

            handler.postDelayed(hideRunnable, delay)
        }
    }
    
    /**
     * 记录回放选择的节目信息（供外部调用）
     */
    fun recordPlaybackInfo(beginTime: Int, endTime: Int) {
        playbackBeginTime = beginTime
        playbackEndTime = endTime
        Log.i(TAG, "recordPlaybackInfo: beginTime=$beginTime, endTime=$endTime")
    }

    /**
     * 设置外部回放信息（由 MainActivity 在显示 EPG 前调用）
     * 优先级高于内部 recordPlaybackInfo
     */
    fun setExternalPlaybackInfo(beginTime: Int, endTime: Int) {
        externalPlaybackBegin = beginTime
        externalPlaybackEnd = endTime
        Log.i(TAG, "setExternalPlaybackInfo: beginTime=$beginTime, endTime=$endTime")
    }

    /**
     * 清空外部回放信息（使用后调用）
     */
    fun clearExternalPlaybackInfo() {
        externalPlaybackBegin = 0
        externalPlaybackEnd = 0
    }
    
    /**
     * 从 URL 中解析时间戳
     */
    private fun parseTimestamp(url: String?): Long {
        if (url.isNullOrEmpty()) return 0
        val timestampPatterns = listOf(
            Regex("""[?&]start[=_](\d+)"""),
            Regex("""[?&]t[=_](\d+)"""),
            Regex("""[?&]time[=_](\d+)"""),
            Regex("""[?&]begin[=_](\d+)"""),
            Regex("""/(\d{10})(?:[/\?&]|$)""")
        )
        for (pattern in timestampPatterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toLongOrNull() ?: 0
            }
        }
        return 0
    }

    fun onHidden() {
        handler.removeCallbacks(hideRunnable)
        clearExternalPlaybackInfo()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
        } else {
            onHidden()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onItemFocusChange(epg: EPG, hasFocus: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    override fun onKey(keyCode: Int): Boolean {
        return false
    }

    /**
     * 回看点击回调：根据 catchupSource 模板构建回放 URL 并播放
     * 
     * 支持的 URL 格式：
     * 1. ${(b)yyyyMMddHHmmss} - 开始时间格式化
     * 2. ${(e)yyyyMMddHHmmss} - 结束时间格式化
     * 3. {start} / {end} - Unix 时间戳（秒）
     * 4. {timestamp} - 开始时间戳
     * 5. {duration} - 节目时长（秒）
     * 6. (b)format / (e)format - 简化格式
     */
    override fun onCatchupClick(epg: EPG, catchupSource: String) {
        handler.removeCallbacks(hideRunnable)

        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val baseUrl = tvModel.tv.uris.firstOrNull() ?: return

        // 记录回放节目信息（供下次打开 EPG 时定位用）
        recordPlaybackInfo(epg.beginTime, epg.endTime)

        // 同步通知 PlaybackControlFragment 记录时间范围（它的 getCatchupTimeRange() 要用这个）
        (activity as? MainActivity)?.notifyCatchupTimeRange(epg.beginTime, epg.endTime)

        // 构建回看 URL
        val catchupUrl = buildCatchupUrl(baseUrl, catchupSource, epg.beginTime, epg.endTime)
        Log.i(TAG, "回看 URL: $catchupUrl")

        // 调用 MainActivity 播放回看 URL
        (activity as? MainActivity)?.playCatchup(catchupUrl)

        hideSelf()
    }

    /**
     * 构建回看 URL
     * 支持多种 IPTV 源使用的占位符格式
     * 
     * 【关键修复】正确拼接 URL：
     * - catchupSource 通常以 "?" 开头（如 "?playseek=..."）
     * - baseUrl 可能已有查询参数（如 "?fmt=ts2hls&..."）
     * - 若 baseUrl 已有 "?"，则用 "&" 连接；否则用 "?"
     */
    private fun buildCatchupUrl(baseUrl: String, catchupSource: String, beginTime: Int, endTime: Int): String {
        val beginDate = Date(beginTime.toLong() * 1000)
        val endDate = Date(endTime.toLong() * 1000)
        val duration = endTime - beginTime

        var result = catchupSource

        Log.d(TAG, "buildCatchupUrl 开始: baseUrl=${baseUrl.take(100)}..., catchupSource=$catchupSource")
        Log.d(TAG, "时间范围: beginTime=$beginTime, endTime=$endTime, duration=$duration 秒")
        Log.d(TAG, "开始时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(beginDate)}")
        Log.d(TAG, "结束时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(endDate)}")

        // 格式1: ${(b)format} 和 ${(e)format} - 带 $ 前缀的花括号格式
        // 例如: ${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}
        result = Regex("""\$\{(\([be]\))([^}]+)\}""").replace(result) { match ->
            val type = match.groupValues[1]  // (b) 或 (e)
            val format = match.groupValues[2] // yyyyMMddHHmmss 等
            val date = if (type == "(b)") beginDate else endDate
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                Log.w(TAG, "日期格式化失败: $format", e)
                match.value
            }
        }

        // 格式2: {start} 和 {end} - Unix 时间戳（秒）
        result = result.replace("{start}", beginTime.toString())
        result = result.replace("{end}", endTime.toString())

        // 格式3: {timestamp} - 开始时间戳
        result = result.replace("{timestamp}", beginTime.toString())

        // 格式4: {duration} - 节目时长（秒）
        result = result.replace("{duration}", duration.toString())

        // 格式5: (b)format 和 (e)format - 不带花括号的格式
        // 例如: (b)yyyyMMddHHmmss-(e)yyyyMMddHHmmss
        result = Regex("""\(([be])\)([a-zA-Z0-9]+)""").replace(result) { match ->
            val type = match.groupValues[1]  // b 或 e
            val format = match.groupValues[2] // yyyyMMddHHmmss 等
            val date = if (type == "b") beginDate else endDate
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                Log.w(TAG, "日期格式化失败: $format", e)
                match.value
            }
        }

        // 格式6: {utc} 和 {utcend} - Unix 时间戳（某些源使用）
        result = result.replace("{utc}", beginTime.toString())
        result = result.replace("{utcend}", endTime.toString())

        // 格式7: ${start} 和 ${end} - 带 $ 前缀但不带括号
        result = result.replace("\${start}", beginTime.toString())
        result = result.replace("\${end}", endTime.toString())

        // 格式8: 毫秒时间戳（某些源使用毫秒）
        result = result.replace("{startms}", (beginTime * 1000).toString())
        result = result.replace("{endms}", (endTime * 1000).toString())

        Log.d(TAG, "回看参数解析结果: $result")

        // 【关键修复】正确拼接 URL
        // catchupSource 可能以 "?" 或 "&" 开头，需要正确处理
        val finalUrl = when {
            // baseUrl 已有查询参数，result 以 "?" 开头 -> 把 "?" 改成 "&"
            baseUrl.contains("?") && result.startsWith("?") -> {
                baseUrl + "&" + result.substring(1)
            }
            // baseUrl 已有查询参数，result 以 "&" 开头 -> 直接拼接
            baseUrl.contains("?") && result.startsWith("&") -> {
                baseUrl + result
            }
            // baseUrl 已有查询参数，result 不以 "?" 或 "&" 开头 -> 用 "&" 连接
            baseUrl.contains("?") -> {
                baseUrl + "&" + result
            }
            // baseUrl 无查询参数，result 以 "&" 开头 -> 把 "&" 改成 "?"
            result.startsWith("&") -> {
                baseUrl + "?" + result.substring(1)
            }
            // baseUrl 无查询参数，result 以 "?" 开头 -> 直接拼接
            result.startsWith("?") -> {
                baseUrl + result
            }
            // baseUrl 无查询参数，result 不以 "?" 或 "&" 开头 -> 用 "?" 连接
            else -> {
                baseUrl + "?" + result
            }
        }
        
        Log.i(TAG, "回看最终URL: $finalUrl")

        return finalUrl
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}