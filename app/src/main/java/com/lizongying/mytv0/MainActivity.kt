package com.lizongying.mytv0

import MainViewModel
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.lifecycle.Observer
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.databinding.SettingsWebBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private var ok = 0
    private var playerFragment = PlayerFragment()
    private val errorFragment = ErrorFragment()
    private val loadingFragment = LoadingFragment()
    private var infoFragment = InfoFragment()
    private var channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private var menuFragment = MenuFragment()
    private var settingFragment = SettingFragment()
    private var programFragment = ProgramFragment()
    private var playbackControlFragment = PlaybackControlFragment()

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    private val delayHideSetting = 3 * 60 * 1000L
    private val delayHidePlaybackControl = 10 * 1000L

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private var server: SimpleServer? = null

    private lateinit var viewModel: MainViewModel

    private var isSafeToPerformFragmentTransactions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            windowInsetsController.let { controller ->
                controller.isAppearanceLightNavigationBars = true
                controller.isAppearanceLightStatusBars = true
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.setAttributes(lp)
        }

        window.decorView.apply {
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        Log.i(TAG, "版本信息: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(this)

        // 处理外部Intent传入的URI（如分享的m3u链接）
        intent?.data?.let { uri ->
            Log.i(TAG, "处理外部URI: $uri")
            viewModel.importFromUri(uri)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .commitNowAllowingStateLoss()
        }
    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    fun ready(tag: String) {
        Log.i(TAG, "ready $tag")
        ok++
        if (ok == 2) {
            Log.i(TAG, "all ready")

            gestureDetector = GestureDetector(this, GestureListener(this))

            viewModel.groupModel.change.observe(this) { _ ->
                Log.i(TAG, "group changed")
                if (viewModel.groupModel.tvGroup.value != null) {
                    watch()
                    menuFragment.update()
                }
            }

            viewModel.channelsOk.observe(this) { it ->
                if (it) {
                    val prevGroup = viewModel.groupModel.positionValue
                    val tvModel = if (SP.channel > 0) {
                        val position = if (SP.channel < viewModel.listModel.size) {
                            SP.channel - 1
                        } else {
                            SP.channel = 0
                            0
                        }
                        Log.i(TAG, "play default channel")
                        viewModel.groupModel.getPosition(position)
                    } else {
                        Log.i(TAG, "play last channel")
                        viewModel.groupModel.getCurrent()
                    }
                    viewModel.groupModel.setPositionPlaying()
                    viewModel.groupModel.getCurrentList()
                        ?.let {
                            Log.i(TAG, "current group ${it.getName()}")
                            it.setPositionPlaying()
                        }
                    tvModel?.setReady()

                    val currentGroup = viewModel.groupModel.positionValue
                    if (currentGroup != prevGroup) {
                        Log.i(TAG, "group change")
                        menuFragment.updateList(currentGroup)
                    }

                    viewModel.groupModel.isInLikeMode =
                        SP.defaultLike && viewModel.groupModel.positionValue == 0

                    // 检查每日首次启动，自动更新 EPG
                    viewModel.checkAndUpdateEPGOnFirstStartOfDay()
                }
            }

            Utils.isp.observe(this) {
                val id = when (it) {
                    else -> 0
                }

                if (id == 0) {
                    return@observe
                }

                resources.openRawResource(id).bufferedReader()
                    .use { i ->
                        val channels = i.readText()
                        if (channels.isNotEmpty()) {
                            viewModel.tryStr2Channels(channels, null, "")
                        } else {
                            Log.w(TAG, "$it is empty")
                        }
                    }
            }

            server = SimpleServer(this, viewModel)

            viewModel.updateConfig()
        }
    }

    private fun watch() {
        viewModel.listModel.forEach { tvModel ->
            tvModel.errInfo.observe(this) { _ ->
                if (tvModel.errInfo.value != null) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        Log.i(TAG, "${tvModel.tv.title} playing")
                        hideFragment(errorFragment)
                        showFragment(playerFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(playerFragment)
                        errorFragment.setMsg(tvModel.errInfo.value.toString())
                        showFragment(errorFragment)
                    }
                }
            }

            tvModel.ready.observe(this) { _ ->
                if (tvModel.ready.value != null) {
                    Log.i(TAG, "${tvModel.tv.title} ready to play")
                    hideFragment(errorFragment)
                    showFragment(loadingFragment)
                    playerFragment.play(tvModel)
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            tvModel.like.observe(this) { _ ->
                if (tvModel.like.value != null && tvModel.tv.id != -1) {
                    val liked = tvModel.like.value as Boolean
                    if (liked) {
                        viewModel.groupModel.getFavoritesList()?.replaceTVModel(tvModel)
                    } else {
                        viewModel.groupModel.getFavoritesList()
                            ?.removeTVModel(tvModel.tv.id)
                    }
                    SP.setLike(tvModel.tv.id, liked)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth = windowManager.defaultDisplay.width
        private var screenHeight = windowManager.defaultDisplay.height
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }

        override fun onDown(e: MotionEvent): Boolean {
            playerFragment.hideVolumeNow()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showFragment(menuFragment)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            showProgram()
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor = 0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta = ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor = 0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta = ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            var newVolume = (currentVolume + deltaVolume).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            playerFragment.setVolumeMax(maxVolume * 100)
            playerFragment.setVolume(newVolume * 100, true)
            playerFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness
            brightness = (brightness + deltaBrightness).coerceIn(0.1f, 0.9f)
            val attributes = window.attributes.apply { screenBrightness = brightness }
            window.attributes = attributes
            playerFragment.setVolumeMax(100)
            playerFragment.setVolume((brightness * 100).toInt())
            playerFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = viewModel.groupModel.getCurrent()!!
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    fun play(position: Int): Boolean {
        return if (position > -1 && position < viewModel.groupModel.getAllList()!!.size()) {
            val prevGroup = viewModel.groupModel.positionValue
            val tvModel = viewModel.groupModel.getPosition(position)
            tvModel?.setReady()
            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()
            val currentGroup = viewModel.groupModel.positionValue
            if (currentGroup != prevGroup) {
                menuFragment.updateList(currentGroup)
            }
            true
        } else {
            R.string.channel_not_exist.showToast()
            false
        }
    }

    /**
     * 播放回放 URL
     * 从 ProgramFragment 点击节目单中的回放节目时调用
     */
    fun playCatchup(catchupUrl: String) {
        Log.i(TAG, "playCatchup: $catchupUrl")
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        
        // 保存原始 URI 和回放状态
        if (!tvModel.isInCatchupMode) {
            tvModel.catchupOriginalUris = tvModel.tv.uris
        }
        tvModel.isInCatchupMode = true
        
        // 只使用回放 URL
        tvModel.tv = tvModel.tv.copy(uris = listOf(catchupUrl))
        tvModel.setReady()
        
        R.string.catchup_playing.showToast()
    }

    /**
     * 退出回放模式，恢复直播流
     */
    fun exitCatchupMode() {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        if (!tvModel.isInCatchupMode) return
        
        // 恢复直播模式的循环播放
        playerFragment.resumeLiveMode()
        
        tvModel.catchupOriginalUris?.let { originalUris ->
            tvModel.tv = tvModel.tv.copy(uris = originalUris)
            tvModel.isInCatchupMode = false
            tvModel.catchupOriginalUris = null
            tvModel.setReady()
            Log.i(TAG, "exitCatchupMode: restored to live stream")
        }
    }

    /**
     * 开始时移播放（直播快退）
     * @param seekTime 时移开始时间（Unix 时间戳，秒）
     */
    fun startTimeShift(seekTime: Long) {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val catchupSource = tvModel.tv.catchupSource
        
        if (catchupSource.isNullOrEmpty()) {
            Log.w(TAG, "startTimeShift: 不支持时移")
            return
        }
        
        Log.i(TAG, "startTimeShift: seekTime=$seekTime, catchupSource=$catchupSource")
        
        // 注意：不设置 isInCatchupMode，这是时移模式，不是回放模式
        // 时移状态由 PlaybackControlFragment 的 rewindSeconds 控制
        
        // 保存原始直播 URL（如果还没保存）
        if (tvModel.catchupOriginalUris == null) {
            tvModel.catchupOriginalUris = tvModel.tv.uris
        }
        
        // 构建时移 URL
        val timeShiftUrl = buildCatchupUrl(tvModel.tv.uris.firstOrNull() ?: "", catchupSource, seekTime)
        Log.i(TAG, "时移URL: $timeShiftUrl")
        
        // 使用 playCatchup 直接播放时移 URL
        playerFragment.playCatchup(tvModel, timeShiftUrl)
    }

    fun prev() {
        exitCatchupMode()
        playbackControlFragment.resetTimeShift()
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null) {
                viewModel.groupModel.getPrev(true)
            } else {
                viewModel.groupModel.getPrev()
            }
        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()
        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    fun next() {
        exitCatchupMode()
        playbackControlFragment.resetTimeShift()
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null) {
                viewModel.groupModel.getNext(true)
            } else {
                viewModel.groupModel.getNext()
            }
        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()
        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) return
        if (!fragment.isAdded) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, fragment)
                .commitAllowingStateLoss()
            return
        }
        if (!fragment.isHidden) return
        supportFragmentManager.beginTransaction()
            .show(fragment)
            .commitAllowingStateLoss()
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) return
        if (!fragment.isAdded || fragment.isHidden) return
        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(menuFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun switchSoftDecode() {
        if (!playerFragment.isAdded || playerFragment.isHidden) return
        playerFragment.updatePlayer()
    }

    fun settingActive() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    private val hideSetting = Runnable {
        hideFragment(settingFragment)
        showTimeFragment()
    }

    fun showTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun showChannel(channel: Int) {
        if (!menuFragment.isHidden) return
        if (settingFragment.isVisible) return
        channelFragment.show(channel)
    }

    private fun channelUp() {
        if (programFragment.isAdded && !programFragment.isHidden) return
        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) { next(); return }
            prev()
        }
    }

    private fun channelDown() {
        if (programFragment.isAdded && !programFragment.isHidden) return
        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) { prev(); return }
            next()
        }
    }

    private fun back() {
        // 播放控制界面显示中 → 直接退出播控界面
        if (playbackControlFragment.isAdded && !playbackControlFragment.isHidden) {
            hidePlaybackControl()
            return
        }
        if (menuFragment.isAdded && !menuFragment.isHidden) { hideFragment(menuFragment); return }
        if (programFragment.isAdded && !programFragment.isHidden) { hideFragment(programFragment); return }
        if (settingFragment.isAdded && !settingFragment.isHidden) { hideFragment(settingFragment); showTimeFragment(); return }
        if (channelFragment.isAdded && channelFragment.isVisible) { channelFragment.hideSelf(); return }

        // 无播控界面时，根据当前模式处理返回键
        if (playbackControlFragment.isTimeShift()) {
            // 时移模式：提示退出时移，回退到直播
            showTimeShiftExitConfirm()
        } else if (playbackControlFragment.isCatchup()) {
            // 回放模式：提示退出回放，回退到直播
            showCatchupExitConfirm()
        } else {
            // 直播模式：双击退出 APK
            if (doubleBackToExitPressedOnce) { super.onBackPressed(); return }
            doubleBackToExitPressedOnce = true
            R.string.press_again_to_exit.showToast()
            Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
        }
    }

    /**
     * 显示退出回放确认对话框
     */
    private fun showCatchupExitConfirm() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(R.string.exit_catchup)
        builder.setMessage(R.string.confirm_exit_catchup)
        builder.setPositiveButton(R.string.confirm) { _, _ ->
            exitCatchupMode()
            // 只有播控界面显示时才隐藏
            if (playbackControlFragment.isAdded && !playbackControlFragment.isHidden) {
                hidePlaybackControl()
            }
        }
        builder.setNegativeButton(R.string.cancel) { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    /**
     * 显示退出时移确认对话框
     */
    private fun showTimeShiftExitConfirm() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("退出时移")
        builder.setMessage("确定退出时移模式，返回直播？")
        builder.setPositiveButton(R.string.confirm) { _, _ ->
            exitTimeShiftMode()
        }
        builder.setNegativeButton(R.string.cancel) { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    /**
     * 退出时移模式，返回直播
     */
    private fun exitTimeShiftMode() {
        playbackControlFragment.resetTimeShift()
        val tvModel = viewModel.groupModel.getCurrent() ?: return

        // 恢复原始直播流 URL
        tvModel.catchupOriginalUris?.let { originalUris ->
            tvModel.tv = tvModel.tv.copy(uris = originalUris)
            tvModel.catchupOriginalUris = null
            tvModel.setReady()
            Log.i(TAG, "exitTimeShiftMode: restored to live stream")
        }
    }

    /**
     * 从直播模式切换到回放模式
     * @param seekTime 回看开始时间（Unix 时间戳，秒）
     * @param endTime 回看结束时间（Unix 时间戳，秒），如果为0则使用直播结束时间
     */
    fun startCatchupFromLive(seekTime: Long, endTime: Long = 0L) {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val catchupSource = tvModel.tv.catchupSource
        
        if (catchupSource.isEmpty()) {
            Log.w(TAG, "该频道不支持回看")
            R.string.catchup.showToast()
            return
        }
        
        Log.i(TAG, "从直播切换到回放: seekTime=$seekTime, endTime=$endTime")
        
        // 标记进入回放模式
        tvModel.isInCatchupMode = true
        
        // 构建回看 URL（带结束时间）
        val baseUrl = tvModel.tv.uris.firstOrNull() ?: ""
        val catchupUrl = buildCatchupUrlWithEnd(baseUrl, catchupSource, seekTime, endTime)
        Log.i(TAG, "回放URL: $catchupUrl")
        
        // 更新播放器
        playerFragment.playCatchup(tvModel, catchupUrl)
        
        // 更新播放控制界面
        playbackControlFragment.setTVModel(tvModel)
    }

    private fun buildCatchupUrl(baseUrl: String, catchupSource: String, beginTime: Long): String {
        // 使用 ProgramFragment 中的 buildCatchupUrl 逻辑
        // 简化版本：替换时间占位符
        var result = catchupSource
        val beginDate = Date(beginTime * 1000)
        val endTime = Date()
        
        // 格式化时间
        result = Regex("""\$\{(\([be]\))([^}]+)\}""").replace(result) { match ->
            val type = match.groupValues[1]
            val format = match.groupValues[2]
            val date = if (type == "(b)") beginDate else endTime
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                match.value
            }
        }
        
        // 替换 {start} 和 {end}
        result = result.replace("{start}", beginTime.toString())
        result = result.replace("{end}", (System.currentTimeMillis() / 1000).toString())
        
        // 拼接 URL
        return when {
            baseUrl.contains("?") && result.startsWith("?") -> baseUrl + "&" + result.substring(1)
            baseUrl.contains("?") -> baseUrl + "&" + result
            result.startsWith("?") -> baseUrl + result
            else -> baseUrl + "?" + result
        }
    }
    
    /**
     * 构建回看 URL（带结束时间）
     */
    private fun buildCatchupUrlWithEnd(baseUrl: String, catchupSource: String, beginTime: Long, endTime: Long): String {
        var result = catchupSource
        val beginDate = Date(beginTime * 1000)
        val endDate = Date(endTime * 1000)
        
        // 替换 ${(b)format} 和 ${(e)format}
        result = Regex("""\$\{(\([be]\))([^}]+)\}""").replace(result) { match ->
            val type = match.groupValues[1]
            val format = match.groupValues[2]
            val date = if (type == "(b)") beginDate else endDate
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                match.value
            }
        }
        
        // 替换 (b)format 和 (e)format
        result = Regex("""\(([be])\)([a-zA-Z0-9]+)""").replace(result) { match ->
            val type = match.groupValues[1]
            val format = match.groupValues[2]
            val date = if (type == "b") beginDate else endDate
            try {
                SimpleDateFormat(format, Locale.getDefault()).format(date)
            } catch (e: Exception) {
                match.value
            }
        }
        
        // 替换 {start} 和 {end}
        result = result.replace("{start}", beginTime.toString())
        result = result.replace("{end}", endTime.toString())
        
        // 替换 {timestamp}
        result = result.replace("{timestamp}", beginTime.toString())
        
        // 拼接 URL
        return when {
            baseUrl.contains("?") && result.startsWith("?") -> baseUrl + "&" + result.substring(1)
            baseUrl.contains("?") -> baseUrl + "&" + result
            result.startsWith("?") -> baseUrl + result
            else -> baseUrl + "?" + result
        }
    }

    private fun showSetting() {
        if (programFragment.isAdded && !programFragment.isHidden) return
        if (menuFragment.isAdded && !menuFragment.isHidden) return
        showFragment(settingFragment)
        settingActive()
    }

    private fun showProgram() {
        if (menuFragment.isAdded && !menuFragment.isHidden) return
        if (settingFragment.isAdded && !settingFragment.isHidden) return
        
        val tvModel = viewModel.groupModel.getCurrent()
        if (tvModel == null) {
            R.string.epg_is_empty.showToast()
            return
        }
        
        // 检查当前频道的 EPG 是否为空
        if (tvModel.epgValue.isEmpty()) {
            // 检查是否有全局 EPG 数据（任意频道有 EPG 则说明 EPG 已加载）
            val hasAnyEpg = viewModel.listModel.any { it.epgValue.isNotEmpty() }
            
            if (!hasAnyEpg) {
                // 没有 EPG 信息（全局 EPG 为空或未加载），直接更新
                Log.i(TAG, "EPG 未加载，立即更新")
                R.string.epg_updating.showToast()
                viewModel.updateEPG()
                return
            } else {
                // 有 EPG 数据但此频道没有，提示"此频道无EPG"
                Log.i(TAG, "频道 ${tvModel.tv.title} 无 EPG 信息")
                "${tvModel.tv.title} 暂无节目单".showToast()
            }
            return
        }
        
        // 回放模式：传入当前回放节目的时间范围，EPG 定位到该节目
        if (playbackControlFragment.isCatchup()) {
            val (beginTime, endTime) = playbackControlFragment.getCatchupTimeRange()
            programFragment.setExternalPlaybackInfo(beginTime, endTime)
            Log.i(TAG, "showProgram: 回放模式，传入回放时间 beginTime=$beginTime, endTime=$endTime")
        }

        showFragment(programFragment)
    }

    private fun hideProgram(): Boolean {
        if (!programFragment.isAdded || programFragment.isHidden) return false
        hideFragment(programFragment)
        return true
    }

    fun showWebViewPopup(url: String) {
        val binding = SettingsWebBinding.inflate(layoutInflater)
        val webView = binding.web
        webView.settings.javaScriptEnabled = true
        webView.isFocusableInTouchMode = true
        webView.isFocusable = true
        webView.loadUrl(url)
        val popupWindow = PopupWindow(
            binding.root,
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        popupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popupWindow.isFocusable = true
        popupWindow.isTouchable = true
        popupWindow.isClippingEnabled = false
        popupWindow.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)
        webView.requestFocus()
        binding.close.setOnClickListener { popupWindow.dismiss() }
    }

    fun onKey(keyCode: Int): Boolean {
        Log.d(TAG, "keyCode $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9 -> { showChannel(keyCode - 7); return true }
            KeyEvent.KEYCODE_ESCAPE -> { back(); return true }
            KeyEvent.KEYCODE_BACK -> { back(); return true }
            KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.KEYCODE_HELP, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_MENU -> { showSetting(); return true }
            KeyEvent.KEYCODE_ENTER -> {
                if (channelFragment.isAdded && channelFragment.isVisible) { channelFragment.playNow(); return true }
                showFragment(menuFragment)
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (channelFragment.isAdded && channelFragment.isVisible) { channelFragment.playNow(); return true }
                showFragment(menuFragment)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> channelUp()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> channelDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showProgram()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 播控刚显示后，第一次右键可能 pendingJustShown=true，需要重置
                pendingJustShown = false
                showPlaybackControl()
            }
        }
        return false
    }

    /**
     * 显示播放控制界面
     */
    private fun showPlaybackControl() {
        if (menuFragment.isAdded && !menuFragment.isHidden) return
        if (settingFragment.isAdded && !settingFragment.isHidden) return
        if (programFragment.isAdded && !programFragment.isHidden) return
        
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        
        playbackControlFragment.setPlayerFragment(playerFragment)
        playbackControlFragment.setTVModel(tvModel)
        
        showFragment(playbackControlFragment)
        playbackControlActive()
    }

    /**
     * 隐藏播放控制界面
     */
    private fun hidePlaybackControl() {
        pendingJustShown = false
        hideFragment(playbackControlFragment)
    }

    fun playbackControlActive() {
        handler.removeCallbacks(hidePlaybackControl)
        handler.postDelayed(hidePlaybackControl, delayHidePlaybackControl)
    }

    private val hidePlaybackControl = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!playbackControlFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(playbackControlFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 右键显示播控时，先标记"刚显示"，让第一次右键只显示界面不触发快进
        val wasHidden = playbackControlFragment.isAdded && playbackControlFragment.isHidden
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && wasHidden) {
            // 临时标记：onKey 会显示界面
            pendingJustShown = true
        }
        // 在播控界面时，传递完整 KeyEvent 给 PlaybackControlFragment 处理长按
        if (playbackControlFragment.isAdded && !playbackControlFragment.isHidden) {
            if (playbackControlFragment.handleKeyEvent(keyCode, event, pendingJustShown)) {
                pendingJustShown = false
                return true
            }
        }
        if (onKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private var pendingJustShown = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 在播控界面时，传递 keyUp 给 PlaybackControlFragment 检测长按松手
        if (playbackControlFragment.isAdded && !playbackControlFragment.isHidden) {
            if (playbackControlFragment.handleKeyUp(keyCode)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        isSafeToPerformFragmentTransactions = true
        showTimeFragment()

        // 处理从后台恢复时的新Intent（如从其他应用分享的链接）
        intent?.data?.let { uri ->
            Log.i(TAG, "onResume处理外部URI: $uri")
            viewModel.importFromUri(uri)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 处理新的Intent（singleTask模式下从外部启动时）
        intent?.data?.let { uri ->
            Log.i(TAG, "onNewIntent处理外部URI: $uri")
            viewModel.importFromUri(uri)
        }
    }

    override fun onPause() {
        super.onPause()
        isSafeToPerformFragmentTransactions = false
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    override fun attachBaseContext(base: Context) {
        try {
            // Only apply Traditional Chinese if the device is already set to a Chinese locale
            val systemLocale = base.resources.configuration.locales.get(0)
            val languageTag = systemLocale.language
            val shouldApplyChinese = languageTag == "zh"

            if (shouldApplyChinese) {
                val locale = Locale.TRADITIONAL_CHINESE
                val config = Configuration()
                config.setLocale(locale)
                super.attachBaseContext(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                        base.createConfigurationContext(config)
                    } else {
                        val resources = base.resources
                        resources.updateConfiguration(config, resources.displayMetrics)
                        base
                    }
                )
            } else {
                super.attachBaseContext(base)
            }
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}