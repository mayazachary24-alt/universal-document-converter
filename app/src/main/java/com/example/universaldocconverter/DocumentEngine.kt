package com.example.universaldocconverter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.extractor.WordExtractor
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** All conversion happens on device. The caller owns temporary files. */
object DocumentEngine {
    enum class Mode { WORD_PDF, WORD_IMAGE, PDF_WORD, PDF_IMAGE }

    fun wordToPdf(input: File, extension: String, output: File, progress: (Int) -> Unit) {
        val paragraphs = if (extension == "doc") readDoc(input) else readDocx(input)
        val pdf = PdfDocument()
        val width = 595
        val height = 842
        val margin = 48f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = margin + 12f
        fun nextPage() {
            page?.let { pdf.finishPage(it) }
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            y = margin + 12f
        }
        nextPage()
        paragraphs.forEachIndexed { index, paragraph ->
            val lines = wrap(paragraph, paint, width - 2 * margin)
            for (line in lines) {
                if (y > height - margin) nextPage()
                page!!.canvas.drawText(line, margin, y, paint)
                y += 20f
            }
            y += 7f
            progress(((index + 1) * 90 / paragraphs.size.coerceAtLeast(1)))
        }
        page?.let { pdf.finishPage(it) }
        FileOutputStream(output).use { pdf.writeTo(it) }
        pdf.close()
        progress(100)
    }

    /** PDF pages become editable text paragraphs; scanned pages have no OCR. */
    fun pdfToDocx(input: File, output: File, progress: (Int) -> Unit) {
        PDDocument.load(input).use { pdf ->
            val pages = mutableListOf<String>()
            val count = pdf.numberOfPages
            for (i in 1..count) {
                val text = PDFTextStripper().apply { startPage = i; endPage = i }.getText(pdf)
                pages.add(text)
                progress(i * 90 / count.coerceAtLeast(1))
            }
            writeDocx(pages, output)
        }
        progress(100)
    }

    /** Android's built-in PdfRenderer rasterizes locally, one page at a time. */
    fun pdfToImages(
        input: File, quality: Int, format: String, progress: (Int) -> Unit,
        save: (page: Int, extension: String, bitmap: Bitmap) -> Unit
    ) {
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = when (quality) { 1 -> 1f; 3 -> 3f; else -> 2f }
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()
                        require(w.toLong() * h <= 24_000_000L) { "图片页过大，请降低清晰度" }
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            save(i + 1, format, bitmap)
                        } finally { bitmap.recycle() }
                    }
                    progress((i + 1) * 100 / renderer.pageCount)
                }
            }
        }
    }

    private fun readDoc(input: File): List<String> =
        FileInputStream(input).use { stream ->
            WordExtractor(stream).use { extractor -> extractor.paragraphText.toList() }
        }

    private fun readDocx(input: File): List<String> {
        ZipFile(input).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("不是有效的 DOCX 文件")
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            var inText = false
            zip.getInputStream(entry).use { stream ->
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    setInput(stream, "UTF-8")
                }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "p" -> current.clear()
                            "t" -> inText = true
                            "tab" -> current.append('\t')
                            "br" -> current.append('\n')
                        }
                        XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "t" -> inText = false
                            "p" -> paragraphs.add(current.toString())
                        }
                    }
                    parser.next()
                }
            }
            return paragraphs
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val line = StringBuilder()
            for (char in raw) {
                if (paint.measureText(line.toString() + char) > maxWidth && line.isNotEmpty()) {
                    lines.add(line.toString())
                    line.clear()
                }
                line.append(char)
            }
            lines.add(line.toString())
        }
        return lines
    }

    private fun writeDocx(pages: List<String>, output: File) {
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            fun entry(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            val body = buildString {
                pages.forEachIndexed { pageIndex, page ->
                    if (pageIndex > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    page.lines().forEach { line ->
                        append("<w:p><w:r><w:t xml:space=\"preserve\">")
                        append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                        append("</w:t></w:r></w:p>")
                    }
                }
            }
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>""")
        }
    }
}
