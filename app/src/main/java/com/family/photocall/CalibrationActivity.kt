package com.family.photocall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.photocall.service.CalibrationOverlayService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

class CalibrationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val tvHelp = findViewById<MaterialTextView>(R.id.tvHelp)
        tvHelp.text = """
            校准说明：
            1. 先开启“显示在其他应用上层”（悬浮窗）权限
            2. 点“开始悬浮窗校准”
            3. 按提示自己打开微信对应页面
            4. 点“开始点选”，再点屏幕上的真实按钮位置
            5. 每步都会自动保存，可随时重做某一步

            建议顺序：
            主界面搜索 → 搜索输入框 →（可选）输入法总菜单 → 输入法剪贴板按钮 → 剪贴板第一条 → 搜索结果 → 聊天页+号 → 视频通话 → 确认视频

            关于输入名字（按你的输入法方式）：
            1. App 后台先复制联系人名字到剪贴板
            2. 自动点输入法“剪贴板”按钮
            3. 再点剪贴板面板第一条
            如果剪贴板藏在总菜单里，先校准“输入法总菜单”，没有就跳过。
        """.trimIndent()

        findViewById<MaterialButton>(R.id.btnOverlayPermission).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<MaterialButton>(R.id.btnStartOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            CalibrationOverlayService.start(this)
            Toast.makeText(this, "校准悬浮窗已启动，可切换到微信", Toast.LENGTH_LONG).show()
            // Leave app so user can open WeChat under the overlay panel
            moveTaskToBack(true)
        }

        findViewById<MaterialButton>(R.id.btnStopOverlay).setOnClickListener {
            CalibrationOverlayService.stop(this)
            Toast.makeText(this, "已请求停止校准悬浮窗", Toast.LENGTH_SHORT).show()
        }
    }
}
