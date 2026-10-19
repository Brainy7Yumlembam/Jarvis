package co.aura.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dedicated in-app CameraX capture screen for JARVIS.
 * Provides:
 * - Live camera preview with JARVIS holographic optical HUD
 * - Manual shutter button & voice-controlled auto-capture
 * - Shutter sound & TTS confirmation upon actual file write
 * - Safe image storage in MediaStore (Pictures/JARVIS) or app-owned storage
 * - Debounced shutter protection against repeated shots
 */
class JarvisCameraActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var statusTextView: TextView? = null
    private var shutterButton: View? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaActionSound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    @Volatile
    private var isCapturing = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AuraLogger.i(LogCategory.ACTION, "[CAMERA_ACTIVITY] Camera permission granted by user.")
            startCamera()
        } else {
            AuraLogger.w(LogCategory.ACTION, "[CAMERA_ACTIVITY] Camera permission was denied.")
            speakAndToast("Camera permission was denied, sir.")
            mainHandler.postDelayed({ finish() }, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        initTts()

        // Build HUD UI programmatically
        val rootLayout = buildCameraHudLayout()
        setContentView(rootLayout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            }
        }
    }

    private fun speakAndToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (isTtsReady) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_CAMERA_TTS")
        }
    }

    private fun buildCameraHudLayout(): FrameLayout {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Camera Preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

        // Holographic HUD Header
        val header = TextView(this).apply {
            text = "J.A.R.V.I.S. OPTICAL CAPTURE // CAMERA ACTIVE"
            setTextColor(Color.parseColor("#00E5FF")) // Holographic Cyan
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        }
        root.addView(header)

        // Status Indicator
        statusTextView = TextView(this).apply {
            text = "STANDING BY"
            setTextColor(Color.parseColor("#FFD54F")) // Amber
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply {
                bottomMargin = 240
            }
        }
        root.addView(statusTextView)

        // Shutter Button
        val buttonSize = (72 * resources.displayMetrics.density).toInt()
        shutterButton = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                buttonSize,
                buttonSize,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply {
                bottomMargin = 60
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((4 * resources.displayMetrics.density).toInt(), Color.parseColor("#00E5FF"))
            }
            setOnClickListener {
                if (!isCapturing) {
                    takePhoto()
                }
            }
        }
        root.addView(shutterButton)

        return root
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView?.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                AuraLogger.i(LogCategory.ACTION, "[CAMERA_ACTIVITY] Camera bound to lifecycle successfully.")
                statusTextView?.text = "OPTICAL SENSOR READY"

                // Check for voice-triggered auto capture
                val isAutoCapture = intent.getBooleanExtra("EXTRA_AUTO_CAPTURE", false)
                if (isAutoCapture) {
                    statusTextView?.text = "FOCUSING & CAPTURING..."
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            takePhoto()
                        }
                    }, 800L)
                }
            } catch (e: Exception) {
                AuraLogger.e(LogCategory.ACTION, "[CAMERA_ACTIVITY] Use case binding failed", e)
                speakAndToast("Failed to initialize camera, sir.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            AuraLogger.w(LogCategory.ACTION, "[CAMERA_ACTIVITY] ImageCapture is not ready yet.")
            return
        }

        if (isCapturing) return
        isCapturing = true

        statusTextView?.text = "CAPTURING..."
        mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "JARVIS_$timeStamp.jpg"

        val outputOptions: ImageCapture.OutputFileOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
            }
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else {
            val picturesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: filesDir
            val jarvisDir = File(picturesDir, "JARVIS").apply { if (!exists()) mkdirs() }
            val photoFile = File(jarvisDir, fileName)
            ImageCapture.OutputFileOptions.Builder(photoFile).build()
        }

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    AuraLogger.i(LogCategory.ACTION, "[CAMERA_ACTIVITY] Photo saved successfully: $savedUri")

                    mainHandler.post {
                        statusTextView?.text = "PHOTO CAPTURED & SAVED"
                        speakAndToast("Photo captured and saved to gallery, sir.")
                        mainHandler.postDelayed({
                            finish()
                        }, 1200L)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    AuraLogger.e(LogCategory.ACTION, "[CAMERA_ACTIVITY] Photo capture failed: ${exception.message}", exception)
                    mainHandler.post {
                        isCapturing = false
                        statusTextView?.text = "CAPTURE FAILED"
                        speakAndToast("Photo capture failed, sir.")
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        mediaActionSound.release()
    }
}
