package com.example.universaldocconverter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A single-screen, offline app. SAF grants access only to files/folders selected by the user. */
class MainActivity : Activity() {
    private val chooseFolder = 1
    private val chooseFile = 2
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var folderLabel: TextView
    private lateinit var history: TextView
    private var mode = DocumentEngine.Mode.WORD_PDF
    private var format = "png"
    private var quality = 2
    private var busy = false
    private var folder: Uri? = null
    private val preferences by lazy { getSharedPreferences("local", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        folder = preferences.getString("folder", null)?.let(Uri::parse)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(label("万能文档转换助手", 24f))
        root.addView(label("所有文件仅在本机处理", 14f))
        root.addView(card("Word → PDF") { start(DocumentEngine.Mode.WORD_PDF) })
        root.addView(card("Word → 图片") { start(DocumentEngine.Mode.WORD_IMAGE) })
        root.addView(card("PDF → Word (可编辑文本)") { start(DocumentEngine.Mode.PDF_WORD) })
        root.addView(card("PDF → 图片") { start(DocumentEngine.Mode.PDF_IMAGE) })
        folderLabel = label("", 14f)
        root.addView(folderLabel)
        root.addView(card("选择保存目录") { pickFolder() })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress)
        status = label("", 14f)
        root.addView(status)
        root.addView(label("转换记录（仅保存在本机）", 18f))
        history = label("", 14f)
        root.addView(history)
        refresh()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(4, 12, 4, 12)
    }

    private fun card(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 17f
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun refresh() {
        folderLabel.text = if (folder == null) "请先选择保存目录" else "保存目录已选择，可随时更改"
        history.text = preferences.getString("history", "暂无记录")
    }

    private fun start(selected: DocumentEngine.Mode) {
        if (busy) return
        mode = selected
        if (folder == null) { pickFolder(); return }
        if (selected == DocumentEngine.Mode.WORD_IMAGE || selected == DocumentEngine.Mode.PDF_IMAGE) {
            val options = arrayOf("PNG · 普通", "PNG · 高清", "PNG · 超清", "JPG · 普通", "JPG · 高清", "JPG · 超清")
            AlertDialog.Builder(this).setTitle("图片格式与清晰度").setItems(options) { _, which ->
                format = if (which < 3) "png" else "jpg"
                quality = which % 3 + 1
                pickFile()
            }.show()
        } else pickFile()
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, chooseFolder)
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, if (mode == DocumentEngine.Mode.PDF_WORD || mode == DocumentEngine.Mode.PDF_IMAGE)
                arrayOf("application/pdf") else arrayOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }, chooseFile)
    }

    @Deprecated("Activity result API is enough for this self-contained project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == chooseFolder) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folder = uri
            preferences.edit().putString("folder", uri.toString()).apply()
            refresh()
        } else if (requestCode == chooseFile) convert(uri)
    }

    private fun convert(uri: Uri) {
        val tree = folder ?: return
        val name = queryName(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val word = mode == DocumentEngine.Mode.WORD_PDF || mode == DocumentEngine.Mode.WORD_IMAGE
        if ((word && ext !in listOf("doc", "docx")) || (!word && ext != "pdf")) {
            Toast.makeText(this, "请选择对应格式的文件", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "正在转换：$name"
        executor.execute {
            val input = File.createTempFile("input_", ".$ext", cacheDir)
            val generated = File.createTempFile("converted_", ".${if (mode == DocumentEngine.Mode.PDF_WORD) "docx" else "pdf"}", cacheDir)
            try {
                copyInput(uri, input)
                val base = name.substringBeforeLast('.').take(40).ifBlank { "转换文件" }
                    .replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val prefix = "${base}_$stamp"
                val tick: (Int) -> Unit = { value -> runOnUiThread { progress.progress = value } }
                var count = 1
                when (mode) {
                    DocumentEngine.Mode.WORD_PDF -> {
                        DocumentEngine.wordToPdf(input, ext, generated, tick)
                        saveFile(tree, "$prefix.pdf", "application/pdf", generated)
                    }
                    DocumentEngine.Mode.PDF_WORD -> {
                        DocumentEngine.pdfToDocx(input, generated, tick)
                        saveFile(tree, "$prefix.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", generated)
                    }
                    DocumentEngine.Mode.WORD_IMAGE -> {
                        DocumentEngine.wordToPdf(input, ext, generated) { tick(it / 2) }
                        count = renderImages(generated, tree, prefix) { tick(50 + it / 2) }
                    }
                    DocumentEngine.Mode.PDF_IMAGE -> count = renderImages(input, tree, prefix, tick)
                }
                val message = "$name → ${if (count == 1) prefix else "$count 张图片"}"
                val old = preferences.getString("history", "") ?: ""
                preferences.edit().putString("history", (message + "\n" + old).lines().take(20).joinToString("\n")).apply()
                runOnUiThread {
                    status.text = "转换成功：$message"
                    refresh()
                    AlertDialog.Builder(this).setMessage("转换成功，已保存到所选目录")
                        .setPositiveButton("打开目录") { _, _ -> openFolder(tree) }
                        .setNegativeButton("完成", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "转换失败：${e.message ?: "文件无法读取"}" }
            } finally {
                input.delete()
                generated.delete()
                runOnUiThread { busy = false; progress.visibility = View.GONE }
            }
        }
    }

    private fun renderImages(input: File, tree: Uri, prefix: String, tick: (Int) -> Unit): Int {
        var count = 0
        DocumentEngine.pdfToImages(input, quality, format, tick) { page, ext, bitmap ->
            val mime = if (ext == "png") "image/png" else "image/jpeg"
            val target = createOutput(tree, "${prefix}_第${page}页.$ext", mime)
            contentResolver.openOutputStream(target, "w")!!.use { out ->
                bitmap.compress(if (ext == "png") android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            count++
        }
        return count
    }

    private fun copyInput(uri: Uri, target: File) {
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 50L * 1024 * 1024) { "文件超过 50 MB 上限" }
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "文档"
        }
        return "文档"
    }

    private fun createOutput(tree: Uri, name: String, mime: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(contentResolver, parent, mime, name) ?: error("无法创建输出文件")
    }

    private fun saveFile(tree: Uri, name: String, mime: String, source: File) {
        val target = createOutput(tree, name, mime)
        contentResolver.openOutputStream(target, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    private fun openFolder(tree: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(tree, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree)
            })
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
