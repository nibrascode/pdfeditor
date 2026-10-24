package com.nibrascode.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Səhifə tərtibi: hansı mənbədən hansı səhifə (src = -1 -> boş səhifə)
class PageSpec(val doc: Int, val src: Int, val rot: Int = 0, val w: Float = 595f, val h: Float = 842f)

// Axtarış nəticəsi: səhifə + normallaşdırılmış düzbucaqlılar
class Hit(val page: Int, val rects: List<RectF>)

object PdfOps {
    @Volatile
    private var inited = false
    private var app: Context? = null

    fun init(ctx: Context) {
        if (!inited) {
            app = ctx.applicationContext
            PDFBoxResourceLoader.init(ctx.applicationContext)
            inited = true
        }
    }

    private fun loadFont(doc: PDDocument): com.tom_roush.pdfbox.pdmodel.font.PDType0Font? = try {
        app!!.assets.open("fonts/DejaVuSans.ttf").use { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it, true) }
    } catch (e: Exception) {
        null
    }

    fun visual(line: String): String = Shaper.visual(line)

    private var curFont: com.tom_roush.pdfbox.pdmodel.font.PDType0Font? = null
    private var fontTried = false

    // ---------- annotasiyaları vektor kimi PDF-ə yazmaq ----------

    // Göstərilən (döndərilmiş) səhifə koordinatlarını (yuxarı-sol, y aşağı) PDF istifadəçi fəzasına çevirir
    private fun displayMatrix(page: PDPage): Triple<Matrix, Float, Float> {
        val cb = page.cropBox
        val x0 = cb.lowerLeftX
        val y0 = cb.lowerLeftY
        val w = cb.width
        val h = cb.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Triple(Matrix(0f, 1f, 1f, 0f, x0, y0), h, w)
            180 -> Triple(Matrix(-1f, 0f, 0f, 1f, x0 + w, y0), w, h)
            270 -> Triple(Matrix(0f, -1f, -1f, 0f, x0 + w, y0 + h), h, w)
            else -> Triple(Matrix(1f, 0f, 0f, -1f, x0, y0 + h), w, h)
        }
    }

    private fun rgb(c: Int): PDColor = PDColor(
        floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f), PDDeviceRGB.INSTANCE
    )

    private fun alphaState(a: Float): PDExtendedGraphicsState {
        val gs = PDExtendedGraphicsState()
        gs.strokingAlphaConstant = a
        gs.nonStrokingAlphaConstant = a
        return gs
    }

    // Mətni Android ilə şəffaf şəklə çəkir (ərəb/azərbaycan hərfləri, birləşmə düzgün qalır)
    private fun textBitmap(a: Ann, W: Float, H: Float): Pair<Bitmap, RectF>? {
        val pt = Paint()
        val box = textBox(a, 0f, 0f, W, H, pt)
        if (box.width() <= 0f || box.height() <= 0f) return null
        val k = min(4f, 2400f / max(box.width(), box.height())).coerceAtLeast(1f)
        val bw = (box.width() * k).toInt().coerceAtLeast(1)
        val bh = (box.height() * k).toInt().coerceAtLeast(1)
        val bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.scale(k, k)
        c.translate(-box.left, -box.top)
        drawAnn(c, a, 0f, 0f, W, H, Paint())
        return Pair(bm, box)
    }

    private fun burn(doc: PDDocument, page: PDPage, anns: List<Ann>) {
        if (anns.isEmpty()) return
        if (!fontTried && anns.any { it.type == A_TEXT || it.type == A_NOTE }) {
            fontTried = true
            curFont = loadFont(doc)
        }
        val (m, W, H) = displayMatrix(page)
        val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
        try {
            cs.saveGraphicsState()
            cs.transform(m)
            for (a in anns) {
                try {
                    burnOne(doc, cs, a, W, H)
                } catch (e: Exception) {
                }
            }
            cs.restoreGraphicsState()
        } finally {
            cs.close()
        }
    }

    // Seçilə bilən real mətn (yerləşdirilmiş şrift). Glif yoxdursa false -> şəkil kimi yazılır
    private fun realText(cs: PDPageContentStream, a: Ann, W: Float, H: Float): Boolean {
        val font = curFont ?: return false
        val lines = textLines(a.text).map { visual(it) }
        try {
            for (ln in lines) font.encode(ln)
        } catch (e: Exception) {
            return false
        }
        val sw = a.w * W
        val x0 = a.p[0] * W
        val y0 = a.p[1] * H
        if (a.type == A_NOTE) {
            val box = textBox(a, 0f, 0f, W, H, Paint())
            cs.saveGraphicsState()
            cs.setNonStrokingColor(rgb(0xFFFFF59D.toInt()))
            cs.addRect(box.left, box.top, box.width(), box.height())
            cs.fill()
            cs.setNonStrokingColor(rgb(0xFFF9A825.toInt()))
            cs.addRect(box.left, box.top, box.width(), max(1f, sw * 0.18f))
            cs.fill()
            cs.restoreGraphicsState()
        }
        cs.saveGraphicsState()
        cs.setNonStrokingColor(rgb(a.ink))
        cs.beginText()
        cs.setFont(font, sw)
        var y = y0 + sw
        for (ln in lines) {
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x0, y))
            cs.showText(ln)
            y += sw * 1.2f
        }
        cs.endText()
        cs.restoreGraphicsState()
        return true
    }

    private fun burnOne(doc: PDDocument, cs: PDPageContentStream, a: Ann, W: Float, H: Float) {
        val n = a.p.size / 2
        if (n == 0) return
        fun X(i: Int) = a.p[2 * i] * W
        fun Y(i: Int) = a.p[2 * i + 1] * H
        when (a.type) {
            A_TEXT, A_NOTE -> {
                if (realText(cs, a, W, H)) return
                val (bm, box) = textBitmap(a, W, H) ?: return
                val img = LosslessFactory.createFromImage(doc, bm)
                cs.drawImage(img, Matrix(box.width(), 0f, 0f, -box.height(), box.left, box.bottom))
                bm.recycle()
                return
            }
            A_IMG -> {
                if (n < 2) return
                val bm = ImgCache.get(a.text) ?: return
                val img: PDImageXObject = if (bm.hasAlpha()) LosslessFactory.createFromImage(doc, bm)
                else JPEGFactory.createFromImage(doc, bm, 0.9f)
                val l = min(X(0), X(1))
                val r = max(X(0), X(1))
                val t = min(Y(0), Y(1))
                val b = max(Y(0), Y(1))
                cs.drawImage(img, Matrix(r - l, 0f, 0f, -(b - t), l, b))
                return
            }
        }
        val sw = a.w * W
        cs.saveGraphicsState()
        if (a.type == A_HL) cs.setGraphicsStateParameters(alphaState(0.4f))
        cs.setStrokingColor(rgb(a.ink))
        cs.setLineWidth(sw)
        cs.setLineJoinStyle(1)
        cs.setLineCapStyle(if (a.type == A_HL) 0 else 1)
        when (a.type) {
            A_PEN, A_HL -> {
                cs.moveTo(X(0), Y(0))
                if (n == 1) {
                    cs.lineTo(X(0) + 0.01f, Y(0))
                } else {
                    var px = X(0)
                    var py = Y(0)
                    if (a.type == A_PEN && n > 2) {
                        for (i in 1 until n - 1) {
                            val cx = X(i)
                            val cy = Y(i)
                            val mx = (cx + X(i + 1)) / 2f
                            val my = (cy + Y(i + 1)) / 2f
                            cs.curveTo(
                                px + 2f / 3f * (cx - px), py + 2f / 3f * (cy - py),
                                mx + 2f / 3f * (cx - mx), my + 2f / 3f * (cy - my), mx, my
                            )
                            px = mx
                            py = my
                        }
                    }
                    cs.lineTo(X(n - 1), Y(n - 1))
                }
                cs.stroke()
            }
            A_LINE, A_ARROW -> {
                if (n >= 2) {
                    val x0 = X(0); val y0 = Y(0); val x1 = X(1); val y1 = Y(1)
                    cs.moveTo(x0, y0)
                    cs.lineTo(x1, y1)
                    if (a.type == A_ARROW) {
                        val ang = atan2(y1 - y0, x1 - x0)
                        val hl = max(sw * 5f, W * 0.03f)
                        cs.moveTo(x1, y1)
                        cs.lineTo(x1 - hl * cos(ang - 0.5f), y1 - hl * sin(ang - 0.5f))
                        cs.moveTo(x1, y1)
                        cs.lineTo(x1 - hl * cos(ang + 0.5f), y1 - hl * sin(ang + 0.5f))
                    }
                    cs.stroke()
                }
            }
            A_RECT -> {
                if (n >= 2) {
                    cs.addRect(min(X(0), X(1)), min(Y(0), Y(1)), kotlin.math.abs(X(1) - X(0)), kotlin.math.abs(Y(1) - Y(0)))
                    cs.stroke()
                }
            }
            A_OVAL -> {
                if (n >= 2) {
                    val cx = (X(0) + X(1)) / 2f
                    val cy = (Y(0) + Y(1)) / 2f
                    val rx = kotlin.math.abs(X(1) - X(0)) / 2f
                    val ry = kotlin.math.abs(Y(1) - Y(0)) / 2f
                    val k = 0.5523f
                    cs.moveTo(cx + rx, cy)
                    cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
                    cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
                    cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
                    cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
                    cs.stroke()
                }
            }
        }
        cs.restoreGraphicsState()
    }

    // Orijinal PDF + qeydlər -> yeni PDF (mətn və vektorlar saxlanılır)
    fun export(src: File, anns: List<List<Ann>>, out: OutputStream, pages: Set<Int>? = null) {
        PDDocument.load(src).use { doc ->
            curFont = null
            fontTried = false
            val n = doc.numberOfPages
            for (i in 0 until min(n, anns.size)) burn(doc, doc.getPage(i), anns[i])
            if (pages != null) {
                for (i in n - 1 downTo 0) if (i !in pages) doc.removePage(i)
            }
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    // ---------- səhifələrin idarəsi ----------

    // specs sırası ilə yeni PDF qurur. docs[0] = cari sənəd, docs[1..] = əlavə olunan sənədlər
    fun rebuild(docs: List<File>, specs: List<PageSpec>, dest: File) {
        val opened = docs.map { PDDocument.load(it) }
        try {
            PDDocument().use { out ->
                for (s in specs) {
                    if (s.src < 0) {
                        out.addPage(PDPage(PDRectangle(s.w, s.h)))
                    } else {
                        val p = out.importPage(opened[s.doc].getPage(s.src))
                        p.rotation = (((p.rotation + s.rot) % 360) + 360) % 360
                    }
                }
                dest.outputStream().use { out.save(it) }
            }
        } finally {
            for (d in opened) try { d.close() } catch (e: Exception) {}
        }
    }

    // Şifrəli PDF-i açıb şifrəsiz işçi nüsxə yazır. Səhv şifrədə InvalidPasswordException atır
    fun decrypt(src: File, pw: String, dest: File) {
        PDDocument.load(src, pw).use { d ->
            d.isAllSecurityToBeRemoved = true
            dest.outputStream().use { d.save(it) }
        }
    }

    // Hazır PDF baytlarını AES-256 ilə şifrələyir
    fun encrypt(bytes: ByteArray, pw: String, out: OutputStream) {
        PDDocument.load(bytes).use { d ->
            val pol = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(pw, pw, com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission())
            pol.encryptionKeyLength = 256
            d.protect(pol)
            d.save(out)
        }
    }

    fun pageCount(f: File): Int = PDDocument.load(f).use { it.numberOfPages }

    // ---------- mətn axtarışı ----------

    class PageText(val text: String, val boxes: List<RectF?>)

    fun extract(f: File, progress: (Int, Int) -> Unit): List<PageText> {
        val out = ArrayList<PageText>()
        PDDocument.load(f).use { doc ->
            val n = doc.numberOfPages
            for (i in 0 until n) {
                progress(i, n)
                val page = doc.getPage(i)
                val (_, W, H) = displayMatrix(page)
                val sb = StringBuilder()
                val boxes = ArrayList<RectF?>()
                val st = object : PDFTextStripper() {
                    override fun writeString(text: String, tps: MutableList<TextPosition>) {
                        for (tp in tps) {
                            val u = tp.unicode ?: continue
                            val x = tp.xDirAdj / W
                            val y = (tp.yDirAdj - tp.heightDir) / H
                            val r = RectF(x, y - 0.002f, x + tp.widthDirAdj / W, tp.yDirAdj / H + 0.004f)
                            for (ch in u) {
                                sb.append(ch)
                                boxes.add(r)
                            }
                        }
                    }

                    override fun writeWordSeparator() {
                        sb.append(' ')
                        boxes.add(null)
                    }

                    override fun writeLineSeparator() {
                        sb.append(' ')
                        boxes.add(null)
                    }
                }
                st.sortByPosition = true
                st.startPage = i + 1
                st.endPage = i + 1
                try {
                    st.getText(doc)
                } catch (e: Exception) {
                }
                out.add(PageText(sb.toString(), boxes))
            }
        }
        return out
    }

    fun search(texts: List<PageText>, q: String): List<Hit> {
        val hits = ArrayList<Hit>()
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return hits
        for ((pi, pt) in texts.withIndex()) {
            val hay = pt.text.lowercase()
            var from = 0
            while (true) {
                val k = hay.indexOf(needle, from)
                if (k < 0) break
                val rects = ArrayList<RectF>()
                var cur: RectF? = null
                for (j in k until min(k + needle.length, pt.boxes.size)) {
                    val b = pt.boxes[j] ?: continue
                    val c = cur
                    if (c != null && kotlin.math.abs(c.top - b.top) < 0.01f) {
                        c.union(b)
                    } else {
                        cur = RectF(b)
                        rects.add(cur)
                    }
                }
                if (rects.isNotEmpty()) hits.add(Hit(pi, rects))
                from = k + max(1, needle.length)
            }
        }
        return hits
    }
}
