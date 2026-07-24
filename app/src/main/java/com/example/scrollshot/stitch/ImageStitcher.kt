package com.galenzhao.scrollshot.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/**
 * 图像拼接器：按「新增底部条带追加」的方式生成长截图。
 *
 * - 第一帧整帧作为长图顶部。
 * - 后续每一帧只截取本次滚动新出现的底部条带（高度 = deltaY），追加到长图末尾。
 *   这样每一帧自身顶部的内容（哪怕是应用自己的吸顶头部、返回键/菜单栏等不随内容滚动的元素）
 *   只会在它所属的那一帧里出现一次，不会像「整帧覆盖」那样在每个拼接点被反复画一遍。
 * - 可通过 [debugSaveSlicesTo] 将每个切片保存到指定目录，供应用内「查看切片」界面使用。
 */
class ImageStitcher(
    private val frameWidth: Int,
    private val frameHeight: Int
) {

    companion object {
        // 单张最大高度（px）。现在按“新增条带追加”而非整帧存储，内存占用已大幅降低，
        // 故可以放到比较宽松的上限；主要是为了避免单张 Bitmap 大到在个别设备上分配失败。
        private const val MAX_TOTAL_HEIGHT = 30000
    }

    private var firstFrame: Bitmap? = null
    /** 每一条追加的新内容条带（本次滚动新出现的底部区域），按顺序拼在第一帧下方 */
    private val strips = mutableListOf<Bitmap>()
    private var totalHeight = 0

    /** 是否已经达到长图最大高度上限，之后追加的帧会被丢弃；供调用方提示用户及时停止 */
    val isAtMaxHeight: Boolean
        get() = totalHeight >= MAX_TOTAL_HEIGHT

    /** 若设置，buildResult 时会把每个切片保存到此目录，供应用内查看：slice_001.png, slice_002.png, ... result.png */
    var debugSaveSlicesTo: File? = null

    fun addFirstFrame(bitmap: Bitmap) {
        firstFrame?.recycle()
        firstFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        totalHeight = frameHeight
    }

    /**
     * 添加新的一帧。
     * @param currBitmap 当前帧（整帧）
     * @param deltaY 本帧与上一帧之间内容上移的像素数（重合高度）。
     */
    fun addFrame(currBitmap: Bitmap, deltaY: Int): Boolean {
        if (firstFrame == null) {
            addFirstFrame(currBitmap)
            return true
        }
        if (deltaY <= 0) return false

        val clampedDelta = deltaY.coerceAtMost(frameHeight)
        val resultHeight = totalHeight + clampedDelta
        if (resultHeight > MAX_TOTAL_HEIGHT) return false

        // 只截取本帧“新出现”的底部条带，而不是保存整帧，
        // 既避免了吸顶元素被重复画入长图，也大幅减少了内存占用（不用一直持有所有整帧）。
        val stripTop = frameHeight - clampedDelta
        val strip = Bitmap.createBitmap(currBitmap, 0, stripTop, frameWidth, clampedDelta)
        strips.add(strip)
        totalHeight += clampedDelta
        return true
    }

    /**
     * 依次画出第一帧，再把每条新增条带追加到末尾。
     */
    fun buildResult(): Bitmap? {
        val first = firstFrame ?: return null

        val resultHeight = totalHeight.coerceAtMost(MAX_TOTAL_HEIGHT)
        val result = Bitmap.createBitmap(frameWidth, resultHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val debugDir = debugSaveSlicesTo
        if (debugDir != null) debugDir.mkdirs()

        // 第一帧整帧画在顶部
        canvas.drawBitmap(first, 0f, 0f, null)
        if (debugDir != null) saveBitmap(first, File(debugDir, "slice_001.png"))

        var y = frameHeight
        for ((index, strip) in strips.withIndex()) {
            if (y >= resultHeight) break

            val drawHeight = strip.height.coerceAtMost(resultHeight - y)
            if (drawHeight <= 0) continue

            canvas.drawBitmap(
                strip,
                Rect(0, 0, frameWidth, drawHeight),
                Rect(0, y, frameWidth, y + drawHeight),
                null
            )
            if (debugDir != null) saveBitmap(strip, File(debugDir, "slice_%03d.png".format(index + 2)))
            y += drawHeight
        }

        if (debugDir != null) saveBitmap(result, File(debugDir, "result.png"))

        first.recycle()
        strips.forEach { it.recycle() }
        strips.clear()
        totalHeight = 0
        firstFrame = null

        return result
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
    }

    fun release() {
        firstFrame?.recycle()
        firstFrame = null
        strips.forEach { it.recycle() }
        strips.clear()
        totalHeight = 0
    }
}
