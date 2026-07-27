package com.family.photocall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object AvatarImageLoader {
    fun load(context: Context, path: String, targetPx: Int): Bitmap? {
        if (path.isBlank()) return null
        val resolver = context.contentResolver
        val isContentUri = path.startsWith("content://")

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(path, resolver, isContentUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sample = calculateSample(bounds.outWidth, bounds.outHeight, targetPx)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            open(path, resolver, isContentUri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateSample(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        val requested = targetPx.coerceAtLeast(1)
        while (width / (sample * 2) >= requested && height / (sample * 2) >= requested) {
            sample *= 2
        }
        return sample
    }

    private fun open(path: String, resolver: android.content.ContentResolver, isContentUri: Boolean) =
        if (isContentUri) resolver.openInputStream(Uri.parse(path)) else File(path).inputStream()
}
