package com.example.wcamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var shutterOverlay: View
    private lateinit var focusIndicator: View
    private lateinit var flashButton: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var captureButton: Button
    private lateinit var switchButton: ImageButton
    private lateinit var thumbnailButton: ImageButton
    private lateinit var retryButton: Button
    private lateinit var orientationListener: OrientationEventListener

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val preferences by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundPreview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var captureRotation = Surface.ROTATION_0
    private var latestPhoto: Uri? = null
    private var thumbnailVersion = 0
    private var startingCamera = false
    private var capturing = false
    private var hasBothCameras = false
    private var viewportUpdatePending = false
    private var thumbnailCancellation: CancellationSignal? = null
    private val photoPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LATEST_PHOTO_KEY) syncLatestPhoto()
        }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showPermissionError()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        previewView = findViewById(R.id.preview)
        shutterOverlay = findViewById(R.id.shutter_overlay)
        focusIndicator = findViewById(R.id.focus_indicator)
        flashButton = findViewById(R.id.flash)
        zoomLabel = findViewById(R.id.zoom)
        captureButton = findViewById(R.id.capture)
        switchButton = findViewById(R.id.switch_camera)
        thumbnailButton = findViewById(R.id.thumbnail)
        retryButton = findViewById(R.id.retry)
        lensFacing = savedInstanceState?.getInt(STATE_LENS_FACING, lensFacing) ?: lensFacing
        flashMode = savedInstanceState?.getInt(STATE_FLASH_MODE, flashMode) ?: flashMode
        @Suppress("DEPRECATION")
        captureRotation = windowManager.defaultDisplay.rotation
        previewView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (sizeChanged && right > left && bottom > top && camera != null) {
                if (capturing) viewportUpdatePending = true else bindCamera()
            }
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                captureRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = captureRotation
            }
        }
        setupGestures()
        updateControls()
        captureButton.setOnClickListener { takePhoto() }
        switchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCamera()
        }
        flashButton.setOnClickListener { cycleFlash() }
        thumbnailButton.setOnClickListener { openLatestPhoto() }
        retryButton.setOnClickListener {
            if (hasCameraPermission()) {
                startCamera()
            } else if (preferences.getBoolean("permission_requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "Cannot open application settings", e)
                    toast(R.string.camera_permission_required)
                }
            } else {
                requestCameraPermission()
            }
        }
        if (!hasCameraPermission()) requestCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(photoPreferenceListener)
        syncLatestPhoto()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (hasCameraPermission() && camera == null) startCamera()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(photoPreferenceListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_LENS_FACING, lensFacing)
        outState.putInt(STATE_FLASH_MODE, flashMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        shutterOverlay.animate().cancel()
        focusIndicator.removeCallbacks(hideFocusIndicator)
        thumbnailCancellation?.cancel()
        thumbnailCancellation = null
        thumbnailVersion++
        ioExecutor.shutdownNow()
        unbindCamera()
        thumbnailButton.setImageDrawable(null)
        super.onDestroy()
    }

    private fun unbindCamera() {
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        val useCases = listOfNotNull(boundPreview, imageCapture)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        boundPreview = null
        imageCapture = null
        camera = null
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        preferences.edit().putBoolean("permission_requested", true).apply()
        permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionError() {
        retryButton.setText(
            if (preferences.getBoolean("permission_requested", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ) R.string.permission_settings else R.string.camera_permission_required
        )
        retryButton.visibility = View.VISIBLE
    }

    private fun startCamera() {
        if (startingCamera || camera != null || !hasCameraPermission()) return
        startingCamera = true
        retryButton.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            startingCamera = false
            if (isFinishing || isDestroyed) return@addListener
            try {
                provider = future.get()
                val cameraProvider = checkNotNull(provider)
                val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                hasBothCameras = hasBack && hasFront
                if (!hasBack && !hasFront) {
                    showCameraError(IllegalStateException("No camera is available"))
                    return@addListener
                }
                if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack) {
                    lensFacing = CameraSelector.LENS_FACING_FRONT
                } else if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront) {
                    lensFacing = CameraSelector.LENS_FACING_BACK
                }
                bindCamera()
            } catch (e: ExecutionException) {
                showCameraError(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                showCameraError(e)
            } catch (e: androidx.camera.core.CameraInfoUnavailableException) {
                showCameraError(e)
            }
        }, uiExecutor)
    }

    private fun bindCamera() {
        val cameraProvider = provider ?: return
        previewView.doOnLayout {
            if (!isFinishing && !isDestroyed && provider === cameraProvider) {
                bindCameraToViewport(cameraProvider)
            }
        }
    }

    private fun bindCameraToViewport(cameraProvider: ProcessCameraProvider) {
        val viewPort = previewView.viewPort
        if (viewPort == null) {
            showCameraError(IllegalStateException("Preview viewport is unavailable"))
            return
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setTargetRotation(captureRotation)
            .setFlashMode(flashMode)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        unbindCamera()
        updateControls()
        try {
            val useCases = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(capture)
                .build()
            val boundCamera = cameraProvider.bindToLifecycle(this, selector, useCases)
            camera = boundCamera
            boundPreview = preview
            imageCapture = capture
            capture.flashMode = if (boundCamera.cameraInfo.hasFlashUnit()) flashMode else ImageCapture.FLASH_MODE_OFF
            boundCamera.cameraInfo.zoomState.observe(this) { state ->
                zoomLabel.text = getString(R.string.zoom_ratio, state.zoomRatio)
            }
            retryButton.visibility = View.GONE
            updateControls()
        } catch (e: IllegalArgumentException) {
            showCameraError(e)
        } catch (e: IllegalStateException) {
            showCameraError(e)
        }
    }

    private fun showCameraError(error: Exception) {
        Log.e(TAG, "Unable to start camera", error)
        unbindCamera()
        updateControls()
        retryButton.setText(R.string.camera_error_retry)
        retryButton.visibility = View.VISIBLE
    }

    private fun updateControls() {
        val ready = camera != null && imageCapture != null && !capturing
        captureButton.isEnabled = ready
        switchButton.isEnabled = ready && hasBothCameras
        switchButton.alpha = if (switchButton.isEnabled) 1f else 0.5f
        flashButton.isEnabled = ready && camera?.cameraInfo?.hasFlashUnit() == true
        flashButton.alpha = if (flashButton.isEnabled) 1f else 0.5f
        flashButton.setText(
            if (camera?.cameraInfo?.hasFlashUnit() != true) R.string.flash_unavailable
            else when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                else -> R.string.flash_off
            }
        )
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateControls()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        updateControls()
        shutterOverlay.animate().cancel()
        shutterOverlay.alpha = 1f
        shutterOverlay.animate().alpha(0f).setStartDelay(20).setDuration(50).start()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "WCamera_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WCamera")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(output, uiExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                finishCapture()
                val uri = result.savedUri
                if (uri == null) {
                    Log.e(TAG, "CameraX saved a photo without returning its URI")
                    if (!isFinishing && !isDestroyed) {
                        toast(R.string.photo_save_failed)
                    }
                    return
                }
                preferences.edit().putString(LATEST_PHOTO_KEY, uri.toString()).apply()
            }

            override fun onError(error: ImageCaptureException) {
                Log.e(TAG, "Unable to save photo", error)
                finishCapture()
                if (isFinishing || isDestroyed) return
                toast(R.string.photo_save_failed)
            }
        })
    }

    private fun syncLatestPhoto() {
        val savedPhoto = preferences.getString(LATEST_PHOTO_KEY, null)?.let(Uri::parse)
        if (savedPhoto == latestPhoto) return
        latestPhoto = savedPhoto
        if (savedPhoto == null) {
            thumbnailVersion++
            thumbnailCancellation?.cancel()
            thumbnailCancellation = null
            thumbnailButton.setImageDrawable(null)
            thumbnailButton.contentDescription = getString(R.string.no_photo)
            thumbnailButton.isEnabled = false
        } else {
            loadThumbnail(savedPhoto)
        }
    }

    private fun finishCapture() {
        capturing = false
        if (isFinishing || isDestroyed) return
        if (viewportUpdatePending) {
            viewportUpdatePending = false
            bindCamera()
        } else {
            updateControls()
        }
    }

    private fun loadThumbnail(uri: Uri) {
        val version = ++thumbnailVersion
        thumbnailCancellation?.cancel()
        val cancellation = CancellationSignal()
        thumbnailCancellation = cancellation
        thumbnailButton.isEnabled = false
        ioExecutor.execute {
            try {
                if (cancellation.isCanceled) return@execute
                val bitmap: Bitmap = contentResolver.loadThumbnail(uri, Size(256, 256), cancellation)
                uiExecutor.execute {
                    if (!isDestroyed && !cancellation.isCanceled && version == thumbnailVersion) {
                        thumbnailButton.setImageBitmap(bitmap)
                        thumbnailButton.contentDescription = getString(R.string.recent_photo)
                        thumbnailButton.isEnabled = true
                        thumbnailCancellation = null
                    } else {
                        bitmap.recycle()
                    }
                }
            } catch (e: OperationCanceledException) {
                if (!cancellation.isCanceled) handleThumbnailError(uri, version, e)
            } catch (e: IOException) {
                if (!cancellation.isCanceled) handleThumbnailError(uri, version, e)
            } catch (e: SecurityException) {
                if (!cancellation.isCanceled) handleThumbnailError(uri, version, e)
            }
        }
    }

    private fun handleThumbnailError(uri: Uri, version: Int, error: Exception) {
        Log.e(TAG, "Unable to load photo thumbnail: $uri", error)
        uiExecutor.execute {
            if (!isDestroyed && version == thumbnailVersion) {
                thumbnailCancellation = null
                thumbnailButton.setImageResource(android.R.drawable.ic_menu_gallery)
                thumbnailButton.contentDescription = getString(R.string.recent_photo)
                thumbnailButton.isEnabled = true
                toast(R.string.thumbnail_unavailable)
            }
        }
    }

    private fun openLatestPhoto() {
        val uri = latestPhoto ?: return
        startActivity(Intent(this, PhotoViewerActivity::class.java).setData(uri))
    }

    private fun setupGestures() {
        var scaled = false
        var downX = 0f
        var downY = 0f
        var targetZoomRatio = 1f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaled = true
                targetZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val activeCamera = camera ?: return false
                val zoom = activeCamera.cameraInfo.zoomState.value ?: return false
                targetZoomRatio = (targetZoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                val future = activeCamera.cameraControl.setZoomRatio(targetZoomRatio)
                future.addListener({
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        if (e.cause !is CameraControl.OperationCanceledException) {
                            Log.e(TAG, "Unable to zoom", e)
                            toast(R.string.zoom_failed)
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.e(TAG, "Zoom interrupted", e)
                    }
                }, uiExecutor)
                return true
            }
        })
        previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    scaled = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> scaled = true
                MotionEvent.ACTION_UP -> {
                    if (!scaled && abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                        view.performClick()
                        focusAt(event.x, event.y)
                    }
                }
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        val params = focusIndicator.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = (x - params.width / 2f).toInt()
        params.topMargin = (y - params.height / 2f).toInt()
        focusIndicator.layoutParams = params
        focusIndicator.visibility = View.VISIBLE
        focusIndicator.removeCallbacks(hideFocusIndicator)
        focusIndicator.postDelayed(hideFocusIndicator, 1500)
        val future = activeCamera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                if (!future.get().isFocusSuccessful) toast(R.string.focus_failed)
            } catch (e: ExecutionException) {
                if (e.cause !is CameraControl.OperationCanceledException) {
                    Log.e(TAG, "Unable to focus", e)
                    toast(R.string.focus_failed)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "Focus interrupted", e)
            }
        }, uiExecutor)
    }

    private val hideFocusIndicator = Runnable { focusIndicator.visibility = View.GONE }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "WCamera"
        private const val LATEST_PHOTO_KEY = "latest_photo"
        private const val STATE_LENS_FACING = "lens_facing"
        private const val STATE_FLASH_MODE = "flash_mode"
    }
}
