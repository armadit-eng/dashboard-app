package com.dashboard.app.dashboard_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 8041
        private const val BT_METHOD_CHANNEL = "com.dashboard.app/bt_classic"
        private const val BT_EVENT_CHANNEL = "com.dashboard.app/bt_classic_events"
        private const val BLE_METHOD_CHANNEL = "com.dashboard.app/ble_gatt"
        private const val BLE_EVENT_CHANNEL = "com.dashboard.app/ble_gatt_events"
        private const val MIRROR_METHOD_CHANNEL = "com.dashboard.app/screen_mirror"
        private const val MIRROR_EVENT_CHANNEL = "com.dashboard.app/screen_mirror_events"
        private const val TOOLS_METHOD_CHANNEL = "com.dashboard.app/platform_tools"
        private const val GPS_EVENT_CHANNEL = "com.dashboard.app/gps_events"
        private const val CAM_METHOD_CHANNEL = "com.dashboard.app/camera_controls"
        private const val CAM_VIEW_TYPE = "com.dashboard.app/camera_preview"
    }

    private lateinit var btClassicBridge: BtClassicBridge
    private lateinit var bleGattBridge: BleGattBridge
    private lateinit var platformToolsBridge: PlatformToolsBridge
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mirrorEventSink: EventChannel.EventSink? = null
    private var pendingMirrorResult: MethodChannel.Result? = null
    private var pendingMirrorArgs: Map<String, Any?> = emptyMap()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        btClassicBridge = BtClassicBridge(applicationContext)
        MethodChannel(messenger, BT_METHOD_CHANNEL).setMethodCallHandler(btClassicBridge)
        EventChannel(messenger, BT_EVENT_CHANNEL).setStreamHandler(btClassicBridge)

        bleGattBridge = BleGattBridge(applicationContext)
        MethodChannel(messenger, BLE_METHOD_CHANNEL).setMethodCallHandler(bleGattBridge)
        EventChannel(messenger, BLE_EVENT_CHANNEL).setStreamHandler(bleGattBridge)

        platformToolsBridge = PlatformToolsBridge(this)
        MethodChannel(messenger, TOOLS_METHOD_CHANNEL).setMethodCallHandler(platformToolsBridge)
        EventChannel(messenger, GPS_EVENT_CHANNEL).setStreamHandler(platformToolsBridge)

        val camChannel = MethodChannel(messenger, CAM_METHOD_CHANNEL)
        flutterEngine.platformViewsController.registry.registerViewFactory(
            CAM_VIEW_TYPE,
            MotovlogCameraViewFactory(camChannel)
        )

        EventChannel(messenger, MIRROR_EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    mirrorEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    mirrorEventSink = null
                }
            })

        ScreenMirrorService.onFrameReady = { b64 ->
            mainHandler.post {
                mirrorEventSink?.success(mapOf("type" to "frame", "base64" to b64))
            }
        }
        ScreenMirrorService.onMirrorStateChanged = { running ->
            mainHandler.post {
                mirrorEventSink?.success(mapOf("type" to "state", "running" to running))
            }
        }

        MethodChannel(messenger, MIRROR_METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isRunning" -> result.success(ScreenMirrorService.isRunning)
                    "startMirror" -> {
                        pendingMirrorResult = result
                        pendingMirrorArgs = mapOf(
                            "width" to (call.argument<Int>("width") ?: 240),
                            "height" to (call.argument<Int>("height") ?: 180),
                            "quality" to (call.argument<Int>("quality") ?: 55),
                            "intervalMs" to (call.argument<Int>("intervalMs") ?: 450)
                        )
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        @Suppress("DEPRECATION")
                        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
                    }
                    "stopMirror" -> {
                        val stopIntent = Intent(this, ScreenMirrorService::class.java).apply {
                            action = ScreenMirrorService.ACTION_STOP
                        }
                        startService(stopIntent)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        platformToolsBridge.onRequestPermissionsResult(requestCode)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        platformToolsBridge.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            val res = pendingMirrorResult
            pendingMirrorResult = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                val svcIntent = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_START
                    putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenMirrorService.EXTRA_DATA, data)
                    putExtra(ScreenMirrorService.EXTRA_WIDTH, (pendingMirrorArgs["width"] as? Int) ?: 240)
                    putExtra(ScreenMirrorService.EXTRA_HEIGHT, (pendingMirrorArgs["height"] as? Int) ?: 180)
                    putExtra(ScreenMirrorService.EXTRA_QUALITY, (pendingMirrorArgs["quality"] as? Int) ?: 55)
                    putExtra(
                        ScreenMirrorService.EXTRA_INTERVAL_MS,
                        ((pendingMirrorArgs["intervalMs"] as? Int) ?: 450).toLong()
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
                res?.success(true)
            } else {
                res?.success(false)
            }
        }
    }

    override fun onDestroy() {
        platformToolsBridge.dispose()
        super.onDestroy()
    }
}
