package com.family.photocall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.ContactConfig
import com.family.photocall.service.PhotoCallAccessibilityService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: ConfigRepository
    private lateinit var adapter: SimpleContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repo = ConfigRepository(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val switchDryRun = findViewById<MaterialSwitch>(R.id.switchDryRun)
        val tvCalibration = findViewById<MaterialTextView>(R.id.tvCalibrationSummary)
        val btnCalibration = findViewById<MaterialButton>(R.id.btnCalibration)
        val btnA11y = findViewById<MaterialButton>(R.id.btnOpenA11y)
        val btnOverlay = findViewById<MaterialButton>(R.id.btnOpenOverlay)
        val btnAddContact = findViewById<MaterialButton>(R.id.btnAddContact)
        val btnExport = findViewById<MaterialButton>(R.id.btnExport)
        val recycler = findViewById<RecyclerView>(R.id.recyclerContacts)

        adapter = SimpleContactAdapter(
            onEdit = { contact ->
                startActivity(
                    Intent(this, ContactEditActivity::class.java)
                        .putExtra(ContactEditActivity.EXTRA_ID, contact.id)
                )
            },
            onDelete = { contact ->
                AlertDialog.Builder(this)
                    .setTitle("删除联系人")
                    .setMessage("确定删除 ${contact.displayName}？")
                    .setPositiveButton("删除") { _, _ ->
                        repo.deleteContact(contact.id)
                        reload()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val cfg = repo.load()
        switchDryRun.isChecked = cfg.dryRun
        switchDryRun.setOnCheckedChangeListener { _, checked ->
            repo.setDryRun(checked)
            Toast.makeText(
                this,
                if (checked) "已切换为演练模式（不点视频通话）" else "已切换为实拨模式",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnCalibration.setOnClickListener {
            startActivity(Intent(this, AutomationStepsActivity::class.java))
        }
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        btnAddContact.setOnClickListener {
            startActivity(Intent(this, ContactEditActivity::class.java))
        }
        btnExport.setOnClickListener {
            val json = repo.exportJson()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, json)
            }
            startActivity(Intent.createChooser(send, "导出配置"))
        }

        findViewById<MaterialTextView>(R.id.tvServiceState).text =
            if (PhotoCallAccessibilityService.isEnabled(this)) "无障碍服务：已开启" else "无障碍服务：未开启"

        tvCalibration.text = buildCalibrationSummary()
    }

    override fun onResume() {
        super.onResume()
        reload()
        findViewById<MaterialTextView>(R.id.tvCalibrationSummary).text = buildCalibrationSummary()
        findViewById<MaterialTextView>(R.id.tvServiceState).text =
            if (PhotoCallAccessibilityService.isEnabled(this)) "无障碍服务：已开启" else "无障碍服务：未开启"
        findViewById<MaterialSwitch>(R.id.switchDryRun).isChecked = repo.load().dryRun
    }

    private fun reload() {
        adapter.submit(repo.load().contacts)
    }

    private fun buildCalibrationSummary(): String {
        val cfg = repo.load()
        val c = cfg.calibration
        val steps = repo.getAutomationSteps(cfg)
        val missing = repo.missingCalibrationKeys(cfg)
        return buildString {
            append(
                if (missing.isEmpty()) "状态: 已完成校准\n"
                else "状态: 还缺 ${missing.joinToString("、")}\n"
            )
            append("分辨率: ${c.screenWidth}x${c.screenHeight}\n")
            if (steps.isEmpty()) {
                append("点击流程: 未配置")
            } else {
                append("点击流程:\n")
                steps.forEachIndexed { index, step ->
                    val point = if (step.point.x > 0 && step.point.y > 0) {
                        "✓(${step.point.x},${step.point.y})"
                    } else if (step.action == com.family.photocall.model.AutomationActions.TAP) {
                        "未设置"
                    } else {
                        "无需坐标"
                    }
                    append("${index + 1}. ${step.name}: $point")
                    if (!step.enabled) append(" [停用]")
                    append("\n")
                }
            }
        }
    }

    private class SimpleContactAdapter(
        private val onEdit: (ContactConfig) -> Unit,
        private val onDelete: (ContactConfig) -> Unit
    ) : RecyclerView.Adapter<SimpleContactAdapter.VH>() {
        private val items = mutableListOf<ContactConfig>()

        fun submit(list: List<ContactConfig>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater(parent).inflate(R.layout.item_contact_settings, parent, false)
            return VH(v)
        }

        private fun layoutInflater(parent: android.view.ViewGroup) =
            android.view.LayoutInflater.from(parent.context)

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onEdit, onDelete)
        }

        class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<android.widget.TextView>(R.id.tvTitle)
            private val subtitle = itemView.findViewById<android.widget.TextView>(R.id.tvSubtitle)
            private val btnEdit = itemView.findViewById<MaterialButton>(R.id.btnEdit)
            private val btnDelete = itemView.findViewById<MaterialButton>(R.id.btnDelete)

            fun bind(
                item: ContactConfig,
                onEdit: (ContactConfig) -> Unit,
                onDelete: (ContactConfig) -> Unit
            ) {
                title.text = item.displayName
                subtitle.text = "搜索词：${item.searchName}"
                btnEdit.setOnClickListener { onEdit(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
