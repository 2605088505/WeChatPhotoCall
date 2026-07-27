package com.family.photocall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val cropRect = RectF()
    private var scale = 1f
    private var downX = 0f
    private var downY = 0f
    private var mode = TouchMode.NONE
    private var activeCorner = Corner.NONE
    private val minCropSize get() = 96f * resources.displayMetrics.density
    private val handleRadius get() = 24f * resources.displayMetrics.density

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        requestLayout()
        invalidate()
    }

    fun croppedBitmap(): Bitmap? {
        val source = bitmap ?: return null
        if (scale <= 0f || cropRect.width() <= 0f || cropRect.height() <= 0f) return null
        val left = ((cropRect.left - imageRect.left) / scale).toInt().coerceIn(0, source.width - 1)
        val top = ((cropRect.top - imageRect.top) / scale).toInt().coerceIn(0, source.height - 1)
        val right = ((cropRect.right - imageRect.left) / scale).toInt().coerceIn(left + 1, source.width)
        val bottom = ((cropRect.bottom - imageRect.top) / scale).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        if (imageRect.isEmpty) return

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, null, imageRect, imagePaint)
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, shadePaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, shadePaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, shadePaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, shadePaint)
        canvas.drawRect(cropRect, borderPaint)

        val handle = handleRadius
        listOf(
            cropRect.left to cropRect.top,
            cropRect.right to cropRect.top,
            cropRect.left to cropRect.bottom,
            cropRect.right to cropRect.bottom
        ).forEach { (x, y) -> canvas.drawCircle(x, y, handle / 2f, handlePaint) }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateGeometry()
    }

    private fun updateGeometry() {
        val source = bitmap ?: return
        if (width <= 0 || height <= 0) return
        scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        imageRect.set(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
        val size = min(imageRect.width(), imageRect.height()) * 0.78f
        cropRect.set(
            imageRect.centerX() - size / 2f,
            imageRect.centerY() - size / 2f,
            imageRect.centerX() + size / 2f,
            imageRect.centerY() + size / 2f
        )
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activeCorner = cornerAt(event.x, event.y)
                mode = when {
                    activeCorner != Corner.NONE -> TouchMode.RESIZE
                    cropRect.contains(event.x, event.y) -> TouchMode.MOVE
                    else -> TouchMode.NONE
                }
                return mode != TouchMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when (mode) {
                    TouchMode.MOVE -> moveCrop(dx, dy)
                    TouchMode.RESIZE -> resizeCrop(dx, dy)
                    TouchMode.NONE -> Unit
                }
                downX = event.x
                downY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = TouchMode.NONE
                activeCorner = Corner.NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val next = RectF(cropRect)
        next.offset(dx, dy)
        if (next.left < imageRect.left) next.offset(imageRect.left - next.left, 0f)
        if (next.top < imageRect.top) next.offset(0f, imageRect.top - next.top)
        if (next.right > imageRect.right) next.offset(imageRect.right - next.right, 0f)
        if (next.bottom > imageRect.bottom) next.offset(0f, imageRect.bottom - next.bottom)
        cropRect.set(next)
    }

    private fun resizeCrop(dx: Float, dy: Float) {
        val next = RectF(cropRect)
        val delta = if (activeCorner == Corner.TOP_LEFT || activeCorner == Corner.BOTTOM_RIGHT) {
            if (abs(dx) >= abs(dy)) dx else dy
        } else {
            if (abs(dx) >= abs(dy)) -dx else -dy
        }
        when (activeCorner) {
            Corner.TOP_LEFT -> next.set(next.left + delta, next.top + delta, next.right, next.bottom)
            Corner.TOP_RIGHT -> next.set(next.left, next.top + delta, next.right - delta, next.bottom)
            Corner.BOTTOM_LEFT -> next.set(next.left + delta, next.top, next.right, next.bottom - delta)
            Corner.BOTTOM_RIGHT -> next.set(next.left, next.top, next.right + delta, next.bottom + delta)
            Corner.NONE -> return
        }
        if (next.width() < minCropSize || next.height() < minCropSize) return
        if (next.left < imageRect.left || next.top < imageRect.top ||
            next.right > imageRect.right || next.bottom > imageRect.bottom) return
        cropRect.set(next)
    }

    private fun cornerAt(x: Float, y: Float): Corner {
        val threshold = handleRadius
        return when {
            distance(x, y, cropRect.left, cropRect.top) <= threshold -> Corner.TOP_LEFT
            distance(x, y, cropRect.right, cropRect.top) <= threshold -> Corner.TOP_RIGHT
            distance(x, y, cropRect.left, cropRect.bottom) <= threshold -> Corner.BOTTOM_LEFT
            distance(x, y, cropRect.right, cropRect.bottom) <= threshold -> Corner.BOTTOM_RIGHT
            else -> Corner.NONE
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) =
        max(abs(x1 - x2), abs(y1 - y2))

    private enum class TouchMode { NONE, MOVE, RESIZE }
    private enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}
