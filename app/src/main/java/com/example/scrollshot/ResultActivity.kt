package com.galenzhao.scrollshot

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.galenzhao.scrollshot.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var resultBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        resultBitmap = CaptureRepository.resultBitmap
        if (resultBitmap == null) {
            Toast.makeText(this, getString(R.string.toast_no_result), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.ivResult.setImageBitmap(resultBitmap)
        binding.tvImageInfo.text = getString(
            R.string.image_info_format,
            resultBitmap!!.width,
            resultBitmap!!.height
        )

        binding.btnSave.setOnClickListener { saveBitmap() }
        binding.btnShare.setOnClickListener { shareBitmap() }
        binding.btnDiscard.setOnClickListener {
            CaptureRepository.clearResult()
            finish()
        }

        val debugDir = CaptureRepository.lastDebugDir
        binding.btnViewSlices.visibility = if (debugDir != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnViewSlices.setOnClickListener {
            startActivity(Intent(this, SliceViewerActivity::class.java)
                .putExtra(SliceViewerActivity.EXTRA_DEBUG_DIR, CaptureRepository.lastDebugDir))
        }
    }

    private fun applyWindowInsets() {
        val topPad = binding.topBar.paddingTop
        val bottomPad = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = topPad + insets.top)
            binding.bottomBar.updatePadding(bottom = bottomPad + insets.bottom)
            windowInsets
        }
    }

    private fun saveBitmap() {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveToGallery(bmp) }
            if (uri != null) {
                Toast.makeText(this@ResultActivity, getString(R.string.toast_saved_ok), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ResultActivity, getString(R.string.toast_saved_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        return try {
            val fileName = "ScrollShot_${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrollShot")
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                val out: OutputStream = resolver.openOutputStream(it) ?: return null
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                out.close()
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun shareBitmap() {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { saveToGallery(bmp) }
            if (uri != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不 recycle，因为 CaptureRepository 仍持有引用
    }
}
