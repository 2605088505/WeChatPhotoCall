package com.family.photocall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.AutomationActions
import com.family.photocall.model.AutomationStepConfig
import com.family.photocall.service.CalibrationOverlayService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class AutomationStepsActivity : AppCompatActivity() {
    private lateinit var repo: ConfigRepository
    private lateinit var adapter: StepAdapter
    private val steps = mutableListOf<AutomationStepConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation_steps)
        repo = ConfigRepository(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        adapter = StepAdapter(
            onEdit = { index -> showEditDialog(index) },
            onMove = { index, direction -> moveStep(index, direction) },
            onToggle = { index -> toggleStep(index) },
            onDelete = { index -> confirmDelete(index) }
        )
        findViewById<RecyclerView>(R.id.recyclerSteps).apply {
            layoutManager = LinearLayoutManager(this@AutomationStepsActivity)
            adapter = this@AutomationStepsActivity.adapter
        }

        findViewById<MaterialButton>(R.id.btnAddStep).setOnClickListener { showAddDialog() }
        findViewById<MaterialButton>(R.id.btnStartCalibration).setOnClickListener { startCalibration() }
        findViewById<MaterialButton>(R.id.btnResetSteps).setOnClickListener { confirmReset() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized) reload()
    }

    private fun reload() {
        steps.clear()
        steps.addAll(repo.ensureAutomationSteps())
        adapter.submit(steps)
    }

    private fun saveSteps() {
        repo.updateAutomationSteps(steps.toList())
        adapter.submit(steps)
    }

    private fun showAddDialog() {
        val nameInput = TextInputEditText(this).apply {
            hint = "例如：打开家庭相册"
            setSingleLine(true)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = "步骤名称"
            addView(nameInput)
        }
        val skipSwitch = MaterialSwitch(this).apply {
            text = "演示模式跳过（例如最后确认按钮）"
        }
        val container = dialogContainer(nameLayout, skipSwitch)
        AlertDialog.Builder(this)
            .setTitle("新增点击步骤")
            .setMessage("新增步骤默认是坐标点击，保存后到悬浮校准里点选位置。")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "步骤名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    steps.add(
                        AutomationStepConfig(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            action = AutomationActions.TAP,
                            skipInDryRun = skipSwitch.isChecked
                        )
                    )
                    saveSteps()
                }
            }
            .show()
    }

    private fun showEditDialog(index: Int) {
        val current = steps[index]
        val nameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            setText(current.name)
            setSelection(text?.length ?: 0)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = "步骤名称"
            addView(nameInput)
        }
        val delayInput = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(current.delayMs.toString())
        }
        val delayLayout = TextInputLayout(this).apply {
            hint = "执行后等待毫秒"
            addView(delayInput)
        }
        val enabledSwitch = MaterialSwitch(this).apply {
            text = "启用此步骤"
            isChecked = current.enabled
        }
        val skipSwitch = MaterialSwitch(this).apply {
            text = "演示模式跳过"
            isChecked = current.skipInDryRun
        }
        val container = dialogContainer(nameLayout, delayLayout, enabledSwitch, skipSwitch)
        AlertDialog.Builder(this)
            .setTitle("编辑步骤")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "步骤名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val delay = delayInput.text?.toString()?.toLongOrNull()?.coerceAtLeast(0L) ?: 700L
                    steps[index] = current.copy(
                        name = name,
                        delayMs = delay,
                        enabled = enabledSwitch.isChecked,
                        skipInDryRun = skipSwitch.isChecked
                    )
                    saveSteps()
                }
            }
            .show()
    }

    private fun dialogContainer(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            setPadding(20, 8, 20, 0)
        }
    }

    private fun moveStep(index: Int, direction: Int) {
        val target = index + direction
        if (target !in steps.indices) return
        val item = steps.removeAt(index)
        steps.add(target, item)
        saveSteps()
    }

    private fun toggleStep(index: Int) {
        steps[index] = steps[index].copy(enabled = !steps[index].enabled)
        saveSteps()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除步骤")
            .setMessage("确定删除“${steps[index].name}”？删除后需要重新校准流程。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                steps.removeAt(index)
                saveSteps()
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认流程")
            .setMessage("会用当前旧版校准坐标生成默认步骤，并覆盖当前自定义顺序。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                steps.clear()
                steps.addAll(repo.defaultAutomationSteps(repo.load().calibration))
                saveSteps()
            }
            .show()
    }

    private fun startCalibration() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }
        saveSteps()
        CalibrationOverlayService.start(this)
        Toast.makeText(this, "校准面板已启动，可切换到微信", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private class StepAdapter(
        private val onEdit: (Int) -> Unit,
        private val onMove: (Int, Int) -> Unit,
        private val onToggle: (Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<StepAdapter.VH>() {
        private val items = mutableListOf<AutomationStepConfig>()

        fun submit(list: List<AutomationStepConfig>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_automation_step, parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position, items.size, onEdit, onMove, onToggle, onDelete)
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<android.widget.TextView>(R.id.tvStepName)
            private val meta = itemView.findViewById<android.widget.TextView>(R.id.tvStepMeta)
            private val up = itemView.findViewById<MaterialButton>(R.id.btnStepUp)
            private val down = itemView.findViewById<MaterialButton>(R.id.btnStepDown)
            private val edit = itemView.findViewById<MaterialButton>(R.id.btnStepEdit)
            private val toggle = itemView.findViewById<MaterialButton>(R.id.btnStepToggle)
            private val delete = itemView.findViewById<MaterialButton>(R.id.btnStepDelete)

            fun bind(
                item: AutomationStepConfig,
                position: Int,
                size: Int,
                onEdit: (Int) -> Unit,
                onMove: (Int, Int) -> Unit,
                onToggle: (Int) -> Unit,
                onDelete: (Int) -> Unit
            ) {
                title.text = "${position + 1}. ${item.name}"
                val action = when (item.action) {
                    AutomationActions.OPEN_WECHAT -> "打开微信"
                    AutomationActions.COPY_SEARCH -> "复制搜索词"
                    else -> "坐标点击"
                }
                val point = if (item.action == AutomationActions.TAP) {
                    if (item.point.x > 0 && item.point.y > 0) "坐标已设置 (${item.point.x},${item.point.y})" else "未校准坐标"
                } else "无需坐标"
                meta.text = "$action · $point · ${item.delayMs}ms" +
                    if (item.skipInDryRun) " · 演示跳过" else ""
                itemView.alpha = if (item.enabled) 1f else 0.45f
                up.isEnabled = position > 0
                down.isEnabled = position < size - 1
                toggle.text = if (item.enabled) "停用" else "启用"
                up.setOnClickListener { onMove(position, -1) }
                down.setOnClickListener { onMove(position, 1) }
                edit.setOnClickListener { onEdit(position) }
                toggle.setOnClickListener { onToggle(position) }
                delete.setOnClickListener { onDelete(position) }
            }
        }
    }
}
