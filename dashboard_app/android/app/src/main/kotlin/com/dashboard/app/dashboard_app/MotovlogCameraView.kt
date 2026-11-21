package com.dashboard.app.dashboard_app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.View
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.File

class MotovlogCameraViewFactory(private val channel: MethodChannel) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    var activeView: MotovlogCameraView? = null

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val view = MotovlogCameraView(context)
        activeView = view
        channel.setMethodCallHandler(view)
        return view
    }
}

class MotovlogCameraView(private val context: Context) :
    PlatformView, MethodChannel.MethodCallHandler, TextureView.SurfaceTextureListener {

    private val textureView = TextureView(context).apply {
        surfaceTextureListener = this@MotovlogCameraView
    }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var currentVideoPath: String? = null
    private var recording = false

    override fun getView(): View = textureView

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
    private fun openCamera() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        startBgThread()
        try {
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return

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
                }
            }, bgHandler)
        } catch (_: Exception) {
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

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecording" -> {
                val ok = startRecording()
                result.success(ok)
            }
            "stopRecording" -> {
                val path = stopRecording()
                result.success(path)
            }
            else -> result.notImplemented()
        }
    }

    private fun startRecording(): Boolean {
        val cam = cameraDevice ?: return false
        val tex = textureView.surfaceTexture ?: return false
        if (recording) return true
        val hasAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return try {
            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            val outFile = File(outDir, "motovlog_${System.currentTimeMillis()}.mp4")
            currentVideoPath = outFile.absolutePath

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            if (hasAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(outFile.absolutePath)
            mr.setVideoEncodingBitRate(4_500_000)
            mr.setVideoFrameRate(30)
            mr.setVideoSize(1280, 720)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (hasAudio) mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
                        recording = true
                    } catch (_: Exception) {
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, bgHandler)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopRecording(): String? {
        if (!recording) return null
        recording = false
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        startPreview()
        return currentVideoPath
    }

    private fun closeCamera() {
        stopRecording()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    override fun dispose() {
        closeCamera()
    }
}
