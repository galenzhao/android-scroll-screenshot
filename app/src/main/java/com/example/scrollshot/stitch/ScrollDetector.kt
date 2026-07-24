package com.galenzhao.scrollshot.stitch

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 滚动检测器：通过「整帧行特征序列」相关性搜索计算两帧之间的垂直偏移量（deltaY）。
 *
 * 早期版本的思路是"在画面里挑一条（或两条）窄带做模板匹配"——窄带信息量有限，
 * 遇到内容里有大量相似结构（例如一堆长得很像的列表卡片/地图缩略图）时，
 * 无论加多少唯一性校验、双带互相校验，本质上都只是在一个很小的样本上赌运气，
 * 总有概率被巧合骗到一个"看起来合理"但实际错误的位置，导致长图内容重复或跳跃。
 *
 * 这里改为让画面**所有行**一起参与匹配：把每一行降采样简化成一个小向量（按列分 8 段
 * 取平均亮度），对每个候选位移，比较两帧重叠区域内*每一行*的特征差异之和，取总差异
 * 最小、且明显优于次优结果的位移作为答案。要让一个错误位移蒙混过关，需要两帧在整个
 * 重叠区域（往往有上百行）内高度重复，这比"某条窄带碰巧相似"苛刻得多，天然抵御重复
 * 图案造成的误判。计算量上也比之前"多条窄带 x 多轮搜索"更小。
 *
 * 所有可调参数均根据 [frameWidth]、[frameHeight] 动态计算，无需手改常量。
 */
class ScrollDetector(private val frameWidth: Int, private val frameHeight: Int) {

    companion object {
        private const val TAG = "ScrollShot_ScrollDetect"
        /** 每行切成几段分别取平均亮度，作为该行的特征向量；越大越精细但计算量线性增加 */
        private const val ROW_SEGMENTS = 8
    }

    /** 根据当前分辨率算出的检测参数，构造时一次性计算 */
    private val config = ScrollDetectConfig.fromResolution(frameWidth, frameHeight)

    private val sw = (frameWidth * config.scale).toInt().coerceAtLeast(ROW_SEGMENTS)
    private val sh = (frameHeight * config.scale).toInt().coerceAtLeast(1)
    private val segW = (sw / ROW_SEGMENTS).coerceAtLeast(1)

    /**
     * 检测 prevBitmap -> currBitmap 之间内容向上滚动的像素数。
     * 返回值 > 0 表示发生了有效滚动（内容上移了 deltaY 个原始像素）。
     * 返回 null 表示未检测到有效滚动，或匹配结果不可信。
     */
    fun detectScroll(prevBitmap: Bitmap, currBitmap: Bitmap): Int? {
        val scaledPrev = Bitmap.createScaledBitmap(prevBitmap, sw, sh, false)
        val scaledCurr = Bitmap.createScaledBitmap(currBitmap, sw, sh, false)

        return try {
            val rowsPrev = buildRowDescriptors(scaledPrev)
            val rowsCurr = buildRowDescriptors(scaledCurr)

            // 重叠行数太少时统计意义不足，容易被巧合骗过，故要求至少 minOverlapRatio 比例的行参与比较，
            // 这同时限定了单次能检测到的最大位移（约为 sh 的 (1-minOverlapRatio)）。
            val minOverlapRows = (sh * config.minOverlapRatio).toInt().coerceAtLeast(ROW_SEGMENTS)
            val maxDelta = sh - minOverlapRows
            if (maxDelta <= 0) {
                Log.d(TAG, "no_scroll: maxDelta<=0 (sh=$sh minOverlapRows=$minOverlapRows)")
                return null
            }

            // 对每个候选位移 delta，比较 prev[delta, sh) 与 curr[0, sh-delta) 逐行特征差异，
            // 取"平均每行差异"最小的 delta（除以重叠行数，避免偏向重叠行数少的大 delta）。
            var bestDelta = 0
            var bestCost = Double.MAX_VALUE
            val costs = DoubleArray(maxDelta + 1)
            for (delta in 1..maxDelta) {
                val overlapRows = sh - delta
                var sum = 0.0
                for (i in 0 until overlapRows) {
                    sum += rowDiff(rowsPrev, delta + i, rowsCurr, i)
                }
                val avgCost = sum / overlapRows
                costs[delta] = avgCost
                if (avgCost < bestCost) {
                    bestCost = avgCost
                    bestDelta = delta
                }
            }

            if (bestDelta <= 0) {
                Log.d(TAG, "no_scroll: bestDelta<=0")
                return null
            }
            if (bestCost > config.maxRowCost) {
                Log.d(TAG, "no_scroll: cost too high bestCost=${"%.1f".format(bestCost)} max=${config.maxRowCost} bestDelta=$bestDelta")
                return null
            }

            // 唯一性校验：排除 bestDelta 邻近范围（自然平滑、不算歧义）后，
            // 若仍存在同样很低的次优位移，说明画面里有大段重复结构，本次匹配不可信，直接放弃。
            val separation = (sh * 0.1).toInt().coerceAtLeast(ROW_SEGMENTS)
            var secondBestCost = Double.MAX_VALUE
            for (delta in 1..maxDelta) {
                if (abs(delta - bestDelta) < separation) continue
                if (costs[delta] < secondBestCost) secondBestCost = costs[delta]
            }
            if (secondBestCost != Double.MAX_VALUE && secondBestCost < bestCost * config.ambiguityRatio) {
                Log.d(TAG, "no_scroll: ambiguous match (repeated pattern?) bestCost=${"%.1f".format(bestCost)} secondBestCost=${"%.1f".format(secondBestCost)} bestDelta=$bestDelta")
                return null
            }

            var delta = (bestDelta / config.scale).roundToInt()
            // 基于完整分辨率在粗略结果附近做一小段精修，减少由于下采样与取整带来的 1～2px 误差
            delta = refineDeltaOnOriginal(prevBitmap, currBitmap, delta)
            if (delta < config.minScrollPx) {
                Log.d(TAG, "no_scroll: delta too small delta=$delta minScrollPx=${config.minScrollPx}")
                return null
            }
            Log.d(TAG, "scroll: deltaY=$delta (scaledDelta=$bestDelta cost=${"%.1f".format(bestCost)} secondBestCost=${if (secondBestCost == Double.MAX_VALUE) "-" else "%.1f".format(secondBestCost)})")
            delta
        } finally {
            scaledPrev.recycle()
            scaledCurr.recycle()
        }
    }

    /**
     * 把每一行切成 [ROW_SEGMENTS] 段，取每段平均亮度作为该行的特征向量。
     * 展平存储为 FloatArray，第 y 行第 seg 段位于下标 `y * ROW_SEGMENTS + seg`。
     */
    private fun buildRowDescriptors(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(sw * sh)
        bitmap.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val desc = FloatArray(sh * ROW_SEGMENTS)
        for (y in 0 until sh) {
            val rowBase = y * sw
            for (seg in 0 until ROW_SEGMENTS) {
                val xStart = seg * segW
                val xEnd = if (seg == ROW_SEGMENTS - 1) sw else (xStart + segW)
                var sum = 0L
                var count = 0
                for (x in xStart until xEnd) {
                    val p = pixels[rowBase + x]
                    // 近似亮度（整数运算，避免浮点开销）
                    val luma = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 4 + (p and 0xFF)) shr 3
                    sum += luma
                    count++
                }
                desc[y * ROW_SEGMENTS + seg] = if (count > 0) sum.toFloat() / count else 0f
            }
        }
        return desc
    }

    private fun rowDiff(a: FloatArray, rowA: Int, b: FloatArray, rowB: Int): Double {
        val baseA = rowA * ROW_SEGMENTS
        val baseB = rowB * ROW_SEGMENTS
        var sum = 0.0
        for (k in 0 until ROW_SEGMENTS) {
            sum += abs(a[baseA + k] - b[baseB + k])
        }
        return sum
    }

    /**
     * 在原始分辨率上对 delta 做细化搜索。
     *
     * 做法：在粗略 delta 附近的一个很小范围内（±3 像素）用 SAD 比较一块小区域，
     * 选出误差最小的 delta，基本可以把累计的 1～2px 取整误差消掉。
     */
    private fun refineDeltaOnOriginal(
        prevBitmap: Bitmap,
        currBitmap: Bitmap,
        coarseDelta: Int,
        radius: Int = 3
    ): Int {
        if (coarseDelta <= 0 || radius <= 0) return coarseDelta

        val w = minOf(prevBitmap.width, currBitmap.width)
        val h = minOf(prevBitmap.height, currBitmap.height)
        if (w <= 0 || h <= 0) return coarseDelta

        val deltaMin = maxOf(1, coarseDelta - radius)
        val deltaMax = minOf(coarseDelta + radius, h - 1)
        if (deltaMax <= deltaMin) return coarseDelta

        // 选取靠近屏幕中下部的一块区域做对齐，避开顶部状态栏等易变化区域
        val baseY = (h * 0.65f).toInt().coerceIn(0, h - 1)
        val sampleHeight = minOf(80, h - baseY - 1).coerceAtLeast(40)
        if (sampleHeight <= 0) return coarseDelta

        // 宽度上按步长采样，避免整幅遍历造成不必要开销
        val stepX = (w / 64).coerceAtLeast(1)

        var bestDelta = coarseDelta
        var minSad = Long.MAX_VALUE

        for (delta in deltaMin..deltaMax) {
            var sad = 0L
            val yEnd = baseY + sampleHeight
            for (y in baseY until yEnd) {
                val cy = y - delta
                if (cy < 0) break
                for (x in 0 until w step stepX) {
                    val p1 = prevBitmap.getPixel(x, y)
                    val p2 = currBitmap.getPixel(x, cy)

                    val r1 = (p1 shr 16) and 0xFF
                    val g1 = (p1 shr 8) and 0xFF
                    val b1 = p1 and 0xFF
                    val r2 = (p2 shr 16) and 0xFF
                    val g2 = (p2 shr 8) and 0xFF
                    val b2 = p2 and 0xFF

                    sad += kotlin.math.abs(r1 - r2) +
                            kotlin.math.abs(g1 - g2) +
                            kotlin.math.abs(b1 - b2)
                }
            }
            if (sad < minSad) {
                minSad = sad
                bestDelta = delta
            }
        }

        if (bestDelta != coarseDelta) {
            Log.d(TAG, "refine: coarse=$coarseDelta refined=$bestDelta h=$h w=$w")
        }
        return bestDelta
    }

    /**
     * 根据分辨率动态生成的滚动检测参数。
     * 高分辨率：更大最小滚动、更大匹配容差；低分辨率：更灵敏、更严匹配。
     */
    private data class ScrollDetectConfig(
        val scale: Float,
        /** 参与比较的最小重叠行比例（相对 sh），同时决定单次可检测的最大位移 */
        val minOverlapRatio: Float,
        val minScrollPx: Int,
        /** 平均每行特征差异的接受阈值（0~2040 量级，行特征为 8 段 0~255 亮度） */
        val maxRowCost: Double,
        /** 次优位移的 cost 需比最优至少高出该倍数，否则视为重复图案导致的歧义匹配 */
        val ambiguityRatio: Double
    ) {
        companion object {
            /** 参考高度：约 1080p 竖屏，用于线性插值 */
            private const val REF_HEIGHT = 1920
            private const val REF_WIDTH = 1080

            fun fromResolution(width: Int, height: Int): ScrollDetectConfig {
                val pixels = width * height
                val refPixels = REF_WIDTH * REF_HEIGHT
                val resolutionFactor = (pixels.toFloat() / refPixels).coerceIn(0.5f, 3f)

                // 下采样比例：分辨率越高可略降以省算力，保持至少 0.15
                val scale = when {
                    height >= 2400 -> 0.15f
                    height >= 1800 -> 0.18f
                    else -> 0.2f
                }

                // 最小有效滚动（px）：按高度比例，高屏忽略小抖动
                val minScrollPx = (height / 60).coerceAtLeast(
                    (12 + resolutionFactor * 8).toInt().coerceAtLeast(12)
                )

                return ScrollDetectConfig(
                    scale = scale,
                    minOverlapRatio = 0.25f,
                    minScrollPx = minScrollPx,
                    maxRowCost = 22.0,
                    ambiguityRatio = 1.15
                )
            }
        }
    }
}
