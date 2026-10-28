package com.example.wcamera

import android.content.ContentUris
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
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

class PhotoViewerActivity : ComponentActivity() {
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val loadVersion = AtomicInteger()
    private lateinit var imageView: ImageView
    private lateinit var newerButton: Button
    private lateinit var olderButton: Button
    private lateinit var photoCount: TextView
    private var photos: List<Uri> = emptyList()
    private var currentIndex = 0

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
        newerButton = findViewById(R.id.newer_photo)
        olderButton = findViewById(R.id.older_photo)
        photoCount = findViewById(R.id.photo_count)
        findViewById<Button>(R.id.close_photo).setOnClickListener { finish() }
        newerButton.setOnClickListener { movePhoto(-1) }
        olderButton.setOnClickListener { movePhoto(1) }
        setupSwipe()

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
            val result = if (initialIndex < 0) listOf(initialUri) + list else list
            ContextCompat.getMainExecutor(this).execute {
                if (!isFinishing && !isDestroyed) {
                    photos = result
                    currentIndex = if (initialIndex < 0) 0 else initialIndex
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
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
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

    private fun setupSwipe() {
        val threshold = 48 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var multiplePointers = false
        imageView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    multiplePointers = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
                MotionEvent.ACTION_UP -> {
                    val distanceX = event.x - downX
                    val distanceY = event.y - downY
                    if (!multiplePointers && abs(distanceX) >= threshold && abs(distanceX) > abs(distanceY)) {
                        movePhoto(if (distanceX < 0) 1 else -1)
                    } else if (!multiplePointers && abs(distanceX) < threshold && abs(distanceY) < threshold) {
                        view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun movePhoto(offset: Int) {
        val next = currentIndex + offset
        if (next !in photos.indices) return
        currentIndex = next
        showCurrentPhoto()
    }

    private fun showCurrentPhoto() {
        val uri = photos[currentIndex]
        val version = loadVersion.incrementAndGet()
        newerButton.isEnabled = currentIndex > 0
        olderButton.isEnabled = currentIndex < photos.lastIndex
        photoCount.text = getString(R.string.photo_position, currentIndex + 1, photos.size)
        imageView.setImageDrawable(null)
        val width = imageView.width
        val height = imageView.height
        imageExecutor.execute {
            if (version != loadVersion.get()) return@execute
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = minOf(
                        1.0,
                        width.toDouble() / info.size.width,
                        height.toDouble() / info.size.height
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
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: IOException) {
                reportPhotoError(uri, version, e)
            } catch (e: SecurityException) {
                reportPhotoError(uri, version, e)
            }
        }
    }

    private fun reportPhotoError(uri: Uri, version: Int, error: Exception) {
        Log.e(TAG, "Unable to load photo: $uri", error)
        ContextCompat.getMainExecutor(this).execute {
            if (!isFinishing && !isDestroyed && version == loadVersion.get()) {
                Toast.makeText(this, R.string.photo_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        photos.getOrNull(currentIndex)?.let { outState.putString(STATE_PHOTO_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadVersion.incrementAndGet()
        imageExecutor.shutdownNow()
        imageView.setImageDrawable(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhotoViewer"
        private const val STATE_PHOTO_URI = "current_photo_uri"
    }
}
