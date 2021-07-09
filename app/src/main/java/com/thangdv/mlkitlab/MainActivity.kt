package com.thangdv.mlkitlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.ExperimentalTime


@InternalCoroutinesApi
@ExperimentalTime
class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var rootView: View
    private lateinit var previewView: PreviewView
    private lateinit var faceFrame: ImageView
    private lateinit var recordBtn: Button
    private lateinit var prepareRecord: TextView
    private var faceFrameRect: Rect = Rect()
    private var rootRect: Rect = Rect()

    private var imageProcessor: VisionImageProcessor? = null

    private var graphicOverlay: GraphicOverlay? = null
    private var needUpdateGraphicOverlayImageSourceInfo = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val outputFile: File by lazy { createFile(applicationContext) }

    private val videoCapture: VideoCapture by lazy {
        createVideoCapture()
    }

    private val imageAnalysis = ImageAnalysis.Builder()
        .build()

    private val cameraSelector =
        CameraSelector.Builder().requireLensFacing(lensFacing).build()

    private var isDetected = false

    private var isRecording = false

    private var videoDurationCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root)
        previewView = findViewById(R.id.preview_view)
        graphicOverlay = findViewById(R.id.graphic_overlay)
        faceFrame = findViewById(R.id.face_frame)
        recordBtn = findViewById(R.id.record)
        prepareRecord = findViewById(R.id.prepare_record)
        recordBtn.isEnabled = false

        // Request camera permissions
        if (allPermissionsGranted()) {
            startPreview()
            startDetect()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        startRepeatingJob()

        recordBtn.setOnClickListener {
            if (!isRecording) {
                ProcessCameraProvider.getInstance(this).get().unbind(imageAnalysis)
                isRecording = true
                recordBtn.text = if (!isRecording) "Record video" else ""

                faceFrame.visibility = View.INVISIBLE

                prepareRecord.visibility = View.VISIBLE

                var preTime = 3
                prepareRecord.text = preTime.toString()

                GlobalScope.launch {
                    repeat(3) {
                        delay(1_000)
                        withContext(Dispatchers.Main) {
                            preTime--
                            prepareRecord.text = preTime.toString()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        prepareRecord.visibility = View.GONE
                        startRecord()
                        startCountTime()
                    }

                }
            }
        }
    }

    private fun startCountTime() {
        setMicMuted(true)
        videoDurationCounter = 30
        recordBtn.text = "$videoDurationCounter"

        GlobalScope.launch {
            repeat(30) {
                delay(1_000)
                withContext(Dispatchers.Main) {
                    videoDurationCounter--
                    recordBtn.text = "$videoDurationCounter"
                }
            }
            delay(2_00)
            withContext(Dispatchers.Main) {
                recordBtn.text = "Record video"
                isRecording = false
                stopRecording()
                setMicMuted(false)
                faceFrame.visibility = View.VISIBLE
                isDetected = false

                ProcessCameraProvider.getInstance(this@MainActivity).get().unbind(videoCapture)
                startDetect()
            }
        }
    }

    private fun showCompleteDialog() {
        val fileSize = outputFile.length() / 1024
        Log.i(TAG, "Video size = $fileSize KB | ${fileSize / 1024} MB")
        AlertDialog.Builder(this@MainActivity)
            .setTitle("Record video complete")
            .setMessage("File info:\nFile size: $fileSize KB | ${fileSize / 1024} MB")
            .setCancelable(false)
            .setPositiveButton("Play") { _, _ ->
                // Launch external activity via intent to play video recorded using our provider
                startActivity(Intent().apply {
                    action = Intent.ACTION_VIEW
                    type = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(outputFile.extension)
                    val authority = "${BuildConfig.APPLICATION_ID}.provider"
                    data = FileProvider.getUriForFile(this@MainActivity, authority, outputFile)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                })
            }.show()
    }

    private fun startRepeatingJob(): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                withContext(Dispatchers.Main) {
                    if (isDetected) {
                        faceFrame.post { faceFrame.setColorFilter(Color.BLUE) }
                        recordBtn.isEnabled = true
                    } else {
                        faceFrame.post { faceFrame.setColorFilter(Color.WHITE) }
                        recordBtn.isEnabled = false
                    }
                }
                delay(300)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        faceFrame.post {
            faceFrame.getGlobalVisibleRect(faceFrameRect)
            rootView.getGlobalVisibleRect(rootRect)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun startRecord() {
        ProcessCameraProvider.getInstance(this).get().unbind(imageAnalysis)
        ProcessCameraProvider.getInstance(this).get()
            .bindToLifecycle(this, cameraSelector, videoCapture)
        val outputOptions = VideoCapture.OutputFileOptions.Builder(outputFile).build()

        videoCapture.startRecording(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : VideoCapture.OnVideoSavedCallback {
                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    Log.e(TAG, "Video capture failed: $message")
                }

                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(outputFile)
                    val msg = "Video capture succeeded: $savedUri"
//                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()

                    // Broadcasts the media file to the rest of the system
                    MediaScannerConnection.scanFile(
                        application, arrayOf(outputFile.absolutePath), null, null
                    )

                    showCompleteDialog();

                    readMetaData()
                    Log.d(TAG, msg)
                }
            })
    }

    @SuppressLint("RestrictedApi")
    fun createVideoCapture(): VideoCapture = VideoCapture.Builder()
        .setVideoFrameRate(30)
        .setMaxResolution(Size(1280, 720))
        .setBitRate(2000 * 1024)
        .setAudioChannelCount(0)
        .setAudioBitRate(0)
        .setAudioMinBufferSize(0)
        .setAudioSampleRate(0)
        .build()

    fun readMetaData() {
        if (outputFile.exists()) {
            Log.i(TAG, ".mp4 file Exist")

            //Added in API level 10
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(outputFile.absolutePath)
                for (i in 0..999) {
                    //only Metadata != null is printed!
                    if (retriever.extractMetadata(i) != null) {
                        Log.i(TAG, "Metadata :: " + retriever.extractMetadata(i))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception : " + e.message)
            }
        } else {
            Log.e(TAG, ".mp4 file doesn´t exist.")
        }
    }

    @SuppressLint("RestrictedApi")
    private fun stopRecording() {
        videoCapture.stopRecording()
    }

    private fun setMicMuted(state: Boolean) {
        val myAudioManager = application.getSystemService(AUDIO_SERVICE) as AudioManager

        // get the working mode and keep it
        val workingAudioMode = myAudioManager.mode
        myAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // change mic state only if needed
        if (myAudioManager.isMicrophoneMute != state) {
            myAudioManager.isMicrophoneMute = state
        }

        // set back the original working mode
        myAudioManager.mode = workingAudioMode
    }

    private fun startPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        cameraProvider.bindToLifecycle(this, cameraSelector, preview)
    }

    private fun startDetect() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

        if (imageProcessor != null) {
            imageProcessor!!.stop()
        }

        val optionsBuilder = FaceDetectorOptions.Builder()
        optionsBuilder.setMinFaceSize(0.5f)

        imageProcessor =
            FaceDetectorProcessor(this, optionsBuilder.build()) { faces, graphicOverlay ->
                faces.forEach { face ->
                    val faceRect = face.boundingBox

                    val x = graphicOverlay.translateX(
                        graphicOverlay,
                        face.boundingBox.centerX().toFloat()
                    )
                    val y = graphicOverlay.translateY(
                        graphicOverlay,
                        face.boundingBox.centerY().toFloat()
                    )

                    val left =
                        x - graphicOverlay.scale(graphicOverlay, faceRect.width() / 2.0f).toInt()
                    val top =
                        y - graphicOverlay.scale(graphicOverlay, faceRect.height() / 2.0f).toInt()
                    val right =
                        x + graphicOverlay.scale(graphicOverlay, faceRect.width() / 2.0f).toInt()
                    val bottom =
                        y + graphicOverlay.scale(graphicOverlay, faceRect.height() / 2.0f).toInt()

                    isDetected = (faceFrameRect.top < top + 50
                            && faceFrameRect.left < left + 50
                            && faceFrameRect.bottom > bottom - 50
                            && faceFrameRect.right > right - 50)
                    Log.d("thang_", isDetected.toString())
                }
            }

        needUpdateGraphicOverlayImageSourceInfo = true

        imageAnalysis.setAnalyzer(
            // imageProcessor.processImageProxy will use another thread to run the detection underneath,
            // thus we can just runs the analyzer itself on main thread.
            ContextCompat.getMainExecutor(this),
            { imageProxy: ImageProxy ->
                if (needUpdateGraphicOverlayImageSourceInfo) {
                    val isImageFlipped =
                        lensFacing == CameraSelector.LENS_FACING_FRONT
                    val rotationDegrees =
                        imageProxy.imageInfo.rotationDegrees
                    if (rotationDegrees == 0 || rotationDegrees == 180) {
                        graphicOverlay!!.setImageSourceInfo(
                            imageProxy.width, imageProxy.height, isImageFlipped
                        )
                    } else {
                        graphicOverlay!!.setImageSourceInfo(
                            imageProxy.height, imageProxy.width, isImageFlipped
                        )
                    }
                    needUpdateGraphicOverlayImageSourceInfo = false
                }
                try {
                    imageProcessor!!.processImageProxy(imageProxy, graphicOverlay)
                } catch (e: MlKitException) {
                    Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
                    Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        )

        // Bind use cases to camera
        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()

        imageProcessor?.run {
            this.stop()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startPreview()
                startDetect()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String = "mp4"): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
    }
}
