package com.lizongying.mytv0

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.ProgramItemBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class ProgramAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var epgList: List<EPG>,
    private var index: Int,
    // catchupSource 模板，如 ?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}
    private var catchupSource: String = "",
) :
    RecyclerView.Adapter<ProgramAdapter.ViewHolder>() {

    private var listener: ItemListener? = null
    private var focused: View? = null
    val application = context.applicationContext as MyTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ProgramItemBinding.inflate(inflater, parent, false)

        val textSize = application.px2PxFont(binding.title.textSize)
        binding.date.textSize = textSize - 2
        binding.title.textSize = textSize
        binding.description.textSize = textSize

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val epg = epgList[position]
        val view = viewHolder.itemView
        val now = Utils.getDateTimestamp()
        val isPast = epg.endTime < now
        val hasCatchup = isPast && catchupSource.isNotEmpty()

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            listener?.onItemFocusChange(epg, hasFocus)
            val isCurrent = position == index
            if (hasFocus) {
                viewHolder.focus(true, isCurrent)
                focused = view
            } else {
                viewHolder.focus(false, isCurrent)
            }
        }

        // 点击/确定：仅支持回看（过去的节目）
        view.setOnClickListener {
            if (hasCatchup) {
                listener?.onCatchupClick(epg, catchupSource)
            }
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener true
                }
                // 确认键触发回看
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (hasCatchup) {
                        listener?.onCatchupClick(epg, catchupSource)
                        return@setOnKeyListener true
                    }
                }
            }
            if (event?.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    val p = getItemCount() - 1
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }
                return@setOnKeyListener listener?.onKey(keyCode) == true
            }
            false
        }

        viewHolder.bindTitle(epg, hasCatchup)
    }

    override fun getItemCount() = epgList.size

    class ViewHolder(private val context: Context, private val binding: ProgramItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindTitle(epg: EPG, hasCatchup: Boolean) {
            // 显示日期和周几
            val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
            val weekdayFormat = SimpleDateFormat("E", Locale.CHINESE)
            val dateStr = dateFormat.format(Date(epg.beginTime.toLong() * 1000))
            val weekdayStr = weekdayFormat.format(Date(epg.beginTime.toLong() * 1000))
            // 将英文周几转为中文
            val weekdayCn = when (weekdayStr) {
                "Mon" -> "周一"
                "Tue" -> "周二"
                "Wed" -> "周三"
                "Thu" -> "周四"
                "Fri" -> "周五"
                "Sat" -> "周六"
                "Sun" -> "周日"
                else -> weekdayStr
            }
            binding.date.text = "$dateStr $weekdayCn"
            
            binding.title.text = "${Utils.getDateFormat("HH:mm", epg.beginTime)}-${
                Utils.getDateFormat("HH:mm", epg.endTime)
            }"
            binding.description.text = epg.title
            // 显示/隐藏回看标识
            binding.catchupBadge.visibility = if (hasCatchup) View.VISIBLE else View.GONE
        }

        fun focus(hasFocus: Boolean, isCurrent: Boolean) {
            if (hasFocus) {
                val color = ContextCompat.getColor(context, R.color.focus)
                binding.date.setTextColor(color)
                binding.title.setTextColor(color)
                binding.description.setTextColor(color)
            } else {
                if (isCurrent) {
                    val color = ContextCompat.getColor(context, R.color.white)
                    binding.date.setTextColor(color)
                    binding.title.setTextColor(color)
                    binding.description.setTextColor(color)
                } else {
                    val color = ContextCompat.getColor(context, R.color.description_blur)
                    binding.date.setTextColor(color)
                    binding.title.setTextColor(color)
                    binding.description.setTextColor(color)
                }
            }
        }
    }

    /**
     * 滚动到指定位置并选中有焦点，定位到屏幕中央
     * @param position 要定位的位置
     * @param currentTimestamp 当前播放时间戳（秒），用于回放模式定位当前节目
     */
    fun scrollToPositionAndSelect(position: Int, currentTimestamp: Long = 0) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let { lm ->
            recyclerView.postDelayed({
                // 获取 RecyclerView 的高度，计算中央偏移量
                val recyclerHeight = recyclerView.height
                if (recyclerHeight > 0) {
                    // 滚动到中央位置
                    lm.scrollToPositionWithOffset(position, recyclerHeight / 2)
                } else {
                    lm.scrollToPositionWithOffset(position, 0)
                }
                
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, 50)
        }
    }
    
    /**
     * 根据当前播放时间戳找到对应的节目位置
     * 用于回放模式下定位当前回放的节目
     */
    fun findPositionByTimestamp(timestamp: Long): Int {
        if (timestamp <= 0) return -1
        return epgList.indexOfFirst { epg ->
            timestamp >= epg.beginTime && timestamp < epg.endTime
        }
    }

    interface ItemListener {
        fun onItemFocusChange(epg: EPG, hasFocus: Boolean)
        fun onKey(keyCode: Int): Boolean
        // 回看回调：epg 节目信息，catchupSource 模板
        fun onCatchupClick(epg: EPG, catchupSource: String) {}
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "ProgramAdapter"
    }
}
