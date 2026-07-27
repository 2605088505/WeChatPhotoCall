package com.family.photocall

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CropImageActivity : AppCompatActivity() {
    private lateinit var cropView: CropImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_image)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        cropView = findViewById(R.id.cropImageView)

        val uriText = intent.getStringExtra(EXTRA_URI)
        val bitmap = uriText?.let { decodeForCrop(it) }
        if (bitmap == null) {
            Toast.makeText(this, "图片无法读取，请换一张图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        cropView.setBitmap(bitmap)

        findViewById<MaterialButton>(R.id.btnCropCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnCropConfirm).setOnClickListener { saveCrop() }
    }

    private fun saveCrop() {
        val cropped = cropView.croppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "请选择头像区域", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(filesDir, "avatars").apply { mkdirs() }
            val output = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(output).use { stream ->
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
            }
            setResult(Activity.RESULT_OK, intent.putExtra(EXTRA_PATH, output.absolutePath))
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, "头像保存失败", Toast.LENGTH_SHORT).show()
        } finally {
            cropped.recycle()
        }
    }

    private fun decodeForCrop(uriText: String): Bitmap? {
        val uri = android.net.Uri.parse(uriText)
        val source = AvatarImageLoader.load(this, uriText, 2000) ?: return null
        return try {
            val orientation = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (matrix.isIdentity) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        } catch (_: Exception) {
            source
        }
    }

    companion object {
        const val EXTRA_URI = "image_uri"
        const val EXTRA_PATH = "cropped_path"
    }
}
