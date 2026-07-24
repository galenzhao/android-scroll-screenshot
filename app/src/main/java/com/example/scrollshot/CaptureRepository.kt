package com.galenzhao.scrollshot

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单例仓库，在 MainActivity 和 ScreenCaptureService 之间共享捕获状态。
 */
object CaptureRepository {

    sealed class State {
        object Idle : State()
        /** [reachedLimit] 为 true 表示长图已达最大高度上限，之后的滚动内容不会再被记录，应尽快停止 */
        data class Capturing(val frameCount: Int = 0, val reachedLimit: Boolean = false) : State()
        object Processing : State()
        data class Completed(val result: Bitmap) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var _resultBitmap: Bitmap? = null
    val resultBitmap: Bitmap? get() = _resultBitmap

    /** 最近一次 buildResult 时保存切片/长图的调试目录路径，用于应用内「查看切片」 */
    private var _lastDebugDir: String? = null
    val lastDebugDir: String? get() = _lastDebugDir

    fun setLastDebugDir(path: String?) {
        _lastDebugDir = path
    }

    fun updateState(newState: State) {
        if (newState is State.Completed) {
            _resultBitmap = newState.result
        }
        _state.value = newState
    }

    fun clearResult() {
        _resultBitmap?.recycle()
        _resultBitmap = null
        _lastDebugDir = null
        _state.value = State.Idle
    }
}
