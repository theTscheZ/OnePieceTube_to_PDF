package at.aaron.onepiece

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/** One PDF page at a time. At fit scale, a rightward swipe advances (RTL manga order).
 * While zoomed, gestures pan the image and never accidentally turn a page. */
class MangaPageView(context: Context) : View(context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var files = emptyList<File>()
    private var chapter = 0
    private var page = 0
    private var count = 0
    private var generation = 0
    private var loading = false
    private var disposed = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var multiTouch = false
    private var downX = 0f
    private var downY = 0f
    private var startedZoomed = false
    var onTap: () -> Unit = {}
    var onPosition: (File, Int, Int) -> Unit = { _, _, _ -> }
    var onLoading: (Boolean) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onBoundary: (String) -> Unit = {}

    fun open(orderedFiles: List<File>, file: File, initialPage: Int) {
        files = orderedFiles
        chapter = files.indexOf(file).coerceAtLeast(0)
        page = initialPage
        render()
    }
    fun jumpToPage(number: Int) { if (!loading) { page = number; render() } }
    fun turn(forward: Boolean) {
        if (loading || files.isEmpty()) return
        val next = ReadingOrder.next(ReadingOrder.Position(chapter, page), count, files.size, forward)
        if (next == null) {
            onBoundary(if (forward) "Ende der Sammlung" else "Anfang der Sammlung"); return
        }
        chapter = next.chapter; page = next.page
        render()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (files.isNotEmpty()) render() }
    private fun render() {
        if (width == 0 || height == 0 || files.isEmpty() || disposed) return
        val token = ++generation
        val file = files[chapter]
        val requested = page
        val renderWidth = (width * 2).coerceAtMost(2400)
        loading = true; onLoading(true)
        // Clear the previous page immediately so rapid transitions cannot show a wrong chapter.
        bitmap = null; zoom = 1f; panX = 0f; panY = 0f; invalidate()
        executor.execute {
            try {
                var actual = 0; var pages = 0
                val rendered = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        pages = pdf.pageCount
                        require(pages > 0) { "PDF enthält keine Seiten" }
                        actual = requested.coerceIn(0, pages - 1)
                        pdf.openPage(actual).use { source ->
                            val factor = min(renderWidth.toFloat() / source.width, 3200f / source.height)
                            val result = Bitmap.createBitmap((source.width * factor).toInt().coerceAtLeast(1),
                                (source.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            result.eraseColor(Color.WHITE)
                            try { source.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            catch (e: Exception) { result.recycle(); throw e }
                            result
                        }
                    }
                }
                main.post {
                    if (disposed || token != generation) rendered.recycle()
                    else {
                        bitmap = rendered; page = actual; count = pages; loading = false
                        onLoading(false); onPosition(file, page, count); invalidate()
                    }
                }
            } catch (e: Exception) {
                main.post { if (!disposed && token == generation) {
                    loading = false; onLoading(false); onError("${file.name}: ${e.message ?: "PDF konnte nicht geöffnet werden"}")
                } }
            }
        }
    }
    private fun fit(b: Bitmap) = min(width.toFloat() / b.width, height.toFloat() / b.height)
    private fun clampPan() {
        bitmap?.let {
            val factor = fit(it) * zoom
            val x = ((it.width * factor - width) / 2).coerceAtLeast(0f)
            val y = ((it.height * factor - height) / 2).coerceAtLeast(0f)
            panX = panX.coerceIn(-x, x); panY = panY.coerceIn(-y, y)
        }
    }
    private fun scaleTo(value: Float, focusX: Float, focusY: Float) {
        val newZoom = value.coerceIn(1f, 5f)
        val ratio = newZoom / zoom
        panX = (panX - focusX + width / 2f) * ratio + focusX - width / 2f
        panY = (panY - focusY + height / 2f) * ratio + focusY - height / 2f
        zoom = newZoom; clampPan(); invalidate()
    }
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { scaleTo(if (zoom > 1f) 1f else 2.5f, e.x, e.y); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !pinch.isInProgress) { panX -= distanceX; panY -= distanceY; clampPan(); invalidate() }
            return true
        }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false; downX = event.x; downY = event.y; startedZoomed = zoom > 1.01f
        }
        if (event.pointerCount > 1) multiTouch = true
        pinch.onTouchEvent(event); gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch && !startedZoomed && zoom <= 1.01f) {
            val dx = event.x - downX; val dy = event.y - downY
            if (abs(dx) > 48 * resources.displayMetrics.density && abs(dx) > abs(dy)) turn(dx > 0)
        }
        return true
    }
    fun skipChapter() {
        if (!loading && chapter + 1 < files.size) { chapter++; page = 0; render() }
        else onBoundary("Ende der Sammlung")
    }
    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        bitmap?.let {
            canvas.save(); canvas.translate(width / 2f + panX, height / 2f + panY)
            val scale = fit(it) * zoom; canvas.scale(scale, scale)
            canvas.drawBitmap(it, -it.width / 2f, -it.height / 2f, paint); canvas.restore()
        }
    }
    override fun onDetachedFromWindow() {
        disposed = true; generation++; executor.shutdown(); bitmap = null
        super.onDetachedFromWindow()
    }
}
