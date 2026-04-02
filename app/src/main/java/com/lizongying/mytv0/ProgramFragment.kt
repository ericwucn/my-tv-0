package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.ProgramBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000

    private lateinit var programAdapter: ProgramAdapter

    private lateinit var viewModel: MainViewModel

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

        viewModel.groupModel.getCurrent()?.let { tvModel ->
            val index = tvModel.epgValue.indexOfFirst { it.endTime > Utils.getDateTimestamp() }
            val catchupSource = tvModel.tv.catchupSource

            programAdapter = ProgramAdapter(
                context,
                binding.list,
                tvModel.epgValue,
                index,
                catchupSource,
            )
            binding.list.adapter = programAdapter
            binding.list.layoutManager = LinearLayoutManager(context)

            programAdapter.setItemListener(this)

            if (index > -1) {
                programAdapter.scrollToPositionAndSelect(index)
            }

            handler.postDelayed(hideRunnable, delay)
        }
    }

    fun onHidden() {
        handler.removeCallbacks(hideRunnable)
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
     */
    private fun buildCatchupUrl(baseUrl: String, catchupSource: String, beginTime: Int, endTime: Int): String {
        val beginDate = Date(beginTime.toLong() * 1000)
        val endDate = Date(endTime.toLong() * 1000)
        val duration = endTime - beginTime

        var result = catchupSource

        // 格式1: ${(b)format} 和 ${(e)format} - 带 $ 前缀的花括号格式
        // 例如: ${\((b)yyyyMMddHHmmss)}
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

        Log.d(TAG, "回看 URL 构建: $catchupSource -> $result")

        return baseUrl + result
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}