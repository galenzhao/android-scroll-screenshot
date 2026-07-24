package com.galenzhao.scrollshot.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import com.galenzhao.scrollshot.CaptureRepository
import com.galenzhao.scrollshot.stitch.ImageStitcher
import com.galenzhao.scrollshot.stitch.ScrollDetector
import java.io.File

class FrameCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    /** 顶部要去掉的高度（px）。为 null 时使用系统状态栏高度；若目标 App 顶部还有浮动按钮等，可传入总高度（状态栏+浮动区域）。 */
    private val topCropHeightPx: Int? = null,
    /**
     * 底部要去掉的高度（px）。用于目标 App 自带的、不随内容滚动的底部区域（例如底部 Tab 栏）。
     * 为 null 或 <=0 表示不裁剪底部。这类固定区域如果不裁掉，会在长图中被反复截入
     * （因为它每一帧都出现在画面底部，滚动检测/拼接都会把它当成"新内容"）。
     */
    private val bottomCropHeightPx: Int? = null
) {
    companion object {
        private const val TAG = "FrameCaptureManager"
        /** 连续多少次检测失败后强制重新同步基准帧（宁可丢一小段内容，也不能永久卡死不再拼接） */
        private const val MAX_CONSECUTIVE_MISSES = 4

        /**
         * 重复帧检测用的缩略图尺寸/历史长度。触发重复内容的场景不只是"滚动到底后的回弹/停滞"
         * （这种通常连续出现，隔几步就能追上），实测还会出现"隔了一大段之后同一张卡片又出现一次"
         * 的情况（例如中途检测失配、强制重新同步基准帧后，重新对上的位置和之前已经拼过的内容重叠）。
         * 这种重复距离上一次出现可能已经隔了十几次成功拼接，如果历史窗口开得太短就会漏判。
         * 缩略图本身很小（48x96），即使记住整个拍摄过程的所有帧，内存开销也可以忽略不计，
         * 所以这里直接把历史长度设得足够大，相当于和"本次截图过程中拼过的每一帧"都比一遍。
         */
        private const val DUP_THUMB_W = 48
        private const val DUP_THUMB_H = 96
        private const val DUP_HISTORY_SIZE = 2000
        /** 缩略图逐像素平均 RGB 差异阈值：低于此值视为与近期已拼接画面基本相同 */
        private const val DUP_SAD_THRESHOLD = 6.0
    }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("FrameCaptureThread")
    private lateinit var handler: Handler

    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDensity = 0

    /** 有效内容区域高度（去掉顶部+底部裁剪后），用于滚动检测与拼接 */
    private var contentHeight = 0
    /** 第一帧被裁掉的顶部条，用于在最终长图前面再贴回去 */
    private var firstTopStrip: Bitmap? = null
    /**
     * 最近一帧被裁掉的底部条（如应用自身的底部 Tab 栏），用于在最终长图末尾再贴回去。
     *
     * 故意取「最后一帧」而不是第一帧：手填的裁剪高度不可能和固定区域的真实高度分毫不差，
     * 多多少少会带上一点相邻正文内容。这条底部条贴在长图的最末尾，如果从第一帧取，混进来的
     * 那一小截内容其实是"滚动开始时"的画面，跟长图末尾（滚动结束时）毫无关系，贴上去会显得
     * 莫名其妙；而从最后一帧取，混进来的内容至少是和长图末尾自然衔接的一小段延续。
     * 每处理一帧都会更新一次，始终保留"当前为止最后一帧"的底部条。
     */
    private var lastBottomStrip: Bitmap? = null

    private var prevBitmap: Bitmap? = null
    private var stitchCount = 0
    private lateinit var scrollDetector: ScrollDetector
    private lateinit var imageStitcher: ImageStitcher

    /** 连续检测失败（未识别到滚动）的次数；达到阈值后强制把基准帧重新同步到当前帧，避免永久卡死 */
    private var consecutiveMisses = 0

    /** 最近几次成功拼接帧的缩略图，用于识别“回弹/停滞导致的重复内容”，见 [isDuplicateOfRecent] */
    private val recentThumbnails = ArrayDeque<Bitmap>()

    private val statusBarHeight: Int by lazy {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    /** 实际用于裁剪的顶部高度：用户指定则用指定值，否则用系统状态栏高度 */
    private val effectiveTopCropHeight: Int
        get() = topCropHeightPx ?: statusBarHeight

    /** 实际用于裁剪的底部高度：用户未指定或 <=0 时不裁剪 */
    private val effectiveBottomCropHeight: Int
        get() = bottomCropHeightPx?.takeIf { it > 0 } ?: 0

    private var frameIndex = 0
    /** 每隔 N 帧处理一次，根据分辨率动态设置（高分辨率用更小 N 以免漏检） */
    private var processEveryN = 3

    /** release() 后不再处理任何帧，避免访问已释放的 ImageReader/Image 导致 SIGSEGV */
    @Volatile
    private var released = false

    fun start() {
        Log.d(TAG, "start() begin")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        frameWidth = bounds.width()
        frameHeight = bounds.height()
        frameDensity = context.resources.displayMetrics.densityDpi

        // 计算参与滚动检测与拼接的内容高度：整体高度减去顶部+底部裁剪高度
        val cropTop = effectiveTopCropHeight
        val cropBottom = effectiveBottomCropHeight
        contentHeight = if (cropTop + cropBottom > 0 && frameHeight > cropTop + cropBottom) {
            frameHeight - cropTop - cropBottom
        } else {
            frameHeight
        }

        // 适当降低处理频率，避免同一次手势滚动捕获过多切片
        processEveryN = when {
            frameHeight >= 2400 -> 5   // 高分屏：大约每 5 帧处理一次
            frameHeight >= 1800 -> 5
            else -> 6                  // 普通屏：大约每 6 帧处理一次
        }
        Log.d(TAG, "Window metrics width=$frameWidth height=$frameHeight contentHeight=$contentHeight density=$frameDensity processEveryN=$processEveryN cropTop=$cropTop cropBottom=$cropBottom")

        // 滚动检测与拼接都基于「裁剪后」的内容区域高度进行，保证尺寸一致
        scrollDetector = ScrollDetector(frameWidth, contentHeight)
        imageStitcher = ImageStitcher(frameWidth, contentHeight)

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageReader = ImageReader.newInstance(
            frameWidth, frameHeight, PixelFormat.RGBA_8888, 3
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (released) return@setOnImageAvailableListener
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                if (released) return@setOnImageAvailableListener
                frameIndex++
                if (frameIndex % processEveryN != 0) {
                    return@setOnImageAvailableListener
                }
                val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                if (released) {
                    bitmap.recycle()
                    return@setOnImageAvailableListener
                }
                processFrame(bitmap)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScrollShotDisplay",
            frameWidth, frameHeight, frameDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay created: $virtualDisplay")
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        if (released) return null
        return try {
            val planes = image.planes
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * frameWidth
            val paddedWidth = frameWidth + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(paddedWidth, frameHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(planes[0].buffer)

            if (rowPadding != 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, frameWidth, frameHeight)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun makeThumbnail(bitmap: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(bitmap, DUP_THUMB_W, DUP_THUMB_H, false)

    private fun pushRecentThumbnail(bitmap: Bitmap) {
        recentThumbnails.addLast(makeThumbnail(bitmap))
        if (recentThumbnails.size > DUP_HISTORY_SIZE) {
            recentThumbnails.removeFirst().recycle()
        }
    }

    /**
     * 判断 bitmap 是否与最近几次已成功拼接的画面「基本相同」。
     * 用于识别滚动到内容底部后继续滑动触发的回弹/停滞：单步 deltaY 检测可能仍然“合理”，
     * 但画面实际上又回到了此前已经拼接过的状态，若不拦截会把同一段内容重复拼进长图。
     */
    private fun isDuplicateOfRecent(bitmap: Bitmap): Boolean {
        if (recentThumbnails.isEmpty()) return false
        val thumb = makeThumbnail(bitmap)
        val pixelsA = IntArray(DUP_THUMB_W * DUP_THUMB_H)
        val pixelsB = IntArray(DUP_THUMB_W * DUP_THUMB_H)
        thumb.getPixels(pixelsA, 0, DUP_THUMB_W, 0, 0, DUP_THUMB_W, DUP_THUMB_H)
        val isDup = recentThumbnails.any { ref ->
            ref.getPixels(pixelsB, 0, DUP_THUMB_W, 0, 0, DUP_THUMB_W, DUP_THUMB_H)
            var sum = 0L
            for (i in pixelsA.indices) {
                val pa = pixelsA[i]
                val pb = pixelsB[i]
                sum += kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                        kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                        kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
            }
            (sum.toDouble() / pixelsA.size) < DUP_SAD_THRESHOLD
        }
        thumb.recycle()
        return isDup
    }

    /** 从当前这一帧（原始整帧，裁剪/回收之前）提取候选底部条，暂不提交，见 [commitBottomStrip] */
    private fun extractBottomStrip(bitmap: Bitmap): Bitmap? {
        val cropBottom = effectiveBottomCropHeight
        if (cropBottom <= 0 || bitmap.height <= cropBottom) return null
        return try {
            Bitmap.createBitmap(bitmap, 0, bitmap.height - cropBottom, bitmap.width, cropBottom)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "[ScrollShot] 提取底部条失败: ${e.message}")
            null
        }
    }

    /**
     * 提交候选底部条为"最后一帧底部条"。只应在 prevBitmap 真正推进到这一帧时调用——
     * 也就是说，只有被判定为"和应用正常内容对得上"的帧才有资格更新底部条。
     *
     * 这一点很重要：停止截图需要下拉通知栏、在通知里点"停止"，下拉通知栏这个动作本身
     * 也会被捕获到几帧。如果每一帧都无条件更新底部条，就会把通知栏面板当成"最后一帧"存下来，
     * 贴到长图末尾变成一截跟 App 毫不相关的内容。而通知栏画面和 App 正常内容天差地别，
     * detectScroll 根本匹配不上，prevBitmap 本来就不会推进到它——把底部条的更新和
     * prevBitmap 的推进绑在一起，就能天然避免把这类画面记下来。
     */
    private fun commitBottomStrip(strip: Bitmap?) {
        if (strip == null) return
        lastBottomStrip?.recycle()
        lastBottomStrip = strip
    }

    private fun processFrame(bitmap: Bitmap) {
        Log.d(TAG, "[ScrollShot] processFrame  frameIndex=$frameIndex stitchCount=$stitchCount prev=${prevBitmap != null}")
        val candidateBottomStrip = extractBottomStrip(bitmap)
        val prev = prevBitmap
        if (prev == null) {
            // 第一帧：若配置了顶部裁剪，则拆成「顶部条 + 内容区域」：
            // - 顶部条 firstTopStrip：只保留一次，用于最终长图前面再贴回去
            // - 内容区域：参与滚动检测与后续拼接
            val cropTop = effectiveTopCropHeight
            val cropBottom = effectiveBottomCropHeight
            if (cropTop + cropBottom > 0 && bitmap.height > cropTop + cropBottom &&
                contentHeight == bitmap.height - cropTop - cropBottom
            ) {
                try {
                    if (cropTop > 0) {
                        firstTopStrip = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropTop)
                    }
                    val content = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, contentHeight)
                    Log.d(TAG, "[ScrollShot] 第一帧拆分完成 full=${bitmap.width}x${bitmap.height} topStripH=$cropTop bottomStripH=$cropBottom contentH=$contentHeight")
                    bitmap.recycle()
                    imageStitcher.addFirstFrame(content)
                    prevBitmap = content
                } catch (e: IllegalArgumentException) {
                    // 裁剪异常时退回整帧逻辑（但此时 contentHeight 与实际高度可能不符，理论上不应出现）
                    Log.w(TAG, "[ScrollShot] 第一帧裁剪失败，退回整帧参与拼接: ${e.message}")
                    imageStitcher.addFirstFrame(bitmap)
                    prevBitmap = bitmap
                }
            } else {
                Log.d(TAG, "[ScrollShot] 未启用顶部/底部裁剪或尺寸不匹配，整帧作为基础 尺寸=${bitmap.width}x${bitmap.height}")
                imageStitcher.addFirstFrame(bitmap)
                prevBitmap = bitmap
            }
            commitBottomStrip(candidateBottomStrip)
            pushRecentThumbnail(prevBitmap!!)
            updateFrameCount()
            return
        }

        // 非第一帧：若启用了顶部/底部裁剪，则只截取「内容区域」参与滚动检测与拼接，
        // 确保 prev / current 在高度上完全一致（contentHeight）。
        val cropTop = if (contentHeight < frameHeight) effectiveTopCropHeight else 0
        val currentBitmap = if (contentHeight < frameHeight && bitmap.height >= cropTop + contentHeight) {
            try {
                val content = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, contentHeight)
                bitmap.recycle()
                content
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "[ScrollShot] 非第一帧裁剪失败，退回使用整帧: ${e.message}")
                bitmap
            }
        } else {
            bitmap
        }

        val deltaY = scrollDetector.detectScroll(prev, currentBitmap)
        Log.d(TAG, "[ScrollShot] detectScroll 结果 deltaY=$deltaY")
        if (released) {
            candidateBottomStrip?.recycle()
            currentBitmap.recycle()
            return
        }
        if (deltaY != null && deltaY > 0) {
            consecutiveMisses = 0

            if (isDuplicateOfRecent(currentBitmap)) {
                // 单步 deltaY 检测是"合理"的，但整体画面已经和最近几次拼接过的内容基本一样了——
                // 典型场景是滚动到列表底部后继续下拉触发的回弹/停滞动画：回弹过程本身确实有真实的
                // 像素位移，每一步单独看都会被判定为"有效滚动"，但连续几步的净效果只是原地反复，
                // 如果照样拼进长图，就会在结果末尾出现同一段内容被重复贴好几次。这里直接丢弃，
                // 仍然把 prevBitmap 更新为当前帧，避免其变得过时导致后续误判。
                Log.w(TAG, "[ScrollShot] 与近期已拼接画面基本相同(疑似触底回弹/停滞)，本帧丢弃不参与拼接")
                prev.recycle()
                prevBitmap = currentBitmap
                commitBottomStrip(candidateBottomStrip)
                updateFrameCount()
                return
            }

            val added = imageStitcher.addFrame(currentBitmap, deltaY)
            if (added) {
                stitchCount++
                Log.d(TAG, "[ScrollShot] 已拼接 当前切片数=$stitchCount")
                pushRecentThumbnail(currentBitmap)
                updateFrameCount()
                prev.recycle()
                prevBitmap = currentBitmap
                commitBottomStrip(candidateBottomStrip)
            } else {
                if (imageStitcher.isAtMaxHeight) {
                    Log.w(TAG, "[ScrollShot] 已达长图最大高度上限，本帧及之后的内容不再记录，请尽快停止")
                    updateFrameCount()
                } else {
                    Log.w(TAG, "[ScrollShot] addFrame 失败(无效) 本帧丢弃")
                }
                candidateBottomStrip?.recycle()
                currentBitmap.recycle()
            }
        } else {
            consecutiveMisses++
            Log.d(TAG, "[ScrollShot] 未检测到滚动 本帧丢弃 (连续失败次数=$consecutiveMisses)")
            // 连续多次检测失败说明 prevBitmap 已经严重过时（用户仍在持续滑动，
            // 而 prevBitmap 一直没更新，实际位移越滚越大，永远无法在搜索窗口内重新对齐）。
            // 与其永久卡死、后面所有帧都被丢弃，不如强制把基准帧重新同步到当前帧，
            // 代价是这一小段内容（未能成功匹配的这几帧）没有被拼进长图。
            if (consecutiveMisses >= MAX_CONSECUTIVE_MISSES) {
                Log.w(TAG, "[ScrollShot] 连续 $consecutiveMisses 次检测失败，强制重新同步基准帧，本段内容可能未完整捕获")
                consecutiveMisses = 0
                prev.recycle()
                prevBitmap = currentBitmap
                commitBottomStrip(candidateBottomStrip)
            } else {
                candidateBottomStrip?.recycle()
                currentBitmap.recycle()
            }
        }
    }

    private fun updateFrameCount() {
        val reachedLimit = imageStitcher.isAtMaxHeight
        Log.d(TAG, "updateFrameCount() stitchCount=$stitchCount reachedLimit=$reachedLimit")
        CaptureRepository.updateState(CaptureRepository.State.Capturing(stitchCount, reachedLimit))
    }

    fun buildResult(): Bitmap? {
        // 使用固定的调试目录，并在每次生成结果前清空旧的切片，避免图片无限累积
        val debugDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?.let { base ->
                val rootDir = File(base, "ScrollShotDebug")
                if (rootDir.exists()) {
                    rootDir.deleteRecursively()
                }
                rootDir.mkdirs()
                rootDir
            }
        if (debugDir != null) {
            imageStitcher.debugSaveSlicesTo = debugDir
            CaptureRepository.setLastDebugDir(debugDir.absolutePath)
            Log.d(TAG, "[ScrollShot] 切片目录(应用内查看): ${debugDir.absolutePath}")
            // 顶部条（第一帧）/底部条（最后一帧）单独存一份，方便核对裁剪高度设置得是否精确
            // （裁少了会带出一截正文内容，裁多了会切掉一截固定区域）
            firstTopStrip?.let { saveDebugBitmap(it, File(debugDir, "first_top_strip.png")) }
            lastBottomStrip?.let { saveDebugBitmap(it, File(debugDir, "last_bottom_strip.png")) }
        }
        val stitchedContent = imageStitcher.buildResult()
        if (stitchedContent == null) {
            Log.d(TAG, "[ScrollShot] 长图生成为 null")
            return null
        }

        // 顶部条取自第一帧、底部条取自最后一帧，分别贴回结果首尾，得到最终长图
        val topStrip = firstTopStrip
        val bottomStrip = lastBottomStrip
        val topHeight = if (topStrip != null && topStrip.width == stitchedContent.width) topStrip.height else 0
        val bottomHeight = if (bottomStrip != null && bottomStrip.width == stitchedContent.width) bottomStrip.height else 0

        val finalBitmap = if (topHeight > 0 || bottomHeight > 0) {
            val finalHeight = topHeight + stitchedContent.height + bottomHeight
            val result = Bitmap.createBitmap(stitchedContent.width, finalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            if (topHeight > 0) canvas.drawBitmap(topStrip!!, 0f, 0f, null)
            canvas.drawBitmap(stitchedContent, 0f, topHeight.toFloat(), null)
            if (bottomHeight > 0) canvas.drawBitmap(bottomStrip!!, 0f, (topHeight + stitchedContent.height).toFloat(), null)
            Log.d(TAG, "[ScrollShot] 长图生成完成(topStrip=$topHeight bottomStrip=$bottomHeight) 尺寸=${result.width}x${result.height} 切片数=$stitchCount")
            // 贴完后即可回收中间图，释放内存
            stitchedContent.recycle()
            result
        } else {
            Log.d(TAG, "[ScrollShot] 长图生成完成(无单独顶部/底部条) 尺寸=${stitchedContent.width}x${stitchedContent.height} 切片数=$stitchCount")
            stitchedContent
        }

        if (debugDir != null) {
            saveDebugBitmap(finalBitmap, File(debugDir, "final_result.png"))
        }

        return finalBitmap
    }

    private fun saveDebugBitmap(bitmap: Bitmap, file: File) {
        try {
            java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
    }

    fun release() {
        Log.d(TAG, "release()")
        stopCaptureOnly()
        imageStitcher.release()
        firstTopStrip?.recycle()
        firstTopStrip = null
        lastBottomStrip?.recycle()
        lastBottomStrip = null
        recentThumbnails.forEach { it.recycle() }
        recentThumbnails.clear()
    }

    /**
     * 仅停止屏幕捕获（VirtualDisplay / ImageReader / 线程），保留已采集的帧用于后续拼接。
     * 用于“用户点了停止后，立刻结束录屏指示，但仍可基于已有帧生成长图”。
     */
    fun stopCaptureOnly() {
        Log.d(TAG, "stopCaptureOnly()")
        released = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        // Recycle prevBitmap on the capture thread so we don't recycle it while
        // an in-flight processFrame() is still using it in ScrollDetector.detectScroll().
        handler.post {
            prevBitmap?.recycle()
            prevBitmap = null
            handlerThread.quitSafely()
        }
    }
}
