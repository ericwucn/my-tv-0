package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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

    // 时移模式
    private val maxRewindSeconds = 90 * 60L // 最大回退 90 分钟（秒）
    private var rewindSeconds = 0L          // 时移后退秒数（0 = 直播位置）
    
    // 按键步长（毫秒）
    private val rewindStepShort = 30 * 1000L // 按一次左键：回退30秒
    private val rewindStepLong = 5 * 60 * 1000L // 长按左键：每秒5分钟（时移模式）
    private val forwardStepShort = 60 * 1000L // 按一次右键：快进1分钟
    private val forwardStepLong = 5 * 60 * 1000L // 长按右键：每秒5分钟（时移模式）

    // 回放模式遥控器长按检测：记录每种按键的首次按下时间
    private var rewindFirstDownTime = 0L
    private var forwardFirstDownTime = 0L
    private val replayLongPressMs = 300 * 1000L // 回放模式长按：每次300秒

    // 回放模式长按连续快退/快进
    private var replayRewindRunnable: Runnable? = null
    private var replayForwardRunnable: Runnable? = null
    private var isReplayRewindContinuous = false
    private var isReplayForwardContinuous = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlaybackControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 长按检测（统一遥控器和触摸）
    private val LONG_PRESS_MS = 500L
    private var rewindLongPressRunnable: Runnable? = null
    private var forwardLongPressRunnable: Runnable? = null
    private var isRewindContinuous = false
    private var isForwardContinuous = false
    private val continuousRunnable = object : Runnable {
        override fun run() {
            if (isRewindContinuous) {
                rewind(rewindStepLong)
                handler.postDelayed(this, 1000)
            } else if (isForwardContinuous) {
                forward(forwardStepLong)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 触摸屏：使用 OnTouchListener 处理按钮
        binding.btnRewind.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scheduleLongPress(isRewind = true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(isRewind = true)
                    true
                }
                else -> false
            }
        }

        binding.btnForward.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scheduleLongPress(isRewind = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(isRewind = false)
                    true
                }
                else -> false
            }
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

        updateHint()
    }

    /**
     * 遥控器按键按下（由 MainActivity.onKeyDown 调用）
     * 回放模式：注册按下时间，松开时判断短按/长按
     * 时移模式：注册长按检测，短按 30秒/60秒，长按每秒 5分钟
     * @param justShown 右键刚显示播控界面时为 true，此时第一次右键只显示界面不触发快进
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent?, justShown: Boolean = false): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isCatchupMode) {
                    // 回放模式：记录按下时间，注册长按检测
                    if (event?.repeatCount ?: 0 == 0) {
                        rewindFirstDownTime = System.currentTimeMillis()
                        scheduleReplayLongPress(isRewind = true)
                    }
                    // 不要在这里执行 rewind()！由 handleKeyUp 根据按压时长决定
                } else {
                    // 时移模式：长按检测，短按 30秒
                    if (event?.repeatCount ?: 0 > 0) return true // 吞掉 auto-repeat，触摸处理长按
                    scheduleLongPress(isRewind = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 刚显示播控时，第一次右键只显示界面，不触发快进
                if (justShown) {
                    Log.i(TAG, "播控刚显示，忽略首次右键快进")
                    return true
                }
                if (isCatchupMode) {
                    // 回放模式：记录按下时间，注册长按检测
                    if (event?.repeatCount ?: 0 == 0) {
                        forwardFirstDownTime = System.currentTimeMillis()
                        scheduleReplayLongPress(isRewind = false)
                    }
                    // 不要在这里执行 forward()！由 handleKeyUp 根据按压时长决定
                } else {
                    // 时移模式：长按检测，短按 60秒
                    if (event?.repeatCount ?: 0 > 0) return true
                    scheduleLongPress(isRewind = false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePlayPause()
                return true
            }
        }
        return false
    }

    /**
     * 遥控器按键松开（由 MainActivity.onKeyUp 调用）
     * 时移模式：检测短按/长按，决定是否停止连续快退/快进
     * 回放模式：检测短按/长按，短按退30秒/进60秒，长按停止连续执行
     */
    fun handleKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isCatchupMode) {
                    // 取消长按检测 runnable
                    replayRewindRunnable?.let { handler.removeCallbacks(it) }
                    replayRewindRunnable = null
                    val elapsed = System.currentTimeMillis() - rewindFirstDownTime
                    if (isReplayRewindContinuous) {
                        // 长按松开：停止连续快退
                        isReplayRewindContinuous = false
                        Log.i(TAG, "回放模式长按快退停止")
                    } else if (elapsed < LONG_PRESS_MS) {
                        // 短按：退 30秒
                        rewind(rewindStepShort)
                    }
                } else {
                    cancelLongPress(isRewind = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isCatchupMode) {
                    // 取消长按检测 runnable
                    replayForwardRunnable?.let { handler.removeCallbacks(it) }
                    replayForwardRunnable = null
                    val elapsed = System.currentTimeMillis() - forwardFirstDownTime
                    if (isReplayForwardContinuous) {
                        // 长按松开：停止连续快进
                        isReplayForwardContinuous = false
                        Log.i(TAG, "回放模式长按快进停止")
                    } else if (elapsed < LONG_PRESS_MS) {
                        // 短按：进 60秒
                        forward(forwardStepShort)
                    }
                } else {
                    cancelLongPress(isRewind = false)
                }
                return true
            }
        }
        return false
    }

    /**
     * 按下时：注册长按检测，500ms 后开始连续快退/快进
     */
    private fun scheduleLongPress(isRewind: Boolean) {
        // 取消所有可能的旧任务，防止旧 runnable 继续执行导致步长错乱
        handler.removeCallbacks(continuousRunnable)
        rewindLongPressRunnable?.let { handler.removeCallbacks(it) }
        forwardLongPressRunnable?.let { handler.removeCallbacks(it) }
        isRewindContinuous = false
        isForwardContinuous = false
        
        val runnable = Runnable {
            if (isRewind) {
                isRewindContinuous = true
                Log.i(TAG, "长按快退开始")
            } else {
                isForwardContinuous = true
                Log.i(TAG, "长按快进开始")
            }
            handler.post(continuousRunnable)
        }
        if (isRewind) {
            rewindLongPressRunnable = runnable
        } else {
            forwardLongPressRunnable = runnable
        }
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    /**
     * 松开时：如果还没进入长按模式则执行单击，否则停止连续快退/快进
     */
    private fun cancelLongPress(isRewind: Boolean) {
        if (isRewind) {
            rewindLongPressRunnable?.let { handler.removeCallbacks(it) }
            rewindLongPressRunnable = null
        } else {
            forwardLongPressRunnable?.let { handler.removeCallbacks(it) }
            forwardLongPressRunnable = null
        }

        if (isRewindContinuous) {
            // 长按松开：停止连续快退
            isRewindContinuous = false
            handler.removeCallbacks(continuousRunnable)
            Log.i(TAG, "长按快退停止")
        } else if (isForwardContinuous) {
            // 长按松开：停止连续快进
            isForwardContinuous = false
            handler.removeCallbacks(continuousRunnable)
            Log.i(TAG, "长按快进停止")
        } else {
            // 短按（< 500ms）：执行单次操作
            if (isRewind) {
                rewind(rewindStepShort)
            } else {
                forward(forwardStepShort)
            }
        }
    }

    /**
     * 回放模式长按检测：500ms 后开始连续快退/快进（每次 300秒）
     */
    private fun scheduleReplayLongPress(isRewind: Boolean) {
        // 取消旧的
        handler.removeCallbacks(replayRewindRunnable ?: Runnable {})
        handler.removeCallbacks(replayForwardRunnable ?: Runnable {})
        isReplayRewindContinuous = false
        isReplayForwardContinuous = false

        val continuous = Runnable {
            // 先标记为连续模式，确保 handleKeyUp 检测时能正确判断
            if (isRewind) {
                isReplayRewindContinuous = true
                Log.i(TAG, "回放模式长按快退开始")
            } else {
                isReplayForwardContinuous = true
                Log.i(TAG, "回放模式长按快进开始")
            }

            // 立即执行第一次跳转
            if (isRewind) {
                rewind(replayLongPressMs)
            } else {
                forward(replayLongPressMs)
            }

            // 之后每秒执行一次
            lateinit var rep: Runnable
            rep = Runnable {
                if (isRewind && isReplayRewindContinuous) {
                    rewind(replayLongPressMs)
                    handler.postDelayed(rep, 1000)
                } else if (!isRewind && isReplayForwardContinuous) {
                    forward(replayLongPressMs)
                    handler.postDelayed(rep, 1000)
                }
            }
            handler.postDelayed(rep, 1000)
        }

        if (isRewind) {
            replayRewindRunnable = continuous
        } else {
            replayForwardRunnable = continuous
        }
        handler.postDelayed(continuous, LONG_PRESS_MS)
    }

    fun setPlayerFragment(fragment: PlayerFragment) {
        this.playerFragment = fragment
    }

    /**
     * 设置当前的 TVModel
     * 换台时重置 rewindSeconds 和 timeShiftStartPosition
     * 右键打开播控时，如果有 rewindSeconds > 0，重新计算 timeShiftStartPosition
     */
    fun setTVModel(model: TVModel) {
        this.tvModel = model
        isCatchupMode = model.isInCatchupMode
        
        if (isCatchupMode) {
            // 回放模式：记录节目时间范围
            model.epgValue.firstOrNull()?.let { epg ->
                catchupStartTime = epg.beginTime.toLong() * 1000
                catchupEndTime = epg.endTime.toLong() * 1000
            }
        } else {
            // 时移模式：右键打开播控时，如果有 rewindSeconds > 0，重新计算 seekTime
            if (rewindSeconds > 0) {
                // 时移模式不需要在这里处理，由 rewind/forward 方法处理
            }
        }
        
        Log.i(TAG, "setTVModel: isCatchupMode=$isCatchupMode, rewindSeconds=$rewindSeconds")
        updateUI()
    }
    
    /**
     * 换台时调用，重置时移状态
     */
    fun resetTimeShift() {
        rewindSeconds = 0
        Log.i(TAG, "resetTimeShift: rewindSeconds=$rewindSeconds")
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
        activity?.runOnUiThread {
            if (isCatchupMode) {
                // 回放模式
                val player = playerFragment?.getPlayer() ?: return@runOnUiThread
                val playerDuration = player.duration
                val position = player.currentPosition
                if (playerDuration <= 0) return@runOnUiThread
                val progress = ((position.toDouble() / playerDuration) * 1000).toInt()
                binding.seekbar.progress = progress
                binding.timeCurrent.text = formatTime(position)
                binding.timeDuration.text = formatTime(playerDuration)
            } else if (SP.enableTimeShift) {
                // 时移模式（已启用）
                // 进度：rewindSeconds=0时在右侧(LIVE)，rewindSeconds=max时在左侧(90分钟前)
                binding.seekbar.max = 1000
                val progress = (((maxRewindSeconds - rewindSeconds).toDouble() / maxRewindSeconds) * 1000).toInt().coerceIn(0, 1000)
                binding.seekbar.progress = progress
                // 左侧显示-90分钟（最大回退），右侧显示LIVE
                binding.timeCurrent.text = "-${formatTime(maxRewindSeconds * 1000L)}"
                binding.timeDuration.text = "LIVE"
                binding.programTitle.text = if (rewindSeconds > 0) "时移 -${formatTime(rewindSeconds * 1000L)}" else "直播"
            } else {
                // 直播模式（时移已禁用）
                // 不显示进度条，只显示直播状态
                binding.seekbar.max = 1000
                binding.seekbar.progress = 1000 // 固定在 LIVE 位置
                binding.timeCurrent.text = ""
                binding.timeDuration.text = "LIVE"
                binding.programTitle.text = "直播"
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
            // Catchup mode: seek backward within the current media duration
            val currentPosition = player.currentPosition
            val newPosition = (currentPosition - ms).coerceAtLeast(0L)
            player.seekTo(newPosition)
        } else {
            // 时移模式：检查是否启用时移功能
            if (!SP.enableTimeShift) {
                Log.i(TAG, "时移功能已禁用")
                return
            }
            // 使用 startTimeShift
            rewindSeconds += ms / 1000
            rewindSeconds = rewindSeconds.coerceAtMost(maxRewindSeconds)

            val now = System.currentTimeMillis() / 1000
            val seekTime = now - rewindSeconds
            Log.i(TAG, "时移后退: rewindSeconds=$rewindSeconds, seekTime=$seekTime")
            (activity as? MainActivity)?.startTimeShift(seekTime)
        }
        updateProgress()
    }

    private fun forward(ms: Long) {
        val player = playerFragment?.getPlayer() ?: return

        if (isCatchupMode) {
            // Catchup mode: seek forward within the current media duration
            val currentPosition = player.currentPosition
            val duration = player.duration
            val cap = if (duration > 0) duration else Long.MAX_VALUE
            val newPosition = (currentPosition + ms).coerceAtMost(cap)
            player.seekTo(newPosition)
        } else {
            // 时移模式：检查是否启用时移功能
            if (!SP.enableTimeShift) {
                Log.i(TAG, "时移功能已禁用")
                return
            }
            // 向NOW靠近
            rewindSeconds -= ms / 1000
            if (rewindSeconds <= 0) {
                rewindSeconds = 0
                Log.i(TAG, "时移回到直播")
                // 修复：时移模式回到直播，需要恢复原始直播 URL 并重新播放
                // exitCatchupMode() 只用于回放模式，时移模式需要直接恢复直播
                tvModel?.let { model ->
                    model.catchupOriginalUris?.let { originalUris ->
                        model.tv = model.tv.copy(uris = originalUris)
                        playerFragment?.play(model)
                        playerFragment?.resumeLiveMode()
                        Log.i(TAG, "时移回到直播：恢复原始直播流")
                    }
                }
            } else {
                val now = System.currentTimeMillis() / 1000
                val seekTime = now - rewindSeconds
                Log.i(TAG, "时移快进: rewindSeconds=$rewindSeconds, seekTime=$seekTime")
                (activity as? MainActivity)?.startTimeShift(seekTime)
            }
        }
        updateProgress()
    }

    private fun updateHint() {
        val hint = if (isCatchupMode) {
            "回放模式 | ←30秒 →1分钟 | 长按加速"
        } else if (SP.enableTimeShift) {
            "直播模式 | ←30秒 →1分钟 | 长按加速"
        } else {
            "直播模式 | 时移已禁用"
        }
        binding.hint.text = hint
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
        if (isCatchupMode) {
            val player = playerFragment?.getPlayer() ?: return
            val duration = player.duration
            if (duration > 0) {
                val position = (duration * progress / 1000.0).toLong()
                player.seekTo(position)
            }
        } else {
            // 时移模式：检查是否启用时移功能
            if (!SP.enableTimeShift) {
                Log.i(TAG, "时移功能已禁用")
                return
            }
            // 拖动进度条
            // progress=1000在最右侧(LIVE)，progress=0在最左侧(90分钟前)
            rewindSeconds = ((1000 - progress).toDouble() / 1000.0 * maxRewindSeconds).toLong()
            
            if (rewindSeconds > 0) {
                val now = System.currentTimeMillis() / 1000
                val seekTime = now - rewindSeconds
                (activity as? MainActivity)?.startTimeShift(seekTime)
            } else {
                // 修复：时移模式回到直播，需要恢复原始直播 URL 并重新播放
                tvModel?.let { model ->
                    model.catchupOriginalUris?.let { originalUris ->
                        model.tv = model.tv.copy(uris = originalUris)
                        playerFragment?.play(model)
                        playerFragment?.resumeLiveMode()
                        Log.i(TAG, "进度条拖到 LIVE：恢复原始直播流")
                    }
                }
            }
        }
    }

    fun isCatchup(): Boolean = isCatchupMode
    fun isTimeShift(): Boolean = !isCatchupMode && rewindSeconds > 0
    fun isLive(): Boolean = !isCatchupMode && rewindSeconds == 0L

    /**
     * 获取当前回放节目的时间范围（秒），供 EPG 定位使用
     */
    fun getCatchupTimeRange(): Pair<Int, Int> {
        val begin = if (catchupStartTime > 0) (catchupStartTime / 1000).toInt() else 0
        val end = if (catchupEndTime > 0) (catchupEndTime / 1000).toInt() else 0
        return Pair(begin, end)
    }

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
