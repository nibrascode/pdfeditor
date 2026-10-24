package com.nibrascode.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OcrWord(val text: String, val box: RectF)

// Skan edilmiş səhifələr üçün cihazda OCR (Tesseract): Azərbaycan + ərəb + ingilis
object Ocr {
    const val LANGS = "aze+ara+eng"
    private val files = listOf("aze", "ara", "eng")

    fun root(ctx: Context): File = File(ctx.filesDir, "tesseract")

    fun ready(ctx: Context): Boolean = files.all { File(root(ctx), "tessdata/$it.traineddata").let { f -> f.exists() && f.length() > 1000 } }

    // tessdata_fast fayllarını yükləyir (bir dəfə, ~10 MB)
    fun download(ctx: Context, progress: (String) -> Unit) {
        val dir = File(root(ctx), "tessdata")
        dir.mkdirs()
        for (l in files) {
            val f = File(dir, "$l.traineddata")
            if (f.exists() && f.length() > 1000) continue
            progress(l)
            var url = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/$l.traineddata")
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    hops++
                    continue
                }
                if (code != 200) throw java.io.IOException("HTTP $code")
                break
            }
            val tmp = File(dir, "$l.part")
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            conn.disconnect()
            if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        }
    }

    fun open(ctx: Context): TessBaseAPI {
        val t = TessBaseAPI()
        if (!t.init(root(ctx).absolutePath, LANGS)) {
            t.recycle()
            throw IllegalStateException("tesseract init")
        }
        return t
    }

    fun recognize(t: TessBaseAPI, bm: Bitmap): List<OcrWord> {
        val out = ArrayList<OcrWord>()
        t.setImage(bm)
        t.getUTF8Text()
        val it = t.resultIterator ?: return out
        val lvl = TessBaseAPI.PageIteratorLevel.RIL_WORD
        it.begin()
        do {
            val w = it.getUTF8Text(lvl)
            val r = it.getBoundingRect(lvl)
            if (!w.isNullOrBlank() && r != null) {
                out.add(OcrWord(w.trim(), RectF(r.left / bm.width.toFloat(), r.top / bm.height.toFloat(), r.right / bm.width.toFloat(), r.bottom / bm.height.toFloat())))
            }
        } while (it.next(lvl))
        it.delete()
        t.clear()
        return out
    }

    fun toJson(pages: List<List<OcrWord>?>): String {
        val root = JSONArray()
        for (p in pages) {
            if (p == null) {
                root.put(org.json.JSONObject.NULL)
                continue
            }
            val a = JSONArray()
            for (w in p) a.put(JSONArray().put(w.text).put(w.box.left.toDouble()).put(w.box.top.toDouble()).put(w.box.right.toDouble()).put(w.box.bottom.toDouble()))
            root.put(a)
        }
        return root.toString()
    }

    fun fromJson(s: String): List<List<OcrWord>?> {
        val root = JSONArray(s)
        val out = ArrayList<List<OcrWord>?>()
        for (i in 0 until root.length()) {
            val a = root.optJSONArray(i)
            if (a == null) {
                out.add(null)
                continue
            }
            val l = ArrayList<OcrWord>()
            for (k in 0 until a.length()) {
                val w = a.getJSONArray(k)
                l.add(OcrWord(w.getString(0), RectF(w.getDouble(1).toFloat(), w.getDouble(2).toFloat(), w.getDouble(3).toFloat(), w.getDouble(4).toFloat())))
            }
            out.add(l)
        }
        return out
    }

    fun pageText(words: List<OcrWord>): PdfOps.PageText {
        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        for (w in words) {
            for (c in w.text) {
                sb.append(c)
                boxes.add(w.box)
            }
            sb.append(' ')
            boxes.add(null)
        }
        return PdfOps.PageText(sb.toString(), boxes)
    }
}
