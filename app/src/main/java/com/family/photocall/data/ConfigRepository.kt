package com.family.photocall.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.family.photocall.model.AppConfig
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

            when {
                fromPrefs != null && fromFile != null -> {
                    // 谁更新时间更新用谁，避免旧坏文件盖住你刚校准的结果
                    if (fromPrefs.calibration.updatedAt >= fromFile.calibration.updatedAt) {
                        fromPrefs
                    } else {
                        fromFile
                    }
                }
                fromPrefs != null -> fromPrefs
                fromFile != null -> fromFile
                else -> defaultConfig()
            }
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
        val p = config.calibration.points
        val checks = listOf(
            "搜索入口" to p.searchEntry,
            "搜索输入" to p.searchInput,
            "输入法剪贴板" to p.imeClipboard,
            "剪贴板第一条" to p.imeClipboardItem1,
            "搜索结果" to p.searchResult1,
            "聊天加号" to p.chatMore,
            "视频通话" to p.videoCall,
            "确认视频" to p.videoCallConfirm
        )
        return checks.filter { it.second.x <= 0 || it.second.y <= 0 }.map { it.first }
    }

    fun isCalibrationReady(config: AppConfig = load()): Boolean {
        return missingCalibrationKeys(config).isEmpty()
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
