package com.family.photocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.ContactConfig
import com.family.photocall.service.PhotoCallAccessibilityService
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var repo: ConfigRepository
    private lateinit var recycler: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = ConfigRepository(this)

        recycler = findViewById(R.id.recyclerContacts)
        statusText = findViewById(R.id.tvStatus)
        val btnSettings = findViewById<FloatingActionButton>(R.id.btnSettings)
        val btnRefresh = findViewById<MaterialButton>(R.id.btnRefreshStatus)

        adapter = ContactAdapter { contact -> onContactClicked(contact) }
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnRefresh.setOnClickListener { refreshStatus() }

        maybeRequestNotificationPermission()
        ensureDefaultConfig()
    }

    override fun onResume() {
        super.onResume()
        reloadContacts()
        refreshStatus()
    }

    private fun ensureDefaultConfig() {
        val cfg = repo.load()
        if (cfg.contacts.isEmpty()) {
            repo.save(repo.defaultConfig())
        }
    }

    private fun reloadContacts() {
        val contacts = repo.load().contacts.filter { it.enabled }
        adapter.submit(contacts)
    }

    private fun refreshStatus() {
        val a11y = PhotoCallAccessibilityService.isEnabled(this)
        val overlay = Settings.canDrawOverlays(this)
        val ready = repo.isCalibrationReady()
        val dry = repo.load().dryRun
        statusText.text = buildString {
            append(if (a11y) "无障碍：已开启" else "无障碍：未开启")
            append("  |  ")
            append(if (overlay) "悬浮窗：已开" else "悬浮窗：未开")
            append("  |  ")
            append(if (ready) "坐标：已校准" else "坐标：未校准")
            append("  |  ")
            append(if (dry) "模式：演练" else "模式：实拨")
        }
    }

    private fun onContactClicked(contact: ContactConfig) {
        if (!PhotoCallAccessibilityService.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍权限")
                .setMessage("本 App 通过无障碍手势点击坐标自动操作微信，请在系统设置中开启“亲情照片通话”无障碍服务。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (!repo.isCalibrationReady()) {
            val missing = repo.missingCalibrationKeys().joinToString("、")
            AlertDialog.Builder(this)
                .setTitle("请先完成坐标校准")
                .setMessage(
                    "以下点位还没标好：\n$missing\n\n" +
                        "请到设置 → 坐标校准，把每一步都点选保存。"
                )
                .setPositiveButton("去校准") { _, _ ->
                    startActivity(Intent(this, CalibrationActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 通过 :a11y 进程前台服务触发，避免主进程被冻结后手势失效
        com.family.photocall.service.CallKeepAliveService.startCall(this, contact.id)
        Toast.makeText(this, "已开始：${contact.displayName}", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            moveTaskToBack(true)
        }, 400)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private class ContactAdapter(
        private val onClick: (ContactConfig) -> Unit
    ) : RecyclerView.Adapter<ContactAdapter.VH>() {
        private val items = mutableListOf<ContactConfig>()

        fun submit(list: List<ContactConfig>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onClick)
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val avatar: ImageView = itemView.findViewById(R.id.imgAvatar)
            private val name: TextView = itemView.findViewById(R.id.tvName)

            fun bind(item: ContactConfig, onClick: (ContactConfig) -> Unit) {
                name.text = item.displayName
                if (item.avatarPath.isNotBlank()) {
                    val f = File(item.avatarPath)
                    if (f.exists()) {
                        avatar.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                    } else {
                        avatar.setImageResource(R.drawable.ic_person)
                    }
                } else {
                    avatar.setImageResource(R.drawable.ic_person)
                }
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
