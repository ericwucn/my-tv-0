package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.lizongying.mytv0.databinding.PlaybackControlBinding
import com.lizongying.mytv0.models.TVModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放控制界面
 * 
 * 功能：
 * - 直播模式：显示播放控制，快退/快进有时间限制
 * - 回放模式：显示播放控制，快退/快进限制在当前节目范围内
 * 
 * 操作：
 * - 左键：快退 30 秒
 * - 右键：快进 1 分钟
 * - 确认键：播放/暂停
 * - 长按左/右：持续快退/快进
 * - 返回键：退出控制界面（回放时提示确认）
 */
class PlaybackControlFragment : Fragment() {

    private var _binding: PlaybackControlBinding? = null
    private val binding get() = _binding!!

    private var tvModel: TVModel? = null
    private var playerFragment: PlayerFragment? = null

    private val handler = Handler(Looper.myLooper()!!)
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    // 是否处于回放模式
    private var isCatchupMode = false
    
    // 回放节目时间范围
    private var catchupStartTime: Long = 0
    private var catchupEndTime: Long = 0
    
    // 是否正在长按快退/快进
    private var isRewinding = false
    private var isForwarding = false

    // 直播模式下的时间限制
    private val maxLiveRewind = 90 * 60 * 1000L // 最大回退 90 分钟
    private val liveForwardBuffer = 5 * 60 * 1000L // 直播时快进缓冲：5分钟
    
    // 直播快退相关
    private var isInTimeShiftMode = false // 是否处于时移模式
    private var timeShiftStartPosition = 0L // 时移开始时间（Unix秒）
    private var timeShiftBasePosition = 0L // 时移基准播放位置（毫秒）
    
    // 回放模式：节目前后缓冲
    private val catchupBuffer = 2 * 60 * 1000L // 回放节目前后各加2分钟
    
    // 按键步长
    private val rewindStepShort = 30 * 1000L // 按一次左键：回退30秒
    private val rewindStepLong = 60 * 1000L // 长按左键：每次60秒
    private val forwardStepShort = 60 * 1000L // 按一次右键：快进1分钟
    private val forwardStepLong = 120 * 1000L // 长按右键：每次120秒

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlaybackControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRewind.setOnClickListener {
            rewind(rewindStepShort) // 按一次：回退30秒
        }

        binding.btnForward.setOnClickListener {
            forward(forwardStepShort) // 按一次：快进1分钟
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekToProgress(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 设置按钮长按监听
        binding.btnRewind.setOnLongClickListener {
            startContinuousRewind()
            true
        }

        binding.btnForward.setOnLongClickListener {
            startContinuousForward()
            true
        }

        binding.btnRewind.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    stopContinuousSeek()
                }
            }
            false
        }

        binding.btnForward.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    stopContinuousSeek()
                }
            }
            false
        }

        updateHint()
        (activity as MainActivity).ready(TAG)
    }

    fun setPlayerFragment(fragment: PlayerFragment) {
        this.playerFragment = fragment
    }

    fun setTVModel(model: TVModel) {
        this.tvModel = model
        isCatchupMode = model.isInCatchupMode
        isInTimeShiftMode = false // 重置时移模式
        
        if (isCatchupMode) {
            // 回放模式：记录节目时间范围
            model.epgValue.firstOrNull()?.let { epg ->
                catchupStartTime = epg.beginTime.toLong() * 1000
                catchupEndTime = epg.endTime.toLong() * 1000
            }
        }
        
        updateUI()
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            tvModel?.let { model ->
                binding.title.text = model.tv.title
                
                // 显示当前节目
                val currentEpg = model.epgValue.firstOrNull()
                if (currentEpg != null) {
                    binding.programTitle.text = currentEpg.title
                } else {
                    binding.programTitle.text = if (isCatchupMode) "回放" else "直播"
                }
            }
            
            updateHint()
        }
    }

    private fun updateProgress() {
        val player = playerFragment?.getPlayer() ?: return
        val playerDuration = player.duration
        val position = player.currentPosition

        Log.d(TAG, "updateProgress: playerDuration=$playerDuration, position=$position, isCatchupMode=$isCatchupMode, isInTimeShiftMode=$isInTimeShiftMode")

        activity?.runOnUiThread {
            if (isCatchupMode) {
                // 回放模式：使用播放器的实际时间
                if (playerDuration <= 0) return@runOnUiThread
                val progress = ((position.toDouble() / playerDuration) * 1000).toInt()
                binding.seekbar.progress = progress
                binding.timeCurrent.text = formatTime(position)
                binding.timeDuration.text = formatTime(playerDuration)
                Log.d(TAG, "回放时间轴: position=${position/1000}秒, duration=${playerDuration/1000}秒, progress=$progress")
            } else if (isInTimeShiftMode) {
                // 时移模式：时间轴为 90 分钟窗口，右侧动态显示 NOW
                val windowDuration = maxLiveRewind // 90分钟
                
                // 当前位置在时间轴中的位置（相对于缓冲开始）
                val progress = (position.toDouble() / windowDuration * 1000).toInt()
                
                binding.seekbar.progress = progress.coerceIn(0, 1000)
                binding.seekbar.max = 1000
                
                // 显示偏移时间（负数表示回退了多久）
                val offsetText = "-${formatTime(position)}"
                binding.timeCurrent.text = offsetText
                binding.timeDuration.text = "NOW"
                
                Log.d(TAG, "时移时间轴: offset=${position/1000}秒, progress=$progress")
            } else {
                // 直播模式：时间轴为 NOW左侧90分钟，右侧NOW+5分钟
                val windowDuration = maxLiveRewind + liveForwardBuffer // 95分钟
                
                // 当前位置在时间轴的 90/95 处
                val progress = (maxLiveRewind.toDouble() / windowDuration * 1000).toInt()
                
                binding.seekbar.progress = progress.coerceIn(0, 1000)
                binding.seekbar.max = 1000
                
                // 时间显示
                binding.timeCurrent.text = "LIVE"
                binding.timeDuration.text = formatTime(windowDuration)
                
                Log.d(TAG, "直播时间轴: progress=$progress, windowDuration=${windowDuration/60000}分钟")
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun rewind(ms: Long) {
        val player = playerFragment?.getPlayer() ?: return
        
        if (isCatchupMode) {
            // 回放模式：正常快退
            var newPosition = player.currentPosition - ms
            newPosition = Math.max(newPosition, 0)
            player.seekTo(newPosition)
            Log.d(TAG, "回放快退: ${player.currentPosition} -> $newPosition")
        } else {
            // 直播/时移模式
            if (!isInTimeShiftMode) {
                // 第一次回退，切换到时移模式
                isInTimeShiftMode = true
                timeShiftStartPosition = System.currentTimeMillis() / 1000 - maxLiveRewind / 1000
                timeShiftBasePosition = 0
                Log.d(TAG, "切换到时移模式: startTime=$timeShiftStartPosition")
            }
            
            // 时移模式快退
            val currentPlaybackPosition = player.currentPosition
            var newPosition = currentPlaybackPosition - ms
            newPosition = Math.max(newPosition, 0)
            player.seekTo(newPosition)
            
            // 更新基准位置（相对于时移开始时间）
            timeShiftBasePosition = newPosition
            Log.d(TAG, "时移快退: position=$newPosition")
        }
        updateProgress()
    }

    private fun forward(ms: Long) {
        val player = playerFragment?.getPlayer() ?: return
        
        if (isCatchupMode) {
            // 回放模式：正常快进
            var newPosition = player.currentPosition + ms
            newPosition = Math.min(newPosition, player.duration)
            player.seekTo(newPosition)
            Log.d(TAG, "回放快进: ${player.currentPosition} -> $newPosition")
        } else if (isInTimeShiftMode) {
            // 时移模式快进：只能在已缓冲的内容范围内快进
            val currentPosition = player.currentPosition
            val duration = player.duration
            var newPosition = currentPosition + ms
            // 不能超过缓冲范围（即当前直播点）
            newPosition = Math.min(newPosition, duration)
            player.seekTo(newPosition)
            Log.d(TAG, "时移快进: $currentPosition -> $newPosition (max=$duration)")
        } else {
            // 直播模式（未回退）：不能快进
            Log.d(TAG, "直播模式：当前已是最新，无法快进")
        }
        updateProgress()
    }

    private fun startContinuousRewind() {
        isRewinding = true
        handler.post(object : Runnable {
            override fun run() {
                if (isRewinding) {
                    rewind(rewindStepLong) // 长按每次60秒
                    handler.postDelayed(this, 500)
                }
            }
        })
    }

    private fun startContinuousForward() {
        isForwarding = true
        handler.post(object : Runnable {
            override fun run() {
                if (isForwarding) {
                    forward(forwardStepLong) // 长按每次120秒
                    handler.postDelayed(this, 500)
                }
            }
        })
    }

    private fun stopContinuousSeek() {
        isRewinding = false
        isForwarding = false
    }

    private fun togglePlayPause() {
        val player = playerFragment?.getPlayer() ?: return
        if (player.isPlaying) {
            player.pause()
            binding.btnPlayPause.text = getString(R.string.play)
        } else {
            player.play()
            binding.btnPlayPause.text = getString(R.string.pause)
        }
    }

    private fun seekToProgress(progress: Int) {
        val player = playerFragment?.getPlayer() ?: return
        
        if (isCatchupMode) {
            // 回放模式：直接按进度条比例跳转（恢复到2041版本）
            val duration = player.duration
            if (duration > 0) {
                val position = (duration * progress / 1000.0).toLong()
                player.seekTo(position)
                Log.d(TAG, "回放 seekToProgress: progress=$progress, position=$position")
            }
        } else {
            // 直播模式：暂不支持拖动
            Log.d(TAG, "直播 seekToProgress: progress=$progress (暂不支持拖动)")
        }
    }

    fun handleKey(keyCode: Int): Boolean {
        Log.d(TAG, "handleKey: keyCode=$keyCode, isCatchupMode=$isCatchupMode")
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                rewind(rewindStepShort) // 左键一次：回退30秒
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                forward(forwardStepShort) // 右键一次：快进1分钟
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePlayPause()
                return true
            }
        }
        return false
    }

    private fun updateHint() {
        val hint = if (isCatchupMode) {
            "回放模式 | 左键-30秒 | 右键+1分钟 | 长按加速"
        } else {
            "直播模式 | 左键-30秒 | 右键+1分钟 | 长按加速"
        }
        binding.hint.text = hint
    }

    fun isCatchup(): Boolean = isCatchupMode

    override fun onResume() {
        super.onResume()
        handler.post(updateProgressRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateProgressRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    companion object {
        private const val TAG = "PlaybackControlFragment"
    }
}
