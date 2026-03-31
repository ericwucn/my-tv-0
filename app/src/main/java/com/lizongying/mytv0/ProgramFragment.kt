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
     * ????:? catchupSource ????????????????,
     * ????? URL ??,???????
     *
     * ????:?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}
     */
    override fun onCatchupClick(epg: EPG, catchupSource: String) {
        handler.removeCallbacks(hideRunnable)

        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val baseUrl = tvModel.tv.uris.firstOrNull() ?: return

        // ???????
        val catchupUrl = buildCatchupUrl(baseUrl, catchupSource, epg.beginTime, epg.endTime)
        Log.i(TAG, "?? URL: $catchupUrl")

        // ?? MainActivity ???? URL
        (activity as? MainActivity)?.playCatchup(catchupUrl)

        hideSelf()
    }

    /**
     * ???? URL
     * ?? ${(b)yyyyMMddHHmmss} ? ${(e)yyyyMMddHHmmss} ?????
     */
    private fun buildCatchupUrl(baseUrl: String, catchupSource: String, beginTime: Int, endTime: Int): String {
        val beginDate = Date(beginTime.toLong() * 1000)
        val endDate = Date(endTime.toLong() * 1000)

        // ?? ${(b)format} ? ${(e)format} ???
        val result = Regex("""\$\{(\([be]\))([^}]+)\}""").replace(catchupSource) { match ->
            val type = match.groupValues[1]  // (b) ? (e)
            val format = match.groupValues[2] // yyyyMMddHHmmss
            val date = if (type == "(b)") beginDate else endDate
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                match.value
            }
        }

        return baseUrl + result
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}