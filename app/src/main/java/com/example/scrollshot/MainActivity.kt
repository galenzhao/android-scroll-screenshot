package com.galenzhao.scrollshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.galenzhao.scrollshot.databinding.ActivityMainBinding
import com.galenzhao.scrollshot.service.ScreenCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    /** 避免"已达最大长度"提示在每次状态刷新时重复弹出 */
    private var maxHeightWarningShown = false

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "scrollshot_prefs"
        private const val PREF_OVERLAY_ASKED = "overlay_permission_asked"
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "screenCaptureLauncher resultCode=${result.resultCode} data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, getString(R.string.toast_screen_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "notificationPermLauncher granted=$granted")
        if (granted) requestScreenCapture()
        else Toast.makeText(this, getString(R.string.toast_notification_required), Toast.LENGTH_LONG).show()
    }

    /**
     * 悬浮"停止"按钮权限（可选）：只在从未问过时主动引导一次，不管用户最终是否授权，
     * 都会继续走后续流程——授权与否不影响核心截图功能，只是有没有悬浮停止按钮的区别。
     */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "overlayPermissionLauncher returned, canDrawOverlays=${Settings.canDrawOverlays(this)}")
        checkNotificationPermissionAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.btnStartCapture.setOnClickListener { checkPermissionAndStart() }
        binding.btnStopCapture.setOnClickListener { stopCaptureService() }

        observeState()
    }

    private fun applyWindowInsets() {
        val headerPadTop = binding.headerLayout.paddingTop
        val buttonBottomMargin =
            (binding.btnStartCapture.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerLayout.updatePadding(top = headerPadTop + insets.top)
            val bottomMargin = buttonBottomMargin + insets.bottom
            binding.btnStartCapture.updateLayoutParams<ConstraintLayout.LayoutParams> {
                this.bottomMargin = bottomMargin
            }
            binding.btnStopCapture.updateLayoutParams<ConstraintLayout.LayoutParams> {
                this.bottomMargin = bottomMargin
            }
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        // 如果已完成则导航到结果页
        val current = CaptureRepository.state.value
        if (current is CaptureRepository.State.Completed) navigateToResult()
    }

    private fun observeState() {
        lifecycleScope.launch {
            CaptureRepository.state.collect { state ->
                updateUI(state)
                if (state is CaptureRepository.State.Completed) navigateToResult()
            }
        }
    }

    private fun updateUI(state: CaptureRepository.State) {
        when (state) {
            is CaptureRepository.State.Idle -> {
                binding.btnStartCapture.isVisible = true
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_ready)
                binding.tvFrameCount.text = getString(R.string.status_hint_switch_and_scroll)
                maxHeightWarningShown = false
            }
            is CaptureRepository.State.Capturing -> {
                binding.btnStartCapture.isVisible = false
                binding.btnStopCapture.isVisible = true
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_capturing)
                if (state.reachedLimit) {
                    binding.tvFrameCount.text = getString(R.string.status_capturing_reached_limit, state.frameCount)
                    if (!maxHeightWarningShown) {
                        maxHeightWarningShown = true
                        Toast.makeText(this, getString(R.string.toast_reached_max_height), Toast.LENGTH_LONG).show()
                    }
                } else {
                    binding.tvFrameCount.text = getString(R.string.status_capturing_count, state.frameCount)
                }
            }
            is CaptureRepository.State.Processing -> {
                binding.btnStartCapture.isVisible = false
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = true
                binding.tvStatus.text = getString(R.string.status_processing)
                binding.tvFrameCount.text = getString(R.string.status_processing_hint)
            }
            is CaptureRepository.State.Completed -> {
                binding.progressBar.isVisible = false
            }
            is CaptureRepository.State.Error -> {
                binding.btnStartCapture.isVisible = true
                binding.btnStopCapture.isVisible = false
                binding.progressBar.isVisible = false
                binding.tvStatus.text = getString(R.string.status_error)
                binding.tvFrameCount.text = state.message
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissionAndStart() {
        Log.d(TAG, "checkPermissionAndStart()")
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val overlayAlreadyAsked = prefs.getBoolean(PREF_OVERLAY_ASKED, false)
        if (!Settings.canDrawOverlays(this) && !overlayAlreadyAsked) {
            // 只主动引导一次：悬浮停止按钮是可选功能，不想每次开始截图都打断用户
            prefs.edit().putBoolean(PREF_OVERLAY_ASKED, true).apply()
            Log.d(TAG, "Overlay permission not granted, requesting once (optional, for floating stop button)")
            Toast.makeText(this, getString(R.string.toast_overlay_permission_hint), Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            checkNotificationPermissionAndStart()
        }
    }

    private fun checkNotificationPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted, requesting")
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Log.d(TAG, "POST_NOTIFICATIONS already granted, requestScreenCapture()")
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "requestScreenCapture() createScreenCaptureIntent")
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCaptureService() resultCode=$resultCode data=$data")
        val topCropStr = binding.etTopCropHeight.text?.toString()?.trim()
        val topCropPx = topCropStr?.toIntOrNull()
        val bottomCropStr = binding.etBottomCropHeight.text?.toString()?.trim()
        val bottomCropPx = bottomCropStr?.toIntOrNull()

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_TOP_CROP_HEIGHT_PX, topCropPx ?: -1)
            putExtra(ScreenCaptureService.EXTRA_BOTTOM_CROP_HEIGHT_PX, bottomCropPx ?: -1)
        }
        Log.d(TAG, "Calling startForegroundService() with intent=$intent topCropPx=$topCropPx bottomCropPx=$bottomCropPx")
        startForegroundService(intent)
    }

    private fun stopCaptureService() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
    }

    private fun navigateToResult() {
        startActivity(Intent(this, ResultActivity::class.java))
    }
}
