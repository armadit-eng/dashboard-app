package com.dashboard.app.dashboard_app.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideoResult(
    val displayName: String,
    val folderPath: String,
    val contentUri: Uri? = null,
    val filePath: String? = null,
    val savedToGallery: Boolean = false
) {
    val userReadableLocation: String
        get() = if (savedToGallery) {
            "Galeri ($folderPath/$displayName)"
        } else {
            filePath ?: "$folderPath/$displayName"
        }
}

object MotovlogMediaHelper {
    const val GALLERY_SUBFOLDER = "GesitsMotovlog"

    fun buildVideoFileName(timestampMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
        return "Motovlog_$stamp.mp4"
    }

    fun buildGalleryRelativePath(): String =
        "${Environment.DIRECTORY_MOVIES}/$GALLERY_SUBFOLDER"

    fun computeVideoOrientationHint(sensorOrientationDeg: Int, displayRotation: Int): Int {
        val deviceDeg = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientationDeg - deviceDeg + 360) % 360
    }
}

class MotovlogCameraController(private val context: Context) : TextureView.SurfaceTextureListener {
    val textureView: TextureView = TextureView(context).apply {
        surfaceTextureListener = this@MotovlogCameraController
    }

    var onError: ((String?) -> Unit)? = null
    var onRecordingChanged: ((Boolean) -> Unit)? = null

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var sensorOrientationDeg: Int = 90
    private var currentVideoFile: File? = null
    private var currentMediaUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentFileName: String = ""
    private var currentFolderLabel: String = ""
    private var currentIsGallery: Boolean = false

    @Volatile
    var isRecording: Boolean = false
        private set

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun startBgThread() {
        if (bgThread == null) {
            bgThread = HandlerThread("MotovlogCamThread").apply { start() }
            bgHandler = Handler(bgThread!!.looper)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("Izin kamera diperlukan untuk mode Motovlog HUD.")
            return
        }
        if (!textureView.isAvailable) return
        if (cameraDevice != null) return

        startBgThread()
        try {
            val ids = cameraManager.cameraIdList
            if (ids.isEmpty()) {
                onError?.invoke("Kamera tidak tersedia di perangkat ini.")
                return
            }
            val camId = ids.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.first()

            sensorOrientationDeg = cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            onError?.invoke(null)
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onError?.invoke("Gagal membuka kamera (kode $error).")
                }
            }, bgHandler)
        } catch (e: Exception) {
            onError?.invoke("Izin atau akses kamera ditolak: ${e.message}")
        }
    }

    private fun startPreview() {
        val cam = cameraDevice ?: return
        val tex = textureView.surfaceTexture ?: return
        tex.setDefaultBufferSize(1280, 720)
        val previewSurface = Surface(tex)
        try {
            captureSession?.close()
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(req.build(), null, bgHandler)
                    } catch (_: Exception) {
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, bgHandler)
        } catch (_: Exception) {
        }
    }

    private fun currentDisplayRotation(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
    }

    private fun cleanupPendingOutput() {
        runCatching { currentPfd?.close() }
        currentPfd = null
        val pendingUri = currentMediaUri
        currentMediaUri = null
        if (pendingUri != null) {
            runCatching { context.contentResolver.delete(pendingUri, null, null) }
        }
        currentVideoFile?.let { file ->
            if (file.exists() && file.length() == 0L) {
                runCatching { file.delete() }
            }
        }
        currentVideoFile = null
    }

    fun startRecording(): Boolean {
        val cam = cameraDevice ?: return false
        val tex = textureView.surfaceTexture ?: return false
        if (isRecording) return true
        val hasAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return try {
            val nowMs = System.currentTimeMillis()
            val fileName = MotovlogMediaHelper.buildVideoFileName(nowMs)
            currentFileName = fileName
            currentMediaUri = null
            currentPfd = null
            currentVideoFile = null
            currentIsGallery = false

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            if (hasAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relPath = "${Environment.DIRECTORY_MOVIES}/${MotovlogMediaHelper.GALLERY_SUBFOLDER}"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: throw IllegalStateException("Gagal membuat entry MediaStore Galeri")
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: run {
                        runCatching { resolver.delete(uri, null, null) }
                        throw IllegalStateException("Gagal membuka file descriptor Galeri")
                    }
                currentMediaUri = uri
                currentPfd = pfd
                currentFolderLabel = relPath
                currentIsGallery = true
                mr.setOutputFile(pfd.fileDescriptor)
            } else {
                @Suppress("DEPRECATION")
                val publicMovies = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    MotovlogMediaHelper.GALLERY_SUBFOLDER
                )
                val outDir = if ((publicMovies.exists() || publicMovies.mkdirs()) && publicMovies.canWrite()) {
                    currentIsGallery = true
                    currentFolderLabel = "${Environment.DIRECTORY_MOVIES}/${MotovlogMediaHelper.GALLERY_SUBFOLDER}"
                    publicMovies
                } else {
                    val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
                    fallbackDir.mkdirs()
                    currentIsGallery = false
                    currentFolderLabel = fallbackDir.absolutePath
                    fallbackDir
                }
                val outFile = File(outDir, fileName)
                currentVideoFile = outFile
                mr.setOutputFile(outFile.absolutePath)
            }

            mr.setVideoEncodingBitRate(4_500_000)
            mr.setVideoFrameRate(30)
            mr.setVideoSize(1280, 720)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (hasAudio) mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOrientationHint(
                MotovlogMediaHelper.computeVideoOrientationHint(sensorOrientationDeg, currentDisplayRotation())
            )
            mr.prepare()
            mediaRecorder = mr

            tex.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(tex)
            val recordSurface = mr.surface
            captureSession?.close()
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
            }
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(previewSurface, recordSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(req.build(), null, bgHandler)
                        mr.start()
                        isRecording = true
                        onRecordingChanged?.invoke(true)
                    } catch (_: Exception) {
                        runCatching { mr.reset() }
                        runCatching { mr.release() }
                        mediaRecorder = null
                        cleanupPendingOutput()
                        onError?.invoke("Gagal memulai perekaman kamera.")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runCatching { mr.reset() }
                    runCatching { mr.release() }
                    mediaRecorder = null
                    cleanupPendingOutput()
                    onError?.invoke("Konfigurasi sesi rekam kamera gagal.")
                }
            }, bgHandler)
            true
        } catch (_: Exception) {
            runCatching { mediaRecorder?.reset() }
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            cleanupPendingOutput()
            false
        }
    }

    fun stopRecording(): RecordedVideoResult? {
        if (!isRecording && mediaRecorder == null) return null
        val wasRecording = isRecording
        isRecording = false
        onRecordingChanged?.invoke(false)

        var stopSucceeded = false
        if (wasRecording) {
            stopSucceeded = runCatching {
                mediaRecorder?.stop()
                true
            }.getOrDefault(false)
        }
        runCatching {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        }
        mediaRecorder = null
        runCatching { currentPfd?.close() }
        currentPfd = null

        val savedUri = currentMediaUri
        val savedFile = currentVideoFile
        currentMediaUri = null
        currentVideoFile = null

        startPreview()

        if (!stopSucceeded) {
            if (savedUri != null) {
                runCatching { context.contentResolver.delete(savedUri, null, null) }
            }
            if (savedFile != null && savedFile.exists()) {
                runCatching { savedFile.delete() }
            }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
            val doneValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching {
                context.contentResolver.update(savedUri, doneValues, null, null)
            }
            return RecordedVideoResult(
                displayName = currentFileName,
                folderPath = currentFolderLabel,
                contentUri = savedUri,
                filePath = null,
                savedToGallery = true
            )
        }

        if (savedFile != null && savedFile.exists()) {
            if (currentIsGallery) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(savedFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            }
            return RecordedVideoResult(
                displayName = savedFile.name,
                folderPath = currentFolderLabel,
                contentUri = Uri.fromFile(savedFile),
                filePath = savedFile.absolutePath,
                savedToGallery = currentIsGallery
            )
        }

        return null
    }

    fun buildOpenVideoIntent(result: RecordedVideoResult): Intent {
        return if (result.contentUri != null && result.contentUri.scheme == "content") {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(result.contentUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
    }

    fun closeCamera() {
        stopRecording()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }
}
