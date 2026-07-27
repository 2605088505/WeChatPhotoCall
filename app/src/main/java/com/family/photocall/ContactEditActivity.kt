package com.family.photocall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.family.photocall.data.ConfigRepository
import com.family.photocall.model.ContactConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ContactEditActivity : AppCompatActivity() {

    private lateinit var repo: ConfigRepository
    private var contactId: String? = null
    private var avatarPath: String = ""

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            avatarPath = copyAvatar(uri) ?: ""
            Toast.makeText(this, if (avatarPath.isBlank()) "头像保存失败" else "头像已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_edit)
        repo = ConfigRepository(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val etName = findViewById<TextInputEditText>(R.id.etDisplayName)
        val etSearch = findViewById<TextInputEditText>(R.id.etSearchName)
        val switchEnabled = findViewById<MaterialSwitch>(R.id.switchEnabled)
        val btnPickAvatar = findViewById<MaterialButton>(R.id.btnPickAvatar)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        contactId = intent.getStringExtra(EXTRA_ID)
        if (contactId != null) {
            val existing = repo.load().contacts.firstOrNull { it.id == contactId }
            if (existing != null) {
                etName.setText(existing.displayName)
                etSearch.setText(existing.searchName)
                switchEnabled.isChecked = existing.enabled
                avatarPath = existing.avatarPath
            }
        } else {
            switchEnabled.isChecked = true
        }

        btnPickAvatar.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImage.launch(intent)
        }

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val search = etSearch.text?.toString()?.trim().orEmpty()
            if (name.isBlank() || search.isBlank()) {
                Toast.makeText(this, "显示名和搜索词都要填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val id = contactId ?: repo.newContactId()
            repo.upsertContact(
                ContactConfig(
                    id = id,
                    displayName = name,
                    searchName = search,
                    avatarPath = avatarPath,
                    enabled = switchEnabled.isChecked
                )
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun copyAvatar(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "avatars").apply { mkdirs() }
            val outFile = File(dir, "${contactId ?: UUID.randomUUID()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_ID = "contact_id"
    }
}
