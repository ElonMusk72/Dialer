package com.example.ui.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr),
    ScaleGestureDetector.OnScaleGestureListener,
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener {

    private val matrixValues = FloatArray(9)
    private var currentMatrix = Matrix()
    private var mode = NONE

    // Matrix transformations
    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 4f
    private var saveScale = 1f

    private var origWidth = 0f
    private var origHeight = 0f
    private var viewWidth = 0
    private var viewHeight = 0

    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, this)
    private val gestureDetector: GestureDetector = GestureDetector(context, this)

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = currentMatrix
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitImageToView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        viewHeight = MeasureSpec.getSize(heightMeasureSpec)
        fitImageToView()
    }

    private fun fitImageToView() {
        val drawable = drawable ?: return
        val bmWidth = drawable.intrinsicWidth
        val bmHeight = drawable.intrinsicHeight

        if (bmWidth <= 0 || bmHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        origWidth = bmWidth.toFloat()
        origHeight = bmHeight.toFloat()

        val scaleX = viewWidth.toFloat() / bmWidth
        val scaleY = viewHeight.toFloat() / bmHeight
        val scale = Math.min(scaleX, scaleY)

        currentMatrix.setScale(scale, scale)

        // Center the image
        val redundantYSpace = viewHeight.toFloat() - scale * bmHeight
        val redundantXSpace = viewWidth.toFloat() - scale * bmWidth
        currentMatrix.postTranslate(redundantXSpace / 2f, redundantYSpace / 2f)

        saveScale = 1f
        imageMatrix = currentMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val currentPoint = PointF(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(currentPoint)
                start.set(last)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    val deltaX = currentPoint.x - last.x
                    val deltaY = currentPoint.y - last.y
                    val fixTransX = getFixDragTrans(deltaX, viewWidth.toFloat(), origWidth * saveScale)
                    val fixTransY = getFixDragTrans(deltaY, viewHeight.toFloat(), origHeight * saveScale)
                    currentMatrix.postTranslate(fixTransX, fixTransY)
                    fixTrans()
                    last.set(currentPoint.x, currentPoint.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }

        imageMatrix = currentMatrix
        invalidate()
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        var scaleFactor = detector.scaleFactor
        val prevScale = saveScale
        saveScale *= scaleFactor

        if (saveScale > maxScale) {
            saveScale = maxScale
            scaleFactor = maxScale / prevScale
        } else if (saveScale < minScale) {
            saveScale = minScale
            scaleFactor = minScale / prevScale
        }

        if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight) {
            currentMatrix.postScale(scaleFactor, scaleFactor, viewWidth / 2f, viewHeight / 2f)
        } else {
            currentMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
        }

        fixTrans()
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        mode = ZOOM
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    private fun fixTrans() {
        currentMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), origHeight * saveScale)

        if (fixTransX != 0f || fixTransY != 0f) {
            currentMatrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float

        if (contentSize <= viewSize) {
            minTrans = 0f
            maxTrans = viewSize - contentSize
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }

        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) 0f else delta
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (saveScale > 1f) {
            fitImageToView()
        } else {
            val targetScale = 2.5f
            val scaleFactor = targetScale / saveScale
            saveScale = targetScale
            currentMatrix.postScale(scaleFactor, scaleFactor, e.x, e.y)
            fixTrans()
            imageMatrix = currentMatrix
            invalidate()
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = true
    override fun onLongPress(e: MotionEvent) {}
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = true
}
