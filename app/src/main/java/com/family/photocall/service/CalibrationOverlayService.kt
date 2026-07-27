package com.family.photocall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.family.photocall.R
import com.family.photocall.SettingsActivity
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.AutomationActions
import com.family.photocall.model.AutomationStepConfig
import com.family.photocall.model.CalibrationConfig
import com.family.photocall.model.PointConfig
import kotlin.math.abs

class CalibrationOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repo: ConfigRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var captureView: View? = null
    private var cancelCaptureButton: View? = null
    private val steps = mutableListOf<AutomationStepConfig>()
    private var index: Int = 0
    private var working: CalibrationConfig = CalibrationConfig()
    private var minimized: Boolean = false
    private var capturing: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = ConfigRepository(this)
        val config = repo.load()
        working = config.calibration
        steps.addAll(repo.getAutomationSteps(config))
        if (steps.isEmpty()) {
            Toast.makeText(this, "还没有配置点击步骤，请先添加步骤", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        createChannel()
        startAsForeground()
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("坐标校准中")
            .setContentText("可拖动提示框，避免挡住微信按钮")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun showPanel() {
        removePanelOnly()
        minimized = false
        val panel = LayoutInflater.from(this).inflate(R.layout.overlay_calibration_panel, null)
        panelView = panel

        val metrics = screenMetrics()
        // 接近全宽的大面板，默认贴底部，避开顶部搜索区；仍可上下拖动
        val sidePad = (12 * metrics.density).toInt()
        val params = WindowManager.LayoutParams(
            metrics.widthPixels - sidePad * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sidePad
            // 先放到靠下位置；真正高度测量后再微调，避免盖住搜索
            y = (metrics.heightPixels * 0.55f).toInt()
        }
        panelParams = params
        bindPanel(panel)
        // 只允许拖动条拖动，避免和按钮抢触摸
        enableDrag(panel.findViewById(R.id.dragHandle), panel, params)
        windowManager.addView(panel, params)

        // 面板布局完成后，若还偏高则往下推，保证默认不挡顶部
        panel.post {
            val h = panel.height
            if (h > 0) {
                val targetY = (metrics.heightPixels - h - (24 * metrics.density).toInt())
                    .coerceAtLeast((metrics.heightPixels * 0.45f).toInt())
                params.y = targetY
                try {
                    windowManager.updateViewLayout(panel, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun showBubble() {
        removeBubbleOnly()
        minimized = true
        // 收起后的小球也做大一点，平板上好点
        val size = (84 * resources.displayMetrics.density).toInt()
        val bubble = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_overlay_bubble)
            val tv = TextView(context).apply {
                text = "校准"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
            }
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        bubbleView = bubble
        val metrics = screenMetrics()
        val old = panelParams
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = old?.x ?: (metrics.widthPixels - size - 40)
            y = old?.y ?: (metrics.heightPixels / 3)
        }
        bubbleParams = params
        enableDrag(bubble, bubble, params)
        bubble.setOnClickListener {
            // 单击展开（和拖动区分）
            expandFromBubble()
        }
        // 用 touch 区分点击/拖动
        var moved = false
        var downX = 0f
        var downY = 0f
        bubble.setOnTouchListener { v, event ->
            val lp = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    downX = event.rawX
                    downY = event.rawY
                    dragStartX = lp.x
                    dragStartY = lp.y
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = (dragStartX + dx).toInt()
                    lp.y = (dragStartY + dy).toInt()
                    clampParams(lp, v.width.coerceAtLeast(size), v.height.coerceAtLeast(size))
                    try {
                        windowManager.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        expandFromBubble()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(bubble, params)
    }

    private fun expandFromBubble() {
        val bx = bubbleParams?.x
        val by = bubbleParams?.y
        removeBubbleOnly()
        showPanel()
        if (bx != null && by != null) {
            panelParams?.let { lp ->
                lp.x = bx
                lp.y = by
                panelView?.let { v ->
                    try {
                        windowManager.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        Toast.makeText(this, "已展开校准面板，可拖到不挡按钮的位置", Toast.LENGTH_SHORT).show()
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f

    private fun enableDrag(handle: View, target: View, params: WindowManager.LayoutParams) {
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY
                    params.x = (dragStartX + dx).toInt()
                    params.y = (dragStartY + dy).toInt()
                    clampParams(params, target.width.coerceAtLeast(100), target.height.coerceAtLeast(80))
                    try {
                        windowManager.updateViewLayout(target, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampParams(params: WindowManager.LayoutParams, width: Int, height: Int) {
        val metrics = screenMetrics()
        val maxX = (metrics.widthPixels - width / 3).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - height / 3).coerceAtLeast(0)
        params.x = params.x.coerceIn(-width / 3, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun bindPanel(panel: View) {
        val title = panel.findViewById<TextView>(R.id.tvStepTitle)
        val instruction = panel.findViewById<TextView>(R.id.tvStepInstruction)
        val progress = panel.findViewById<TextView>(R.id.tvProgress)
        val btnCapture = panel.findViewById<Button>(R.id.btnStartCapture)
        val btnSkip = panel.findViewById<Button>(R.id.btnSkip)
        val btnPrev = panel.findViewById<Button>(R.id.btnPrev)
        val btnSaveExit = panel.findViewById<Button>(R.id.btnSaveExit)
        val btnOpenWechat = panel.findViewById<Button>(R.id.btnOpenWechat)
        val btnMinimize = panel.findViewById<Button>(R.id.btnMinimize)

        val step = steps[index]
        progress.text = "步骤 ${index + 1}/${steps.size}  ·  可拖动，勿挡目标按钮"
        title.text = step.name
        val existing = step.point
        instruction.text = buildString {
            append(
                when (step.action) {
                    AutomationActions.OPEN_WECHAT -> "执行时打开微信并等待页面稳定。此步骤不需要校准坐标。"
                    AutomationActions.COPY_SEARCH -> "执行时把当前联系人搜索词写入剪贴板。此步骤不需要校准坐标。"
                    else -> "请打开对应页面，点击“开始点选”，再点击“${step.name}”实际所在的位置。"
                }
            )
            if (existing.x > 0 && existing.y > 0) {
                append("\n当前已记录: (${existing.x}, ${existing.y})")
            }
            if (step.skipInDryRun) append("\n演示模式：跳过此步骤")
        }

        btnMinimize.setOnClickListener {
            // 收起成小球，方便操作微信
            val x = panelParams?.x
            val y = panelParams?.y
            removePanelOnly()
            showBubble()
            if (x != null && y != null) {
                bubbleParams?.let { lp ->
                    lp.x = x
                    lp.y = y
                    bubbleView?.let { v ->
                        try {
                            windowManager.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            Toast.makeText(this, "已收起。点蓝色小球可重新展开", Toast.LENGTH_SHORT).show()
        }

        val needsCoordinate = step.needsCoordinate()
        btnCapture.isEnabled = needsCoordinate
        btnCapture.text = if (needsCoordinate) "开始点选" else "无需校准坐标"
        btnCapture.setOnClickListener {
            if (needsCoordinate) enterCaptureMode()
        }

        // 验证：画红圈 + 执行真实点击，让你确认位置和效果是否正确
        val btnVerify = panel.findViewById<Button>(R.id.btnVerify)
        btnVerify.setOnClickListener {
            val pt = step.point
            if (pt.x <= 0 || pt.y <= 0) {
                Toast.makeText(this, "还没标过这个点，请先点选", Toast.LENGTH_SHORT).show()
            } else {
                // 先显示红圈，400ms 后再点击（让你看到圈的位置）
                showVerifyMarker(pt.x, pt.y)
                panelView?.visibility = View.GONE  // 临时隐藏面板，避免挡住反馈
                mainHandler.postDelayed({
                    performVerifyTap(pt.x, pt.y)
                    mainHandler.postDelayed({
                        panelView?.visibility = View.VISIBLE
                    }, 600)
                }, 400)
            }
        }
        btnSkip.setOnClickListener { nextStep() }
        btnPrev.setOnClickListener {
            if (index > 0) {
                index--
                bindPanel(panel)
            }
        }
        btnSaveExit.setOnClickListener {
            persist()
            Toast.makeText(this, "已保存校准配置", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        btnOpenWechat.setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } else {
                Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show()
            }
        }
        val hasCoord = existing.x > 0 && existing.y > 0
        btnVerify.isEnabled = needsCoordinate && hasCoord
        btnSkip.text = if (needsCoordinate && !hasCoord) "下一步(暂不校准)" else "下一步"
    }

    private fun enterCaptureMode() {
        if (capturing) return
        capturing = true
        try {
            // 点选时彻底隐藏面板和小球，避免挡住目标
            panelView?.visibility = View.GONE
            bubbleView?.visibility = View.GONE

            // 全透明捕获层：不再整屏染色
            val capture = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        // 千万不要在 touch 回调里直接 removeView，容易把悬浮窗服务弄挂
                        mainHandler.post { onPointCaptured(x, y) }
                        true
                    } else {
                        event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_MOVE
                    }
                }
            }
            captureView = capture
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(capture, params)
            showCancelCaptureButton()
            Toast.makeText(this, "请直接点目标位置（屏幕无遮罩）。点错可按“取消点选”", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e(TAG, "enterCaptureMode failed", t)
            capturing = false
            restorePanelSafely()
            Toast.makeText(this, "进入点选失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCancelCaptureButton() {
        removeCancelCaptureButton()
        val btn = Button(this).apply {
            text = "取消点选"
            textSize = 16f
            setOnClickListener {
                mainHandler.post { cancelCaptureMode() }
            }
        }
        cancelCaptureButton = btn
        val metrics = screenMetrics()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * metrics.density).toInt()
            y = (120 * metrics.density).toInt()
        }
        try {
            windowManager.addView(btn, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "showCancelCaptureButton failed", t)
        }
    }

    private fun removeCancelCaptureButton() {
        cancelCaptureButton?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        cancelCaptureButton = null
    }

    private fun cancelCaptureMode() {
        capturing = false
        removeCaptureLayer()
        removeCancelCaptureButton()
        restorePanelSafely()
        Toast.makeText(this, "已取消点选", Toast.LENGTH_SHORT).show()
    }

    private fun removeCaptureLayer() {
        captureView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        captureView = null
    }

    private fun onPointCaptured(x: Int, y: Int) {
        if (!capturing) return
        capturing = false
        try {
            removeCaptureLayer()
            removeCancelCaptureButton()

            val metrics = screenMetrics()
            val point = PointConfig(
                x = x,
                y = y,
                xPercent = if (metrics.widthPixels > 0) x.toFloat() / metrics.widthPixels else 0f,
                yPercent = if (metrics.heightPixels > 0) y.toFloat() / metrics.heightPixels else 0f
            )
            val step = steps.getOrNull(index) ?: return
            steps[index] = steps[index].copy(point = point)
            working = working.copy(
                deviceModel = Build.MODEL.orEmpty(),
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                updatedAt = System.currentTimeMillis()
            )
            persist()
            Toast.makeText(this, "${step.name} 已记录: ($x, $y)", Toast.LENGTH_SHORT).show()

            // 先恢复面板，再进入下一步，避免“点完就消失”
            if (index < steps.lastIndex) {
                index++
            }
            restorePanelSafely()
            if (index >= steps.lastIndex && steps[index].point.x > 0) {
                // 最后一步也记完了，不自动关服务，让用户自己点“保存退出”
                panelView?.let { bindPanel(it) }
                Toast.makeText(this, "全部点位已记录，可点“保存退出”", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onPointCaptured failed", t)
            restorePanelSafely()
            Toast.makeText(this, "保存坐标失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restorePanelSafely() {
        try {
            // 优先重新创建大面板，最稳；小球模式也先展开，避免“消失”
            minimized = false
            if (panelView == null) {
                showPanel()
            } else {
                panelView?.visibility = View.VISIBLE
                panelView?.let { bindPanel(it) }
            }
            // 确保小球不残留挡视线
            bubbleView?.visibility = View.GONE
        } catch (t: Throwable) {
            Log.e(TAG, "restorePanelSafely failed", t)
            try {
                showPanel()
            } catch (t2: Throwable) {
                Log.e(TAG, "showPanel retry failed", t2)
            }
        }
    }

    private fun performVerifyTap(px: Int, py: Int) {
        // Cross-process: CalibrationOverlayService runs in main process,
        // PhotoCallAccessibilityService runs in :a11y process.
        // instance is always null here — must use broadcast instead.
        com.family.photocall.service.PhotoCallAccessibilityService.requestVerifyTap(this, px, py)
        Log.i(TAG, "verify tap broadcast sent ($px,$py)")
    }

    private fun nextStep() {
        if (index < steps.lastIndex) {
            index++
            restorePanelSafely()
        } else {
            persist()
            Toast.makeText(this, "已到最后一步，可点保存退出", Toast.LENGTH_SHORT).show()
            restorePanelSafely()
        }
    }

    /**
     * 在屏幕指定坐标画一个红色圆圈 2 秒，让用户确认位置是否正确。
     * 不做真实点击（避免误操作），仅显示视觉标记。
     */
    private fun showVerifyMarker(px: Int, py: Int) {
        val metrics = screenMetrics()
        val radiusDp = 48
        val size = (radiusDp * metrics.density * 2).toInt()

        val marker = object : android.view.View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = width / 2f - 8f
                val ring = android.graphics.Paint().apply {
                    color = android.graphics.Color.RED
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 10f
                    isAntiAlias = true
                }
                val fill = android.graphics.Paint().apply {
                    color = 0x55FF0000  // translucent red
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(cx, cy, r, fill)
                canvas.drawCircle(cx, cy, r, ring)
                // crosshair
                ring.strokeWidth = 4f
                canvas.drawLine(cx, cy - r - 20, cx, cy + r + 20, ring)
                canvas.drawLine(cx - r - 20, cy, cx + r + 20, cy, ring)
            }
        }
        marker.setWillNotDraw(false)

        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (px - size / 2).coerceIn(-size / 4, metrics.widthPixels - size * 3 / 4)
            y = (py - size / 2).coerceIn(0, metrics.heightPixels - size)
        }

        try {
            windowManager.addView(marker, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "showVerifyMarker failed", t)
            return
        }

        // Auto-remove after 2 seconds
        mainHandler.postDelayed({
            try {
                windowManager.removeView(marker)
            } catch (_: Exception) {
            }
        }, 2000)
    }

    private fun persist() {
        try {
            repo.updateCalibration(working)
            repo.updateAutomationSteps(steps.toList())
        } catch (t: Throwable) {
            Log.e(TAG, "persist failed", t)
        }
    }

    private fun removePanelOnly() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
    }

    private fun removeBubbleOnly() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
    }

    private fun removeViews() {
        capturing = false
        removePanelOnly()
        removeBubbleOnly()
        removeCaptureLayer()
        removeCancelCaptureButton()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "坐标校准", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "PhotoCallCalib"
        const val CHANNEL_ID = "calibration_overlay"
        const val NOTI_ID = 42
        const val ACTION_STOP = "com.family.photocall.action.STOP_CALIBRATION"

        fun start(context: Context) {
            val intent = Intent(context, CalibrationOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CalibrationOverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private fun AutomationStepConfig.needsCoordinate(): Boolean {
        return action != AutomationActions.OPEN_WECHAT && action != AutomationActions.COPY_SEARCH
    }

}
