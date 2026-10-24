package com.nibrascode.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import java.io.File
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Alətlər
const val TL_VIEW = 0
const val TL_PEN = 1
const val TL_HL = 2
const val TL_TEXT = 3
const val TL_SHAPE = 4
const val TL_ERASE = 5
const val TL_NOTE = 6
const val TL_SELECT = 7

// Annotasiya növləri
const val A_PEN = 0
const val A_HL = 1
const val A_TEXT = 2
const val A_LINE = 3
const val A_ARROW = 4
const val A_RECT = 5
const val A_OVAL = 6
const val A_IMG = 7
const val A_NOTE = 8

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

// Sürüşdürücü (0..1) -> səhifə eninə nisbətən real qalınlıq / ölçü
fun widthFor(tool: Int, v: Float): Float = when (tool) {
    TL_PEN -> lerp(0.002f, 0.016f, v)
    TL_HL -> lerp(0.012f, 0.05f, v)
    TL_TEXT, TL_NOTE -> lerp(0.016f, 0.06f, v)
    else -> lerp(0.002f, 0.014f, v)
}

// Koordinatlar səhifəyə nisbətən 0..1 aralığındadır
class Ann(val type: Int, val ink: Int, val w: Float, var text: String = "") {
    val p = ArrayList<Float>()

    fun add(x: Float, y: Float) {
        p.add(x)
        p.add(y)
    }
}

// old != null olarsa: yerdəyişmə / ölçü dəyişməsi (köhnə koordinatlar)
class Op(val ann: Ann, val added: Boolean, val idx: Int, val old: FloatArray? = null, val oldText: String? = null)

// Şəkil annotasiyaları üçün bitmap keşi (fayl yolu -> Bitmap)
object ImgCache {
    var dir: File? = null
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val d = dir ?: return null
        val f = File(d, name)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache.put(name, b)
        return b
    }
}

fun textLines(s: String): List<String> = s.split("\n")

fun a2f(a: Ann): FloatArray = FloatArray(a.p.size) { a.p[it] }

class PageData {
    val anns = ArrayList<Ann>()
    val undo = ArrayList<Op>()
    val redo = ArrayList<Op>()

    fun commit(a: Ann) {
        undo.add(Op(a, true, anns.indexOf(a)))
        redo.clear()
    }

    fun addAnn(a: Ann) {
        anns.add(a)
        commit(a)
    }

    fun removeAnn(a: Ann) {
        val i = anns.indexOf(a)
        if (i >= 0) {
            anns.removeAt(i)
            undo.add(Op(a, false, i))
            redo.clear()
        }
    }

    // Yerdəyişmə / mətn dəyişikliyi üçün tarixçə
    fun commitMove(a: Ann, old: FloatArray, oldText: String? = null) {
        undo.add(Op(a, true, -1, old, oldText))
        redo.clear()
    }

    private fun swapState(op: Op): Op {
        val cur = a2f(op.ann)
        val curText = op.ann.text
        val o = op.old!!
        op.ann.p.clear()
        for (v in o) op.ann.p.add(v)
        if (op.oldText != null) op.ann.text = op.oldText
        return Op(op.ann, true, -1, cur, if (op.oldText != null) curText else null)
    }

    fun doUndo(): Boolean {
        if (undo.isEmpty()) return false
        val op = undo.removeAt(undo.size - 1)
        if (op.old != null) {
            redo.add(swapState(op))
            return true
        }
        if (op.added) {
            anns.remove(op.ann)
        } else {
            anns.add(min(op.idx, anns.size), op.ann)
        }
        redo.add(op)
        return true
    }

    fun doRedo(): Boolean {
        if (redo.isEmpty()) return false
        val op = redo.removeAt(redo.size - 1)
        if (op.old != null) {
            undo.add(swapState(op))
            return true
        }
        if (op.added) {
            anns.add(min(op.idx, anns.size), op.ann)
        } else {
            anns.remove(op.ann)
        }
        undo.add(op)
        return true
    }
}

// Ekranda da, saxlanan PDF-də də eyni funksiya ilə çəkilir
fun drawAnn(c: Canvas, a: Ann, l: Float, t: Float, w: Float, h: Float, pt: Paint) {
    val n = a.p.size / 2
    if (n == 0) return
    pt.reset()
    pt.isAntiAlias = true
    val sw = a.w * w
    if (a.type == A_TEXT || a.type == A_NOTE) {
        pt.textSize = sw
        val lines = textLines(a.text)
        val x0 = l + a.p[0] * w
        val y0 = t + a.p[1] * h
        if (a.type == A_NOTE) {
            var bw = 0f
            for (ln in lines) bw = max(bw, pt.measureText(ln))
            val pad = sw * 0.5f
            val r = RectF(x0 - pad, y0 - pad, x0 + bw + pad, y0 + sw * 1.2f * lines.size + pad * 0.6f)
            pt.style = Paint.Style.FILL
            pt.color = 0xFFFFF59D.toInt()
            pt.setShadowLayer(sw * 0.25f, 0f, sw * 0.08f, 0x55000000)
            c.drawRoundRect(r, sw * 0.25f, sw * 0.25f, pt)
            pt.clearShadowLayer()
            pt.color = 0xFFF9A825.toInt()
            c.drawRect(r.left, r.top, r.right, r.top + max(1f, sw * 0.18f), pt)
        }
        pt.color = a.ink
        var y = y0 + sw
        for (ln in lines) {
            c.drawText(ln, x0, y, pt)
            y += sw * 1.2f
        }
        return
    }
    if (a.type == A_IMG) {
        if (n < 2) return
        val bm = ImgCache.get(a.text) ?: return
        val r = RectF(l + min(a.p[0], a.p[2]) * w, t + min(a.p[1], a.p[3]) * h, l + max(a.p[0], a.p[2]) * w, t + max(a.p[1], a.p[3]) * h)
        pt.isFilterBitmap = true
        c.drawBitmap(bm, null, r, pt)
        return
    }
    pt.style = Paint.Style.STROKE
    pt.strokeJoin = Paint.Join.ROUND
    pt.strokeCap = if (a.type == A_HL) Paint.Cap.BUTT else Paint.Cap.ROUND
    pt.strokeWidth = sw
    pt.color = if (a.type == A_HL) (a.ink and 0xFFFFFF) or 0x66000000 else a.ink
    when (a.type) {
        A_PEN, A_HL -> {
            if (n == 1) {
                c.drawPoint(l + a.p[0] * w, t + a.p[1] * h, pt)
            } else {
                val path = Path()
                path.moveTo(l + a.p[0] * w, t + a.p[1] * h)
                if (a.type == A_PEN && n > 2) {
                    for (i in 1 until n - 1) {
                        val cx = l + a.p[2 * i] * w
                        val cy = t + a.p[2 * i + 1] * h
                        val mx = (cx + l + a.p[2 * i + 2] * w) / 2f
                        val my = (cy + t + a.p[2 * i + 3] * h) / 2f
                        path.quadTo(cx, cy, mx, my)
                    }
                }
                path.lineTo(l + a.p[2 * n - 2] * w, t + a.p[2 * n - 1] * h)
                c.drawPath(path, pt)
            }
        }
        A_LINE, A_ARROW -> {
            if (n >= 2) {
                val x0 = l + a.p[0] * w
                val y0 = t + a.p[1] * h
                val x1 = l + a.p[2] * w
                val y1 = t + a.p[3] * h
                c.drawLine(x0, y0, x1, y1, pt)
                if (a.type == A_ARROW) {
                    val ang = atan2(y1 - y0, x1 - x0)
                    val hl = max(sw * 5f, w * 0.03f)
                    val da = 0.5f
                    c.drawLine(x1, y1, x1 - hl * cos(ang - da), y1 - hl * sin(ang - da), pt)
                    c.drawLine(x1, y1, x1 - hl * cos(ang + da), y1 - hl * sin(ang + da), pt)
                }
            }
        }
        A_RECT, A_OVAL -> {
            if (n >= 2) {
                val xa = l + a.p[0] * w
                val xb = l + a.p[2] * w
                val ya = t + a.p[1] * h
                val yb = t + a.p[3] * h
                val r = RectF(min(xa, xb), min(ya, yb), max(xa, xb), max(ya, yb))
                if (a.type == A_RECT) c.drawRect(r, pt) else c.drawOval(r, pt)
            }
        }
    }
}

// Pozan üçün: annotasiyanın piksel koordinatlarında xətti təsviri [x0,y0,x1,y1,...]
fun outline(a: Ann, l: Float, t: Float, w: Float, h: Float): FloatArray {
    val n = a.p.size / 2
    if ((a.type == A_RECT || a.type == A_OVAL || a.type == A_IMG) && n >= 2) {
        val x0 = l + min(a.p[0], a.p[2]) * w
        val x1 = l + max(a.p[0], a.p[2]) * w
        val y0 = t + min(a.p[1], a.p[3]) * h
        val y1 = t + max(a.p[1], a.p[3]) * h
        if (a.type == A_RECT || a.type == A_IMG) {
            return floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0)
        }
        val steps = 40
        val o1 = FloatArray((steps + 1) * 2)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val rx = (x1 - x0) / 2f
        val ry = (y1 - y0) / 2f
        for (i in 0..steps) {
            val ang = (2.0 * Math.PI * i / steps).toFloat()
            o1[2 * i] = cx + rx * cos(ang)
            o1[2 * i + 1] = cy + ry * sin(ang)
        }
        return o1
    }
    val o2 = FloatArray(n * 2)
    for (i in 0 until n) {
        o2[2 * i] = l + a.p[2 * i] * w
        o2[2 * i + 1] = t + a.p[2 * i + 1] * h
    }
    return o2
}

fun annsToJson(pages: List<PageData>): String {
    val root = JSONArray()
    for (pd in pages) {
        val arr = JSONArray()
        for (a in pd.anns) {
            val o = JSONObject()
            o.put("t", a.type)
            o.put("c", a.ink)
            o.put("w", a.w.toDouble())
            if (a.type == A_TEXT || a.type == A_NOTE || a.type == A_IMG) o.put("s", a.text)
            val p = JSONArray()
            for (v in a.p) p.put(v.toDouble())
            o.put("p", p)
            arr.put(o)
        }
        root.put(arr)
    }
    return root.toString()
}

fun jsonToAnns(s: String, pages: List<PageData>) {
    val root = JSONArray(s)
    val n = min(root.length(), pages.size)
    for (i in 0 until n) {
        val arr = root.getJSONArray(i)
        for (j in 0 until arr.length()) {
            val o = arr.getJSONObject(j)
            val a = Ann(o.getInt("t"), o.getInt("c"), o.getDouble("w").toFloat(), o.optString("s", ""))
            val p = o.getJSONArray("p")
            for (k in 0 until p.length()) a.p.add(p.getDouble(k).toFloat())
            pages[i].anns.add(a)
        }
    }
}

// Mətn / qeyd annotasiyasının çərçivəsi (piksel)
fun textBox(a: Ann, l: Float, t: Float, w: Float, h: Float, pt: Paint): RectF {
    val sw = a.w * w
    pt.reset()
    pt.textSize = sw
    val lines = textLines(a.text)
    var bw = 0f
    for (ln in lines) bw = max(bw, pt.measureText(ln))
    val x0 = l + a.p[0] * w
    val y0 = t + a.p[1] * h
    val pad = if (a.type == A_NOTE) sw * 0.5f else 0f
    return RectF(x0 - pad, y0 - pad, x0 + bw + pad, y0 + sw * 1.2f * lines.size + pad * 0.6f)
}

// Səhifə 90° döndəriləndə annotasiya koordinatlarını çevirir (cw = saat istiqamətində)
fun rotateAnn(a: Ann, cw: Boolean) {
    var i = 0
    while (i + 1 < a.p.size) {
        val x = a.p[i]
        val y = a.p[i + 1]
        if (cw) {
            a.p[i] = 1f - y
            a.p[i + 1] = x
        } else {
            a.p[i] = y
            a.p[i + 1] = 1f - x
        }
        i += 2
    }
}
