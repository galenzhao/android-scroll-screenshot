package com.galenzhao.scrollshot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.galenzhao.scrollshot.CaptureRepository
import com.galenzhao.scrollshot.MainActivity
import com.galenzhao.scrollshot.R
import com.galenzhao.scrollshot.capture.FrameCaptureManager
import com.galenzhao.scrollshot.capture.StopOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        /** 顶部裁剪高度（px）。<0 表示使用系统状态栏高度；>=0 表示使用该值（可含状态栏+浮动按钮等） */
        const val EXTRA_TOP_CROP_HEIGHT_PX = "extra_top_crop_height_px"
        /** 底部裁剪高度（px）。<=0 表示不裁剪；用于目标 App 自带的、不随内容滚动的底部 Tab 栏等 */
        const val EXTRA_BOTTOM_CROP_HEIGHT_PX = "extra_bottom_crop_height_px"
        const val ACTION_STOP = "action_stop"
        const val ACTION_BEGIN = "action_begin_capture"
        // 延迟开始捕获，给系统动画/通知栏收起留足时间
        private const val BEGIN_DELAY_MS = 2000L
        // 使用新的渠道 ID，避免之前被系统默认为静默通知
        private const val CHANNEL_ID = "scroll_shot_channel_v2"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"
    }

    private var mediaProjection: MediaProjection? = null
    private var captureManager: FrameCaptureManager? = null
    private var hasStartedCapture: Boolean = false
    /** 截图过程中常驻的悬浮"停止"按钮，避免必须下拉通知栏才能停止（见类注释） */
    private val stopOverlay by lazy { StopOverlayController(this) }
    /** 用户指定的顶部裁剪高度（px），-1 表示未指定、使用系统状态栏 */
    private var topCropHeightPx: Int = -1
    /** 用户指定的底部裁剪高度（px），<=0 表示不裁剪 */
    private var bottomCropHeightPx: Int = -1
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection onStop, will processAndStop()")
            processAndStop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() flags=$flags startId=$startId intent=$intent action=${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Received ACTION_STOP, calling processAndStop()")
            processAndStop()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_BEGIN) {
            Log.d(TAG, "Received ACTION_BEGIN, will start capture after delay=${BEGIN_DELAY_MS}ms")
            serviceScope.launch {
                delay(BEGIN_DELAY_MS)
                beginCapture()
            }
            return START_NOT_STICKY
        }

        // 先尽快进入前台，满足系统时限要求
        val notification = buildNotification()
        Log.d(TAG, "Calling startForeground() with notification=$notification")
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        Log.d(TAG, "startForeground() called successfully")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK) ?: Activity.RESULT_OK
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        topCropHeightPx = intent?.getIntExtra(EXTRA_TOP_CROP_HEIGHT_PX, -1) ?: -1
        bottomCropHeightPx = intent?.getIntExtra(EXTRA_BOTTOM_CROP_HEIGHT_PX, -1) ?: -1

        // 这里不再校验 resultCode 是否等于 -1，
        // 因为 Activity.RESULT_OK 本身就是 -1，直接允许通过，只要 data 不为 null 即可
        if (resultData == null) {
            Log.e(TAG, "Invalid resultData=null, stopping self")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        Log.d(TAG, "MediaProjection obtained: $mediaProjection")
        mediaProjection!!.registerCallback(projectionCallback, null)

        // 这里只是完成 MediaProjection 的准备，不立刻开始捕获，
        // 等用户切换到目标 App 后，从通知栏点击“开始捕获”再真正启动
        hasStartedCapture = false
        // 更新一次通知文案，让用户知道需要从通知栏开始
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())

        return START_NOT_STICKY
    }

    private fun processAndStop() {
        Log.d(TAG, "processAndStop() called, current state=${CaptureRepository.state.value}")
        if (CaptureRepository.state.value is CaptureRepository.State.Processing) return

        // 用户点击“停止并处理”后，先立刻停止屏幕捕获和录屏指示，再在已有帧上做拼接处理。
        try {
            captureManager?.stopCaptureOnly()
        } catch (e: Exception) {
            Log.e(TAG, "Error when stopping captureOnly", e)
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error when unregistering projection callback", e)
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error when stopping MediaProjection", e)
        } finally {
            mediaProjection = null
        }

        CaptureRepository.updateState(CaptureRepository.State.Processing)

        serviceScope.launch {
            try {
                val result = captureManager?.buildResult()
                if (result != null) {
                    CaptureRepository.updateState(CaptureRepository.State.Completed(result))
                } else {
                    CaptureRepository.updateState(
                        CaptureRepository.State.Error(getString(R.string.error_no_content))
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while processing result", e)
                CaptureRepository.updateState(
                    CaptureRepository.State.Error(
                        e.message ?: getString(R.string.error_processing_failed)
                    )
                )
            } finally {
                Log.d(TAG, "Processing finished, cleaning up and stopping service")
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup()")
        stopOverlay.hide()
        captureManager?.release()
        captureManager = null
        hasStartedCapture = false
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        serviceScope.cancel()
        cleanup()
    }

    private fun beginCapture() {
        if (hasStartedCapture) {
            Log.d(TAG, "beginCapture() called but already started, ignore")
            return
        }
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "beginCapture() called but mediaProjection is null")
            return
        }

        captureManager = FrameCaptureManager(
            this,
            projection,
            topCropHeightPx = if (topCropHeightPx >= 0) topCropHeightPx else null,
            bottomCropHeightPx = if (bottomCropHeightPx > 0) bottomCropHeightPx else null
        )
        Log.d(TAG, "FrameCaptureManager created in beginCapture(), calling start()")
        captureManager!!.start()
        Log.d(TAG, "FrameCaptureManager.start() returned in beginCapture()")

        hasStartedCapture = true
        CaptureRepository.updateState(CaptureRepository.State.Capturing(0))
        Log.d(TAG, "State updated to Capturing(0) in beginCapture()")

        // 更新通知文案为“正在运行”
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())

        // 悬浮停止按钮：未授予「显示在其他应用上层」权限时会静默跳过，不影响通知栏停止方式
        stopOverlay.show { processAndStop() }
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel() with id=$CHANNEL_ID")
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.notif_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val beginIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_BEGIN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainIntent)
            .setOngoing(true)

        if (!hasStartedCapture) {
            builder
                .setContentTitle(getString(R.string.notif_ready_title))
                .setContentText(getString(R.string.notif_ready_text))
                .addAction(0, getString(R.string.notif_action_begin), beginIntent)
                .addAction(0, getString(R.string.notif_action_cancel), stopIntent)
        } else {
            builder
                .setContentTitle(getString(R.string.notif_running_title))
                .setContentText(getString(R.string.notif_running_text))
                .addAction(0, getString(R.string.notif_action_stop_and_process), stopIntent)
        }

        return builder.build()
    }
}
