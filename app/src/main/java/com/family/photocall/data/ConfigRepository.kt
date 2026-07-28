package com.family.photocall.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.family.photocall.model.AppConfig
import com.family.photocall.model.AutomationActions
import com.family.photocall.model.AutomationStepConfig
import com.family.photocall.model.CalibrationConfig
import com.family.photocall.model.ContactConfig
import com.family.photocall.model.DelayConfig
import com.family.photocall.model.PointConfig
import com.family.photocall.model.PointsConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

class ConfigRepository(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val configFile: File = File(context.filesDir, CONFIG_FILE)

    fun load(): AppConfig {
        return try {
            val fromPrefs = readPrefs()
            val fromFile = readFile()

            // 无障碍服务运行在 :a11y 独立进程，SharedPreferences 在不同进程间
            // 可能保留旧缓存；文件每次都重新读取，确保演练模式和联系人更新即时可见。
            fromFile ?: fromPrefs ?: defaultConfig()
        } catch (t: Exception) {
            Log.e(TAG, "load failed", t)
            defaultConfig()
        }
    }

    fun save(config: AppConfig) {
        val json = gson.toJson(config)
        // SharedPreferences 是主存储，必须成功
        prefs.edit().putString(KEY_JSON, json).commit()
        // 文件只是备份；写失败不能影响校准结果
        try {
            if (configFile.exists() && !configFile.canWrite()) {
                // 清掉无权限/别人 root 写进去的坏文件
                configFile.delete()
            }
            configFile.writeText(json)
        } catch (t: Exception) {
            Log.w(TAG, "backup file write failed (prefs already saved)", t)
            try {
                configFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    fun updateCalibration(calibration: CalibrationConfig) {
        val current = load()
        save(current.copy(calibration = calibration))
    }

    fun updateContacts(contacts: List<ContactConfig>) {
        val current = load()
        save(current.copy(contacts = contacts))
    }

    fun upsertContact(contact: ContactConfig) {
        val current = load()
        val list = current.contacts.toMutableList()
        val idx = list.indexOfFirst { it.id == contact.id }
        if (idx >= 0) list[idx] = contact else list.add(contact)
        save(current.copy(contacts = list))
    }

    fun deleteContact(id: String) {
        val current = load()
        save(current.copy(contacts = current.contacts.filterNot { it.id == id }))
    }

    fun setDryRun(dryRun: Boolean) {
        val current = load()
        save(current.copy(dryRun = dryRun))
    }

    fun getAutomationSteps(config: AppConfig = load()): List<AutomationStepConfig> {
        val steps = config.automationSteps ?: defaultAutomationSteps(config.calibration)
        return removeLegacyBackStep(steps)
    }

    fun ensureAutomationSteps(): List<AutomationStepConfig> {
        val current = load()
        val rawSteps = current.automationSteps ?: defaultAutomationSteps(current.calibration)
        val steps = removeLegacyBackStep(rawSteps)
        if (current.automationSteps == null || steps != rawSteps) {
            save(current.copy(automationSteps = steps))
        }
        return steps
    }

    fun updateAutomationSteps(steps: List<AutomationStepConfig>) {
        save(load().copy(automationSteps = normalizeAutomationSteps(steps)))
    }

    fun exportJson(): String = gson.toJson(load())

    fun importJson(json: String) {
        val parsed = gson.fromJson(json, AppConfig::class.java)
            ?: throw IllegalArgumentException("invalid config")
        save(parsed)
    }

    fun newContactId(): String = UUID.randomUUID().toString()

    fun defaultConfig(): AppConfig {
        return AppConfig(
            contacts = listOf(
                ContactConfig(
                    id = "demo-1",
                    displayName = "测试联系人",
                    searchName = "测试",
                    enabled = true
                )
            ),
            calibration = CalibrationConfig(),
            dryRun = true
        )
    }

    fun missingCalibrationKeys(config: AppConfig = load()): List<String> {
        val steps = getAutomationSteps(config)
        if (steps.isEmpty()) return listOf("没有配置点击步骤")
        return steps
            .filter { it.enabled && needsCoordinate(it) }
            .filter { it.point.x <= 0 || it.point.y <= 0 }
            .map { it.name }
    }

    fun isCalibrationReady(config: AppConfig = load()): Boolean {
        val steps = getAutomationSteps(config)
        return steps.isNotEmpty() && missingCalibrationKeys(config).isEmpty()
    }

    fun defaultAutomationSteps(calibration: CalibrationConfig): List<AutomationStepConfig> {
        val p = calibration.points
        val d = calibration.delays
        fun tap(
            id: String,
            name: String,
            point: PointConfig,
            delayMs: Long = 700,
            enabled: Boolean = true,
            skipInDryRun: Boolean = false
        ) = AutomationStepConfig(
            id = id,
            name = name,
            action = AutomationActions.TAP,
            point = point,
            delayMs = delayMs,
            enabled = enabled,
            skipInDryRun = skipInDryRun
        )

        return listOf(
            AutomationStepConfig(
                id = "copy_search",
                name = "复制微信搜索词",
                action = AutomationActions.COPY_SEARCH,
                delayMs = 300
            ),
            AutomationStepConfig(
                id = "open_wechat",
                name = "打开微信",
                action = AutomationActions.OPEN_WECHAT,
                delayMs = d.afterOpenWechatMs.coerceIn(800L, 1200L)
            ),
            tap("search_entry", "搜索入口", p.searchEntry, d.afterSearchEntryMs.coerceAtLeast(1200)),
            tap("search_input", "搜索输入框", p.searchInput, 900),
            tap(
                "ime_menu",
                "输入法总菜单",
                p.imeMenu,
                d.afterImeMenuMs.coerceAtLeast(500),
                enabled = p.imeMenu.x > 0 && p.imeMenu.y > 0
            ),
            tap("ime_clipboard", "输入法剪贴板", p.imeClipboard, d.afterImeClipboardMs.coerceAtLeast(700)),
            tap("ime_clipboard_item1", "剪贴板第一条", p.imeClipboardItem1, d.afterImeItemMs.coerceAtLeast(800)),
            tap("search_result_1", "第一个搜索结果", p.searchResult1, d.afterResultMs.coerceAtLeast(1600)),
            tap("chat_more", "聊天页加号", p.chatMore, d.afterChatMoreMs.coerceAtLeast(1100)),
            tap("video_call", "视频通话", p.videoCall, d.afterVideoCallMs.coerceAtLeast(1100)),
            tap(
                "video_call_confirm",
                "确认视频通话",
                p.videoCallConfirm,
                d.afterVideoConfirmMs.coerceAtLeast(900),
                skipInDryRun = true
            )
        )
    }

    private fun needsCoordinate(step: AutomationStepConfig): Boolean {
        return step.action == AutomationActions.TAP
    }

    /** The old fixed back step is no longer part of the customizable flow. */
    private fun removeLegacyBackStep(steps: List<AutomationStepConfig>): List<AutomationStepConfig> {
        return normalizeAutomationSteps(steps).filterNot {
            it.id.equals("back", ignoreCase = true) || it.name == "返回位置（可选）"
        }
    }

    /** Never allow demo mode to reach the actual call confirmation tap. */
    private fun normalizeAutomationSteps(steps: List<AutomationStepConfig>): List<AutomationStepConfig> {
        return steps.map { step ->
            if (isCallConfirmationStep(step)) step.copy(skipInDryRun = true) else step
        }
    }

    private fun isCallConfirmationStep(step: AutomationStepConfig): Boolean {
        return step.id.equals("video_call_confirm", ignoreCase = true) ||
            step.name.trim() in setOf("确认视频通话", "确认通话", "视频通话确认")
    }

    private fun readPrefs(): AppConfig? {
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return try {
            gson.fromJson(json, AppConfig::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun readFile(): AppConfig? {
        if (!configFile.exists()) return null
        return try {
            gson.fromJson(configFile.readText(), AppConfig::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PhotoCallConfig"
        private const val PREFS = "photo_call_prefs"
        private const val KEY_JSON = "app_config_json"
        private const val CONFIG_FILE = "app_config.json"
    }
}

fun PointConfig.isSet(): Boolean = x > 0 && y > 0

fun PointsConfig.withStep(key: String, point: PointConfig): PointsConfig {
    return when (key) {
        "search_entry" -> copy(searchEntry = point)
        "search_input" -> copy(searchInput = point)
        "input_action" -> copy(inputAction = point)
        "ime_menu" -> copy(imeMenu = point)
        "ime_clipboard" -> copy(imeClipboard = point)
        "ime_clipboard_item1" -> copy(imeClipboardItem1 = point)
        "search_result_1" -> copy(searchResult1 = point)
        "chat_more" -> copy(chatMore = point)
        "video_call" -> copy(videoCall = point)
        "video_call_confirm" -> copy(videoCallConfirm = point)
        "home_tab_wechat" -> copy(homeTabWechat = point)
        "back" -> copy(back = point)
        else -> this
    }
}

fun PointsConfig.pointOf(key: String): PointConfig {
    return when (key) {
        "search_entry" -> searchEntry
        "search_input" -> searchInput
        "input_action" -> inputAction
        "ime_menu" -> imeMenu
        "ime_clipboard" -> imeClipboard
        "ime_clipboard_item1" -> imeClipboardItem1
        "search_result_1" -> searchResult1
        "chat_more" -> chatMore
        "video_call" -> videoCall
        "video_call_confirm" -> videoCallConfirm
        "home_tab_wechat" -> homeTabWechat
        "back" -> back
        else -> PointConfig()
    }
}

fun DelayConfig.copyDefaultsIfNeeded(): DelayConfig = this
