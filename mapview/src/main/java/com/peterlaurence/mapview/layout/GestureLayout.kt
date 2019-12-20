package com.peterlaurence.mapview.layout

import android.content.Context
import android.util.AttributeSet
import android.view.*
import android.widget.Scroller
import androidx.core.view.ViewCompat
import com.peterlaurence.mapview.layout.animators.ZoomPanAnimator
import com.peterlaurence.mapview.layout.controllers.ScaleController
import com.peterlaurence.mapview.layout.detectors.RotationGestureDetector
import com.peterlaurence.mapview.layout.detectors.TouchUpGestureDetector
import com.peterlaurence.mapview.util.scale
import kotlin.math.*

/**
 * GestureLayout provides support for scrolling, zooming, and rotating.
 * Fling, drag, pinch and double-tap events are supported natively.
 *
 * @author P.Laurence on 12/12/19
 */
abstract class GestureLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        ViewGroup(context, attrs, defStyleAttr), GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener,
        TouchUpGestureDetector.OnTouchUpListener, RotationGestureDetector.OnRotationGestureListener,
        ScaleController.Scalable {

    /* Controllers */
    internal val scaleController: ScaleController by lazy { ScaleController(this) }

    private var mImagePadding: Int = 0
    private var mScaledImagePadding: Int = 0

    override fun onMinScaleUpdateRequest() {
        scaleController.calculateMinimumScaleToFit(width, height, scaleController.baseWidth, scaleController.baseHeight)
    }

    /**
     * The [ScaleController] is the actual owner of the scale.
     */
    var scale: Float
        get() = scaleController.scale
        set(value) {
            scaleController.scale = value
        }

    /**
     * The horizontal distance children are offset if the content is scaled smaller than width.
     */
    var offsetX: Int = 0
        private set

    /**
     * The vertical distance children are offset if the content is scaled smaller than height.
     */
    var offsetY: Int = 0
        private set

    /**
     * Whether the [GestureLayout] is currently being flung.
     */
    var isFlinging: Boolean = false
        private set

    /**
     * Whether the [GestureLayout] is currently being dragged.
     */
    var isDragging: Boolean = false
        private set

    /**
     * Whether the [GestureLayout] is currently scaling.
     */
    var isScaling: Boolean = false
        private set

    /**
     * Whether the [GestureLayout] is currently currently scrolling.
     */
    var isSliding: Boolean = false
        private set

    /**
     * Set the duration zoom and pan animation will use.
     */
    var animationDuration = DEFAULT_ZOOM_PAN_ANIMATION_DURATION
        set(duration) {
            field = duration
            animator.duration = duration.toLong()
        }

    private val scaleGestureDetector: ScaleGestureDetector by lazy {
        ScaleGestureDetector(context, this)
    }
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(context, this)
    }
    private val touchUpGestureDetector: TouchUpGestureDetector by lazy {
        TouchUpGestureDetector(this)
    }
    private val rotationGestureDetector: RotationGestureDetector by lazy {
        RotationGestureDetector(this)
    }

    /* The Scroller instance used to manage dragging and flinging */
    private val scroller: Scroller by lazy {
        Scroller(context)
    }

    private val animator: ZoomPanAnimator by lazy {
        val animator = ZoomPanAnimator(object : ZoomPanAnimator.OnZoomPanAnimationListener {
            override fun setIsScaling(isScaling: Boolean) {
                this@GestureLayout.isScaling = isScaling
            }

            override fun setIsSliding(isSliding: Boolean) {
                this@GestureLayout.isSliding = isSliding
            }

            override fun setScale(scale: Float) {
                this@GestureLayout.scaleController.scale = scale
            }

            override fun scrollTo(x: Int, y: Int) {
                this@GestureLayout.scrollTo(x, y)
            }

            override fun getScrollX(): Int = this@GestureLayout.scrollX
            override fun getScrollY(): Int = this@GestureLayout.scrollY
            override fun getScale(): Float = this@GestureLayout.scaleController.scale

        })
        animator.duration = animationDuration.toLong()
        animator
    }

    val halfWidth: Int
        get() = scale(width, 0.5f)

    val halfHeight: Int
        get() = scale(height, 0.5f)

    private val scrollLimitX: Int
        get() = scaleController.scaledWidth - width + mScaledImagePadding

    private val scrollLimitY: Int
        get() = scaleController.scaledHeight - height + mScaledImagePadding

    private val scrollMinX: Int
        get() = -mScaledImagePadding

    private val scrollMinY: Int
        get() = -mScaledImagePadding

    init {
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the container's children should be the size provided by setSize
        // don't use measureChildren because that grabs the child's LayoutParams
        val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(scaleController.scaledWidth, MeasureSpec.EXACTLY)
        val childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(scaleController.scaledHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
        }
        // but the layout itself should report normal (on screen) dimensions
        var width = MeasureSpec.getSize(widthMeasureSpec)
        var height = MeasureSpec.getSize(heightMeasureSpec)
        width = View.resolveSize(width, widthMeasureSpec)
        height = View.resolveSize(height, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = width       // width of screen in pixels
        val height = height     // height on screen in pixels

        val scaledWidth = scaleController.scaledWidth
        val scaledHeight = scaleController.scaledHeight

        offsetX = if (scaledWidth >= width) 0 else width / 2 - scaledWidth / 2
        offsetY = if (scaledHeight >= height) 0 else height / 2 - scaledHeight / 2

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                child.layout(offsetX, offsetY, scaledWidth + offsetX, scaledHeight + offsetY)
            }
        }
        onMinScaleUpdateRequest()
        constrainScrollToLimits()
    }

    override fun onLayoutChanged() {
        requestLayout()
    }

    override fun onContentChanged() {
        invalidate()
    }

    /**
     * Adds extra padding around the tiled image, making it possible to scroll past the end of
     * the border even when zoomed in.
     *
     * @param padding  Additional empty padding around the tiled image.
     */
    fun setImagePadding(padding: Int) {
        mImagePadding = padding
        recalculateImagePadding()
    }

    /**
     * Scrolls and centers the [GestureLayout] to the x and y values provided.
     *
     * @param x Horizontal destination point.
     * @param y Vertical destination point.
     */
    fun scrollToAndCenter(x: Int, y: Int) {
        scrollTo(x - halfWidth, y - halfHeight)
    }

    /**
     * Set the scale of the [GestureLayout] while maintaining the current center point.
     */
    fun setScaleFromCenter(scale: Float) {
        setScaleFromPosition(halfWidth, halfHeight, scale)
    }

    /**
     * Scrolls the [GestureLayout] to the x and y values provided using scrolling animation.
     *
     * @param x Horizontal destination point.
     * @param y Vertical destination point.
     */
    fun slideTo(x: Int, y: Int) {
        animator.animatePan(x, y)
    }

    /**
     * Scrolls and centers the [GestureLayout] to the x and y values provided using scrolling animation.
     *
     * @param x Horizontal destination point.
     * @param y Vertical destination point.
     */
    fun slideToAndCenter(x: Int, y: Int) {
        slideTo(x - halfWidth, y - halfHeight)
    }

    /**
     * Animates the [GestureLayout] to the scale provided, and centers the viewport to the position
     * supplied.
     *
     * @param x Horizontal destination point.
     * @param y Vertical destination point.
     * @param scale The final scale value the layout should animate to.
     */
    fun slideToAndCenterWithScale(x: Int, y: Int, scale: Float) {
        animator.animateZoomPan(x - halfWidth, y - halfHeight, scale)
    }

    /**
     * Scales the [GestureLayout] with animated progress, without maintaining scroll position.
     *
     * @param destination The final scale value the layout should animate to.
     */
    fun smoothScaleTo(destination: Float) {
        animator.animateZoom(destination)
    }

    /**
     * Animates the [GestureLayout] to the scale provided, while maintaining position determined by
     * the focal point provided.
     *
     * @param focusX The horizontal focal point to maintain, relative to the screen (as supplied by MotionEvent.getX).
     * @param focusY The vertical focal point to maintain, relative to the screen (as supplied by MotionEvent.getY).
     * @param scale The final scale value the layout should animate to.
     */
    fun smoothScaleFromFocalPoint(focusX: Int, focusY: Int, scale: Float) {
        val scaleCst = scaleController.getConstrainedDestinationScale(scale)
        if (scaleCst == scaleController.scale) {
            return
        }
        val x = getOffsetScrollXFromScale(focusX, scaleCst, scaleController.scale)
        val y = getOffsetScrollYFromScale(focusY, scaleCst, scaleController.scale)
        animator.animateZoomPan(x, y, scaleCst)
    }

    /**
     * Animate the scale of the [GestureLayout] while maintaining the current center point.
     *
     * @param scale The final scale value the layout should animate to.
     */
    fun smoothScaleFromCenter(scale: Float) {
        smoothScaleFromFocalPoint(halfWidth, halfHeight, scale)
    }

    override fun constrainScrollToLimits() {
        val x = scrollX
        val y = scrollY
        val constrainedX = getConstrainedScrollX(x)
        val constrainedY = getConstrainedScrollY(y)
        if (x != constrainedX || y != constrainedY) {
            scrollTo(constrainedX, constrainedY)
        }
    }

    private fun getOffsetScrollXFromScale(offsetX: Int, destinationScale: Float, currentScale: Float): Int {
        val scrollX = scrollX + offsetX
        val deltaScale = destinationScale / currentScale
        return (scrollX * deltaScale).toInt() - offsetX
    }

    private fun getOffsetScrollYFromScale(offsetY: Int, destinationScale: Float, currentScale: Float): Int {
        val scrollY = scrollY + offsetY
        val deltaScale = destinationScale / currentScale
        return (scrollY * deltaScale).toInt() - offsetY
    }

    fun setScaleFromPosition(offsetX: Int, offsetY: Int, scale: Float) {
        val scaleCst = scaleController.getConstrainedDestinationScale(scale)
        if (scaleCst == scaleController.scale) {
            return
        }
        var x = getOffsetScrollXFromScale(offsetX, scaleCst, scaleController.scale)
        var y = getOffsetScrollYFromScale(offsetY, scaleCst, scaleController.scale)

        this.scaleController.scale = scaleCst

        x = getConstrainedScrollX(x)
        y = getConstrainedScrollY(y)

        scrollTo(x, y)
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        val position = scrollX
        return if (direction > 0) position < scrollLimitX else direction < 0 && position > 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gestureIntercept = gestureDetector.onTouchEvent(event)
        val scaleIntercept = scaleGestureDetector.onTouchEvent(event)
        val touchIntercept = touchUpGestureDetector.onTouchEvent(event)
        val rotationIntercept = rotationGestureDetector.onTouchEvent(event)
        return gestureIntercept || scaleIntercept || touchIntercept || super.onTouchEvent(event) || rotationIntercept
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(getConstrainedScrollX(x), getConstrainedScrollY(y))
    }

    protected fun getConstrainedScrollX(x: Int): Int {
        return scrollMinX.coerceAtLeast(min(x, scrollLimitX))
    }

    protected fun getConstrainedScrollY(y: Int): Int {
        return scrollMinY.coerceAtLeast(min(y, scrollLimitY))
    }

    override fun recalculateImagePadding() {
        mScaledImagePadding = scale(mImagePadding, scaleController.scale)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val startX = scrollX
            val startY = scrollY
            val endX = getConstrainedScrollX(scroller.currX)
            val endY = getConstrainedScrollY(scroller.currY)
            if (startX != endX || startY != endY) {
                scrollTo(endX, endY)
            }
            if (scroller.isFinished) {
                if (isFlinging) {
                    isFlinging = false
                }
            } else {
                ViewCompat.postInvalidateOnAnimation(this)
            }
        }
    }

    override fun onDown(event: MotionEvent): Boolean {
        if (isFlinging && !scroller.isFinished) {
            scroller.forceFinished(true)
            isFlinging = false
        }
        return true
    }

    override fun onFling(event1: MotionEvent, event2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        scroller.fling(scrollX, scrollY, (-velocityX).toInt(), (-velocityY).toInt(),
                scrollMinX, scrollLimitX, scrollMinY, scrollLimitY)

        isFlinging = true
        ViewCompat.postInvalidateOnAnimation(this)
        return true
    }

    override fun onLongPress(event: MotionEvent) {

    }

    override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val scrollEndX = scrollX + distanceX.toInt()
        val scrollEndY = scrollY + distanceY.toInt()
        scrollTo(scrollEndX, scrollEndY)
        if (!isDragging) {
            isDragging = true
        }
        return true
    }

    override fun onShowPress(event: MotionEvent) {

    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        return true
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        return true
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        val destination = 2.0.pow(floor(ln((scaleController.scale * 2).toDouble()) / ln(2.0))).toFloat()
        val scaleCst = scaleController.getDoubleTapDestinationScale(destination, scaleController.scale)
        smoothScaleFromFocalPoint(event.x.toInt(), event.y.toInt(), scaleCst)
        return true
    }

    override fun onDoubleTapEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun onTouchUp(event: MotionEvent): Boolean {
        if (isDragging) {
            isDragging = false
        }
        return true
    }

    override fun onScaleBegin(scaleGestureDetector: ScaleGestureDetector): Boolean {
        isScaling = true
        return true
    }

    override fun onScaleEnd(scaleGestureDetector: ScaleGestureDetector) {
        isScaling = false
    }

    override fun onScale(scaleGestureDetector: ScaleGestureDetector): Boolean {
        val newScale = scaleController.scale * this.scaleGestureDetector.scaleFactor
        setScaleFromPosition(
                scaleGestureDetector.focusX.toInt(),
                scaleGestureDetector.focusY.toInt(),
                newScale)
        return true
    }

    override fun onRotate(rotationDelta: Float, focusX: Float, focusY: Float): Boolean {
        println("rotate $rotationDelta ($focusX ; $focusY)")
        return true
    }

    override fun onRotationBegin(): Boolean {
        println("rotate start")
        return true
    }

    override fun onRotationEnd() {
        println("rotate end")
    }

    companion object {
        private const val DEFAULT_ZOOM_PAN_ANIMATION_DURATION = 400
    }
}


fun GestureLayout.setSize(width: Int, height: Int) {
    scaleController.setSize(width, height)
}
