package com.galenzhao.scrollshot.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 截图过程中常驻屏幕的悬浮"停止"按钮。
 *
 * 背景：停止截图原本只能通过下拉通知栏、点通知里的按钮完成。下拉通知栏这个动作本身也会被
 * MediaProjection 捕捉到，如果恰好在那几帧触发了滚动检测/底部条更新逻辑，通知栏或状态栏的
 * 画面就可能被当成"最后一帧"混进长图里。悬浮按钮让用户全程不需要离开目标 App、
 * 不需要碰通知栏，从根源上避免这个问题。
 *
 * 需要「显示在其他应用上层」（[Settings.canDrawOverlays]）权限，且该权限是可选的——
 * 调用方应在权限未授予时跳过 [show]，静默退回"仅通知栏可停止"的方式，不影响核心功能。
 */
class StopOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "StopOverlayController"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    /**
     * 权限已授予时才会真正显示；未授予时静默跳过，不影响调用方后续逻辑。
     *
     * [WindowManager.addView] 必须在有 Looper 的线程（通常是主线程）上调用，
     * 而调用方（[com.galenzhao.scrollshot.service.ScreenCaptureService.beginCapture]）
     * 是在 `Dispatchers.Default` 的后台线程里执行的，直接调用会抛异常。
     * 这里统一 post 到主线程执行，调用方不需要关心自己在哪个线程。
     */
    fun show(onStop: () -> Unit) {
        mainHandler.post { showInternal(onStop) }
    }

    private fun showInternal(onStop: () -> Unit) {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.d(TAG, "showInternal: overlay permission not granted, skip")
            return
        }

        // 尽量做小一点：占屏越小，万一落进内容区，对滚动检测的污染范围也越小
        val density = context.resources.displayMetrics.density
        val button = TextView(context).apply {
            text = "■ 停止"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.argb(230, 200, 40, 40))
            }
            elevation = 8 * density
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 悬浮按钮是真实画在屏幕上的系统窗口，MediaProjection 会把它和 App 内容一起录进去。
            // 如果按钮位置落在用户设置的"内容区"（裁剪高度以下）里，就会变成一个每帧都存在、
            // 但固定不动的"外来物"，污染滚动检测用的画面比对，导致大量误判——
            // 这和之前分析过的"吸顶头部反复出现"是同一类问题。
            // 这里把默认位置尽量贴近屏幕最顶端（只留一点点避开圆角/挖孔摄像头的边距），
            // 让它大概率落在状态栏范围内，被绝大多数人的顶部裁剪高度盖住；
            // 如果用户把顶部裁剪设置得比这更小，仍有落入内容区的风险，可以把按钮拖到别处躲开。
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (24 * density).toInt()
        }

        // 支持拖拽移动；按下-抬起总位移很小才算作一次点击（触发停止），避免拖拽误触发。
        // gravity 用的是 END（从右边内缩），x 越大表示离右边缘越远，
        // 所以手指往右拖（dx>0）时 x 要减小——横向增量要取反，纵向不受影响。
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downParamX - (event.rawX - downRawX).toInt()
                    params.y = downParamY + (event.rawY - downRawY).toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    if (moved < touchSlop) v.performClick()
                    true
                }
                else -> false
            }
        }
        button.setOnClickListener { onStop() }

        try {
            windowManager.addView(button, params)
            overlayView = button
            Log.d(TAG, "showInternal: addView succeeded")
        } catch (e: Exception) {
            // 极少数机型/权限状态下 addView 仍可能失败，退回通知栏停止方式，但记录下来方便排查
            Log.w(TAG, "showInternal: addView failed: $e")
        }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    private fun hideInternal() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "hideInternal: removeView failed: $e")
        }
        overlayView = null
    }
}
