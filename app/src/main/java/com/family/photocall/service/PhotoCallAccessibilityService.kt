package com.family.photocall.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.AutomationActions
import com.family.photocall.model.AutomationStepConfig
import com.family.photocall.model.AppConfig
import com.family.photocall.model.ContactConfig
import com.family.photocall.model.DelayConfig
import com.family.photocall.model.PointConfig
import com.family.photocall.model.PointsConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PhotoCallAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private lateinit var repo: ConfigRepository
    private var receiverRegistered = false
    private var wm: WindowManager? = null
    @Volatile
    private var lastEventPackage: String? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_CALL -> {
                    val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
                    val forceDry = if (intent.hasExtra(EXTRA_FORCE_DRY_RUN))
                        intent.getBooleanExtra(EXTRA_FORCE_DRY_RUN, true) else null
                    Log.i(TAG, "received start call contactId=$contactId")
                    startCall(contactId, forceDry)
                }
                ACTION_VERIFY_TAP -> {
                    val x = intent.getIntExtra(EXTRA_TAP_X, -1)
                    val y = intent.getIntExtra(EXTRA_TAP_Y, -1)
                    if (x > 0 && y > 0) {
                        Log.i(TAG, "verify tap at ($x,$y)")
                        val path = android.graphics.Path().apply {
                            moveTo(x.toFloat(), y.toFloat())
                            lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
                        }
                        val stroke = android.accessibilityservice.GestureDescription
                            .StrokeDescription(path, 0, 80)
                        val gesture = android.accessibilityservice.GestureDescription
                            .Builder().addStroke(stroke).build()
                        dispatchGesture(gesture, null, null)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repo = ConfigRepository(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registerCommandReceiver()
        Log.i(TAG, "Accessibility service connected in process=${android.os.Process.myPid()}")
        toast("无障碍服务已连接（独立进程）")
    }

    private fun registerCommandReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_START_CALL).also {
            it.addAction(ACTION_VERIFY_TAP)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(commandReceiver, filter)
            }
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.e(TAG, "registerReceiver failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != lastEventPackage) {
            lastEventPackage = packageName
            Log.d(TAG, "accessibility event package=$packageName")
        }
    }

    override fun onInterrupt() {
        // 不要把 running 直接清掉导致半截流程状态错乱；仅记录
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        try {
            if (receiverRegistered) unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun isBusy(): Boolean = running.get()

    fun startCall(contactId: String, forceDryRun: Boolean? = null) {
        if (!running.compareAndSet(false, true)) {
            toast("正在执行中，请稍候")
            return
        }
        val config = repo.load()
        val contact = config.contacts.firstOrNull { it.id == contactId && it.enabled }
        if (contact == null) {
            running.set(false)
            toast("未找到联系人")
            return
        }
        if (!repo.isCalibrationReady(config)) {
            running.set(false)
            val missing = repo.missingCalibrationKeys(config).joinToString("、")
            toast("校准未完成，缺少：$missing")
            return
        }
        val dryRun = forceDryRun ?: config.dryRun
        Log.i(
            TAG,
            "startCall contact=${contact.id} forceDryRun=$forceDryRun " +
                "configDryRun=${config.dryRun} effectiveDryRun=$dryRun"
        )
        toast(if (dryRun) "开始演练：${contact.displayName}" else "正在呼叫：${contact.displayName}")
        Thread {
            try {
                runFlow(config, contact, dryRun)
            } catch (t: Throwable) {
                Log.e(TAG, "flow failed", t)
                toast("执行失败：${t.message ?: "unknown"}")
            } finally {
                CallKeepAliveService.stop(this)
                running.set(false)
            }
        }.start()
    }

    private fun runFlow(config: AppConfig, contact: ContactConfig, dryRun: Boolean) {
        val steps = repo.getAutomationSteps(config).filter { it.enabled }
        if (steps.isEmpty()) {
            throw IllegalStateException("没有启用任何点击步骤，请先配置点击流程")
        }

        // The custom list may be reordered, but the search term is always prepared
        // before opening WeChat or tapping the search UI.
        step("开始搜索前复制搜索词 ${contact.searchName}")
        setClipboard(contact.searchName)

        var skippedDryRunStep = false
        for (stepConfig in steps) {
            val latestDryRun = repo.load().dryRun
            val isConfirmationStep = stepConfig.id.equals("video_call_confirm", ignoreCase = true) ||
                stepConfig.name.trim() in setOf("确认视频通话", "确认通话", "视频通话确认")
            if ((stepConfig.skipInDryRun || isConfirmationStep) && (dryRun || latestDryRun)) {
                skippedDryRunStep = true
                Log.i(
                    TAG,
                    "dry run: skip step id=${stepConfig.id} name=${stepConfig.name} " +
                        "confirmation=$isConfirmationStep configured=${stepConfig.skipInDryRun}"
                )
                continue
            }

            step("${stepConfig.name} ${stepConfig.point.label()}")
            when (stepConfig.action) {
                AutomationActions.OPEN_WECHAT -> {
                    bringWeChatToFront(stepConfig.delayMs)
                    sleep(250)
                }
                AutomationActions.COPY_SEARCH -> setClipboard(contact.searchName)
                AutomationActions.TAP -> {
                    if (!stepConfig.point.isUsable()) {
                        throw IllegalStateException("步骤“${stepConfig.name}”还没有校准坐标")
                    }
                    tapWithRetry(stepConfig.point, stepConfig.name, retries = 4)
                    sleep(stepConfig.delayMs.coerceAtLeast(250))
                }
                else -> {
                    if (!stepConfig.point.isUsable()) {
                        throw IllegalStateException("步骤“${stepConfig.name}”还没有校准坐标")
                    }
                    tapWithRetry(stepConfig.point, stepConfig.name, retries = 4)
                    sleep(stepConfig.delayMs.coerceAtLeast(250))
                }
            }
        }

        if (skippedDryRunStep) {
            toast("演示完成：已跳过标记为“演示时跳过”的步骤。")
        } else if (dryRun || repo.load().dryRun) {
            toast("演示完成：流程已执行，没有标记需要跳过的步骤。")
        } else {
            toast("自定义点击流程执行完成")
        }
    }

    private fun bringWeChatToFront(minWaitMs: Long) {
        openWeChat()
        // ColorOS may hide the accessibility window tree even though WeChat is
        // already visible. Do one launch only; repeated launches cause visible
        // startup animations and can reorder the task back to the launcher.
        val ok = waitForWeChatForeground(1600)

        // 微信常把 a11y 树藏空，检测可能一直失败；若用户已看到微信打开，则降级继续
        if (!ok) {
            Log.w(TAG, "still cannot confirm wechat foreground via a11y; continue after wait")
            toast("已尝试打开微信，继续自动点击…")
        } else {
            Log.i(TAG, "wechat foreground confirmed")
        }
        // The foreground wait above already absorbs launch variance. Keep only
        // a short, configurable render settling delay before the first tap.
        sleep(minWaitMs.coerceIn(300L, 1200L))
    }

    private fun waitForWeChatForeground(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(100L)
        while (System.currentTimeMillis() < deadline) {
            if (isWeChatForeground()) return true
            sleep(100)
        }
        return isWeChatForeground()
    }

    private fun openWeChat() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(WECHAT_PKG)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                startActivity(launch)
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "launch wechat failed", t)
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(WECHAT_PKG, "$WECHAT_PKG.ui.LauncherUI")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(intent)
    }

    /**
     * 微信 8.x 常返回空节点树，rootInActiveWindow.packageName 会是 null。
     * 改用 windows 列表 + root 双重判断。
     */
    private fun isWeChatForeground(): Boolean {
        // On some ColorOS/Android 11 builds the active window tree is empty,
        // while accessibility events still report the foreground package.
        if (lastEventPackage == WECHAT_PKG) return true

        // 1) windows API（比 root 包名更可靠）
        try {
            val wins = windows
            if (wins != null) {
                for (w in wins) {
                    try {
                        val pkg = w.root?.packageName?.toString()
                        if (pkg == WECHAT_PKG) return true
                        // 有的窗口 title 含 WeChat/微信
                        val title = w.title?.toString().orEmpty()
                        if (title.contains("微信") || title.contains("WeChat", true)) return true
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "windows check failed", t)
        }

        // 2) root package fallback
        val root = rootInActiveWindow
        try {
            val name = root?.packageName?.toString()
            if (name == WECHAT_PKG) return true
        } catch (_: Exception) {
        } finally {
            try {
                root?.recycle()
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pkg == WECHAT_PKG && isWeChatForeground()) return true
            if (pkg != WECHAT_PKG) {
                val root = rootInActiveWindow
                val name = try {
                    root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                } finally {
                    try {
                        root?.recycle()
                    } catch (_: Exception) {
                    }
                }
                if (name == pkg) return true
            }
            sleep(250)
        }
        return false
    }

    /**
     * 用户指定方案（最稳，适配输入法剪贴板）：
     * 1) 后台把联系人名字写入系统剪贴板
     * 2) 可选：点输入法总菜单/工具栏
     * 3) 点输入法"剪贴板"入口
     * 4) 点剪贴板面板第一条
     */
    private fun pasteByImeClipboard(
        name: String,
        points: com.family.photocall.model.PointsConfig,
        delays: com.family.photocall.model.DelayConfig
    ) {
        if (!points.imeClipboard.isUsable() || !points.imeClipboardItem1.isUsable()) {
            throw IllegalStateException(
                "请先校准：输入法剪贴板按钮 + 剪贴板第一条。"
            )
        }

        // 重新写一次，确保输入法剪贴板面板打开时第一条就是当前联系人。
        setClipboard(name)
        sleep(300)

        // 有些输入法剪贴板藏在总菜单里
        if (points.imeMenu.isUsable()) {
            step("点击输入法总菜单 ${points.imeMenu.label()}")
            tapWithRetry(points.imeMenu, "输入法总菜单", retries = 3)
            sleep(delays.afterImeMenuMs.coerceAtLeast(500))
            // 安全检查：如果点完总菜单后已经离开了微信搜索页（比如误点到网页搜索/其他功能），
            // 立刻中止，避免继续在错误页面上乱点
            if (!isWeChatForeground()) {
                throw IllegalStateException(
                    "点击“输入法总菜单”后离开了微信搜索页。" +
                        "这个坐标(${points.imeMenu.label()})可能标错了，请重新校准或直接跳过这一步。"
                )
            }
        }

        step("点击输入法剪贴板 ${points.imeClipboard.label()}")
        tapWithRetry(points.imeClipboard, "输入法剪贴板", retries = 4)
        sleep(delays.afterImeClipboardMs.coerceAtLeast(700))

        step("点击剪贴板第一条 ${points.imeClipboardItem1.label()}")
        tapWithRetry(points.imeClipboardItem1, "剪贴板第一条", retries = 4)
        sleep(delays.afterImeItemMs.coerceAtLeast(800))

        // Do NOT press BACK here — in WeChat's search page, BACK navigates to web search,
        // not keyboard dismiss. The keyboard will close naturally when we tap the result row.
        Log.i(TAG, "IME clipboard paste finished for: $name")
    }

    private fun setClipboard(text: String) {
        val latch = CountDownLatch(1)
        val copied = AtomicBoolean(false)
        mainHandler.post {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("photocall", text))
                val actual = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                copied.set(actual == text)
                Log.i(TAG, "clipboard set, verified=${copied.get()}, length=${text.length}")
            } catch (t: Throwable) {
                Log.e(TAG, "clipboard set failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        if (!copied.get()) {
            throw IllegalStateException("无法把微信搜索词复制到剪贴板，请检查系统剪贴板权限")
        }
    }

    private fun trySetTextEverywhere(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            findFocusedEditable(root)?.let { node ->
                if (setTextOnNode(node, text)) return true
            }
            findFirstEditable(root)?.let { node ->
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                }
                if (setTextOnNode(node, text)) return true
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun tryPasteActionOnEditable(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val node = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun textProbablyPresent(expected: String): Boolean {
        if (expected.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        try {
            return nodeTreeContains(root, expected)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun nodeTreeContains(node: AccessibilityNodeInfo, expected: String): Boolean {
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        if (t.contains(expected) || d.contains(expected)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = nodeTreeContains(child, expected)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun clickNodeByTexts(texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = findNodeByTexts(root, texts) ?: return false
            // 优先系统 click
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            // 退化到手势点中心
            val rect = android.graphics.Rect()
            target.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val p = PointConfig(x = rect.centerX(), y = rect.centerY())
                return tap(p)
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun findNodeByTexts(
        node: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        if (texts.any { t.equals(it, true) || d.equals(it, true) || t.contains(it) || d.contains(it) }) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTexts(child, texts)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun tapWithRetry(
        point: PointConfig,
        name: String,
        retries: Int = 3,
        required: Boolean = true
    ) {
        if (!point.isUsable()) {
            if (required) throw IllegalStateException("$name 坐标未配置")
            return
        }
        // Show marker BEFORE tapping so user sees where the click will land
        showTapMarker(point.x, point.y, name)
        sleep(350)  // brief pause so user can see the circle
        var lastError = "unknown"
        repeat(retries) { attempt ->
            Log.i(TAG, "tap $name attempt=${attempt + 1} ${point.label()}")
            val ok = when (attempt % 3) {
                0 -> tap(point, durationMs = 80L)
                1 -> tap(point, durationMs = 140L, drift = 2f)
                else -> tap(point, durationMs = 50L, drift = 1f)
            }
            if (ok) {
                sleep(180)
                return
            }
            lastError = "手势被取消/未完成"
            sleep(450)
        }
        if (required) {
            throw IllegalStateException(
                "$name 点击失败${point.label()}：$lastError。" +
                    "请确认无障碍仍开启，且坐标没点到空白区域。"
            )
        }
    }

    private fun longPressWithRetry(point: PointConfig, name: String, retries: Int = 2) {
        if (!point.isUsable()) return
        repeat(retries) { attempt ->
            Log.i(TAG, "longPress $name attempt=${attempt + 1} ${point.label()}")
            if (longPress(point)) return
            sleep(300)
        }
    }

    /**
     * 在屏幕指定坐标画红色圆圈+标签，持续 1.2 秒后消失。
     * 在每次真实点击前调用，让用户看清楚点击落点。
     */
    private fun showTapMarker(px: Int, py: Int, label: String) {
        val wmRef = wm ?: return
        val density = resources.displayMetrics.density
        val size = (72 * density).toInt()
        val marker = object : View(this) {
            private val ringPaint = Paint().apply {
                color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
            }
            private val fillPaint = Paint().apply {
                color = 0x44FF0000; style = Paint.Style.FILL; isAntiAlias = true
            }
            private val textPaint = Paint().apply {
                color = Color.WHITE; textSize = 28f; isAntiAlias = true
                setShadowLayer(4f, 1f, 1f, Color.BLACK)
            }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 6f
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.strokeWidth = 4f
                canvas.drawLine(cx, cy - r - 16, cx, cy + r + 16, ringPaint)
                canvas.drawLine(cx - r - 16, cy, cx + r + 16, cy, ringPaint)
                val tw = textPaint.measureText(label)
                canvas.drawText(label, cx - tw / 2, cy + r + 36, textPaint)
            }
        }
        marker.setWillNotDraw(false)
        val lp = WindowManager.LayoutParams(
            size + 80, size + 60,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (px - (size + 80) / 2).coerceIn(-(size / 2), 2120)
            y = (py - size / 2).coerceIn(0, 3000)
        }
        mainHandler.post {
            try {
                wmRef.addView(marker, lp)
                mainHandler.postDelayed({ try { wmRef.removeView(marker) } catch (_: Exception) {} }, 1200)
            } catch (t: Throwable) { Log.w(TAG, "showTapMarker failed", t) }
        }
    }

    /**
     * 注意：Path 只 moveTo 不 lineTo 时，部分机型会当成无效手势直接 cancel。
     * 必须给一个极小位移。
     */
    private fun tap(
        point: PointConfig,
        durationMs: Long = 90L,
        drift: Float = 1.5f
    ): Boolean {
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + drift, y + drift)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40L, 300L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    private fun longPress(point: PointConfig): Boolean {
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val accepted = AtomicReference<Boolean?>(null)
        val err = AtomicReference<String?>(null)

        // 必须在主线程派发，并把 callback handler 也设为主线程 Handler
        mainHandler.post {
            try {
                val ok = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.i(TAG, "gesture completed")
                            result.set(true)
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "gesture cancelled")
                            result.set(false)
                            latch.countDown()
                        }
                    },
                    mainHandler
                )
                accepted.set(ok)
                Log.i(TAG, "dispatchGesture accepted=$ok")
                if (!ok) {
                    err.set("dispatchGesture 返回 false（服务可能未完全就绪）")
                    latch.countDown()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "dispatchGesture exception", t)
                accepted.set(false)
                err.set(t.message)
                latch.countDown()
            }
        }

        val finished = latch.await(5, TimeUnit.SECONDS)
        if (!finished) {
            Log.w(TAG, "dispatchGesture timeout")
            return false
        }
        if (accepted.get() == false) {
            Log.w(TAG, "dispatchGesture not accepted: ${err.get()}")
            return false
        }
        return result.get()
    }

    private fun step(msg: String) {
        Log.i(TAG, "STEP: $msg")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun PointConfig.isUsable(): Boolean = x > 0 && y > 0
    private fun PointConfig.label(): String = "($x,$y)"

    companion object {
        private const val TAG = "PhotoCallA11y"
        private const val WECHAT_PKG = "com.tencent.mm"
        const val ACTION_START_CALL = "com.family.photocall.action.START_CALL"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_FORCE_DRY_RUN = "force_dry_run"
        const val ACTION_VERIFY_TAP = "com.family.photocall.action.VERIFY_TAP"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"

        fun requestVerifyTap(context: Context, x: Int, y: Int) {
            val intent = Intent(ACTION_VERIFY_TAP).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TAP_X, x)
                putExtra(EXTRA_TAP_Y, y)
            }
            context.sendBroadcast(intent)
        }

        @Volatile
        var instance: PhotoCallAccessibilityService? = null
            private set

        /** 跨进程：不能看 instance，要看系统设置 */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = "${context.packageName}/${PhotoCallAccessibilityService::class.java.name}"
            val shortExpected = "${context.packageName}/.service.PhotoCallAccessibilityService"
            return enabled.split(':').any {
                it.equals(expected, true) || it.equals(shortExpected, true) ||
                    (it.contains(context.packageName) && it.contains("PhotoCallAccessibilityService"))
            }
        }

        fun requestStartCall(context: Context, contactId: String, forceDryRun: Boolean? = null) {
            val intent = Intent(ACTION_START_CALL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CONTACT_ID, contactId)
                if (forceDryRun != null) putExtra(EXTRA_FORCE_DRY_RUN, forceDryRun)
            }
            context.sendBroadcast(intent)
        }
    }
}
