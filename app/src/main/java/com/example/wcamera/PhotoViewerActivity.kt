package com.example.wcamera

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

class PhotoViewerActivity : ComponentActivity() {
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val loadVersion = AtomicInteger()
    private lateinit var imageView: ImageView
    private lateinit var transitionView: ImageView
    private lateinit var photoCount: TextView
    private var photos: List<Uri> = emptyList()
    private var currentIndex = 0
    private var displayedIndex: Int? = null
    private val photoMatrix = Matrix()
    private var photoScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_photo_viewer)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.photo_viewer_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        imageView = findViewById(R.id.photo_image)
        transitionView = findViewById(R.id.photo_transition)
        photoCount = findViewById(R.id.photo_count)
        findViewById<Button>(R.id.close_photo).setOnClickListener { finish() }
        setupGestures()
        imageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                resetPhotoTransform()
            }
        }

        val uri = savedInstanceState?.getString(STATE_PHOTO_URI)?.let(Uri::parse) ?: intent.data
        if (uri == null) {
            Toast.makeText(this, R.string.photo_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        imageView.doOnLayout {
            if (!isFinishing && !isDestroyed) loadPhotoList(uri)
        }
    }

    private fun loadPhotoList(initialUri: Uri) {
        imageExecutor.execute {
            val list = try {
                queryPhotos()
            } catch (e: IOException) {
                reportListError(e)
                emptyList()
            } catch (e: SecurityException) {
                reportListError(e)
                emptyList()
            } catch (e: IllegalArgumentException) {
                reportListError(e)
                emptyList()
            }
            val initialId = initialUri.lastPathSegment
            val initialIndex = list.indexOfFirst { it.lastPathSegment == initialId }
            val result = if (initialIndex < 0) list + initialUri else list
            ContextCompat.getMainExecutor(this).execute {
                if (!isFinishing && !isDestroyed) {
                    photos = result
                    currentIndex = if (initialIndex < 0) result.lastIndex else initialIndex
                    showCurrentPhoto()
                }
            }
        }
    }

    private fun queryPhotos(): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/WCamera/", packageName)
        val order = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
        val cursor = contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID), selection, selectionArgs, order
        ) ?: throw IOException("MediaStore returned no cursor")
        return cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                while (it.moveToNext()) {
                    add(ContentUris.withAppendedId(collection, it.getLong(idColumn)))
                }
            }
        }
    }

    private fun reportListError(error: Exception) {
        Log.e(TAG, "Unable to list photos", error)
        ContextCompat.getMainExecutor(this).execute {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, R.string.photo_list_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGestures() {
        val threshold = 48 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var multiplePointers = false
        var startedZoomed = false
        var doubleTapHandled = false
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (imageView.drawable == null) return false
                doubleTapHandled = true
                if (photoScale > 1f) {
                    resetPhotoTransform()
                } else {
                    photoScale = DOUBLE_TAP_ZOOM
                    photoMatrix.postScale(DOUBLE_TAP_ZOOM, DOUBLE_TAP_ZOOM, event.x, event.y)
                    constrainPhotoTransform()
                }
                return true
            }
        })
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (imageView.drawable == null) return false
                val newScale = (photoScale * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                val factor = newScale / photoScale
                photoScale = newScale
                photoMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainPhotoTransform()
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        imageView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) doubleTapHandled = false
            tapDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    multiplePointers = false
                    startedZoomed = photoScale > 1f
                }
                MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
                MotionEvent.ACTION_MOVE -> {
                    if (!multiplePointers && photoScale > 1f) {
                        photoMatrix.postTranslate(event.x - lastX, event.y - lastY)
                        constrainPhotoTransform()
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val distanceX = event.x - downX
                    val distanceY = event.y - downY
                    if (!multiplePointers && !doubleTapHandled && !startedZoomed && photoScale == 1f &&
                        abs(distanceX) >= threshold && abs(distanceX) > abs(distanceY)
                    ) {
                        movePhoto(if (distanceX < 0) 1 else -1)
                    } else if (!multiplePointers && !doubleTapHandled &&
                        abs(distanceX) < threshold && abs(distanceY) < threshold
                    ) {
                        view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun resetPhotoTransform() {
        val drawable = imageView.drawable ?: return
        if (imageView.width == 0 || imageView.height == 0 ||
            drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0
        ) return
        val scale = minOf(
            imageView.width.toFloat() / drawable.intrinsicWidth,
            imageView.height.toFloat() / drawable.intrinsicHeight
        )
        val left = (imageView.width - drawable.intrinsicWidth * scale) / 2f
        val top = (imageView.height - drawable.intrinsicHeight * scale) / 2f
        photoMatrix.reset()
        photoMatrix.postScale(scale, scale)
        photoMatrix.postTranslate(left, top)
        photoScale = 1f
        imageView.imageMatrix = photoMatrix
    }

    private fun constrainPhotoTransform() {
        val drawable = imageView.drawable ?: return
        val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        photoMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= imageView.width -> (imageView.width - bounds.width()) / 2f - bounds.left
            bounds.left > 0f -> -bounds.left
            bounds.right < imageView.width -> imageView.width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= imageView.height -> (imageView.height - bounds.height()) / 2f - bounds.top
            bounds.top > 0f -> -bounds.top
            bounds.bottom < imageView.height -> imageView.height - bounds.bottom
            else -> 0f
        }
        photoMatrix.postTranslate(dx, dy)
        imageView.imageMatrix = photoMatrix
    }

    private fun movePhoto(offset: Int) {
        val next = currentIndex + offset
        if (next !in photos.indices) return
        currentIndex = next
        if (next == displayedIndex) {
            loadVersion.incrementAndGet()
            return
        }
        showCurrentPhoto()
    }

    private fun showCurrentPhoto() {
        val targetIndex = currentIndex
        val uri = photos[targetIndex]
        val version = loadVersion.incrementAndGet()
        val width = imageView.width
        val height = imageView.height
        imageExecutor.execute {
            if (version != loadVersion.get()) return@execute
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = minOf(
                        1.0,
                        width.toDouble() * MAX_ZOOM / info.size.width,
                        height.toDouble() * MAX_ZOOM / info.size.height,
                        sqrt(MAX_DECODE_PIXELS / (info.size.width.toDouble() * info.size.height))
                    )
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                ContextCompat.getMainExecutor(this).execute {
                    if (isFinishing || isDestroyed || version != loadVersion.get()) {
                        bitmap.recycle()
                    } else {
                        displayPhoto(bitmap, targetIndex)
                    }
                }
            } catch (e: IOException) {
                reportPhotoError(uri, version, e)
            } catch (e: SecurityException) {
                reportPhotoError(uri, version, e)
            }
        }
    }

    private fun displayPhoto(bitmap: Bitmap, index: Int) {
        transitionView.animate().cancel()
        transitionView.setImageDrawable(null)
        val previous = imageView.drawable?.constantState?.newDrawable(resources)?.mutate()
        if (previous != null) {
            transitionView.setImageDrawable(previous)
            transitionView.imageMatrix = Matrix(imageView.imageMatrix)
            transitionView.alpha = 1f
        }
        imageView.setImageBitmap(bitmap)
        resetPhotoTransform()
        displayedIndex = index
        currentIndex = index
        photoCount.text = getString(R.string.photo_position, index + 1, photos.size)
        if (previous != null) {
            transitionView.animate().alpha(0f).setDuration(150).withEndAction {
                transitionView.setImageDrawable(null)
            }.start()
        }
    }

    private fun reportPhotoError(uri: Uri, version: Int, error: Exception) {
        Log.e(TAG, "Unable to load photo: $uri", error)
        ContextCompat.getMainExecutor(this).execute {
            if (!isFinishing && !isDestroyed && version == loadVersion.get()) {
                displayedIndex?.let { currentIndex = it }
                Toast.makeText(this, R.string.photo_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        photos.getOrNull(displayedIndex ?: currentIndex)?.let { outState.putString(STATE_PHOTO_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadVersion.incrementAndGet()
        imageExecutor.shutdownNow()
        transitionView.animate().cancel()
        transitionView.setImageDrawable(null)
        imageView.setImageDrawable(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhotoViewer"
        private const val STATE_PHOTO_URI = "current_photo_uri"
        private const val DOUBLE_TAP_ZOOM = 4f
        private const val MAX_ZOOM = 8f
        private const val MAX_DECODE_PIXELS = 6_000_000.0
    }
}
