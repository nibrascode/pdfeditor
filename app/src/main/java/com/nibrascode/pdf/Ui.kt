package com.nibrascode.pdf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = 0xFFF4F4F5.toInt()
    val CANVAS = 0xFFECECEC.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val SURFACE2 = 0xFFF0F0F2.toInt()
    val GOLD = 0xFFD9342B.toInt()
    val GOLD_L = 0xFFF0564B.toInt()
    val GOLD_D = 0xFFB8261E.toInt()
    val TEXT = 0xFF1F1F22.toInt()
    val DIM = 0xFF85858C.toInt()
    val ICON = 0xFF2E2E33.toInt()
    val BAR = 0xFFF7F7F8.toInt()
    const val LINE = 0x1A000000
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.RECTANGLE
    g.cornerRadius = radius
    g.setColor(color)
    if (strokePx > 0) g.setStroke(strokePx, strokeColor)
    return g
}

fun oval(color: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    if (strokePx > 0) g.setStroke(strokePx, strokeColor)
    return g
}

fun goldGrad(radius: Float): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.GOLD_L, Ui.GOLD, Ui.GOLD_D))
    g.cornerRadius = radius
    return g
}

fun ripple(base: Drawable?, circle: Boolean, radius: Float = 0f): RippleDrawable {
    val mask: Drawable = if (circle) oval(Color.WHITE) else rounded(Color.WHITE, radius)
    return RippleDrawable(ColorStateList.valueOf(0x1F000000), base, mask)
}

fun Context.label(s: String, sp: Float, color: Int = Ui.TEXT, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = sp
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    return t
}

fun Context.space(h: Int): View {
    val v = View(this)
    v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
    return v
}

fun Context.tintedIcon(res: Int, color: Int, size: Int): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.imageTintList = ColorStateList.valueOf(color)
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    return v
}

fun Context.iconButton(
    res: Int,
    desc: String,
    tint: Int = Ui.TEXT,
    box: Int = 44,
    pad: Int = 10,
    onClick: () -> Unit
): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.imageTintList = ColorStateList.valueOf(tint)
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    v.contentDescription = desc
    val p = dp(pad)
    v.setPadding(p, p, p, p)
    v.background = ripple(null, true)
    v.setOnClickListener { onClick() }
    v.layoutParams = LinearLayout.LayoutParams(dp(box), dp(box))
    return v
}

// Qələm / marker / mətn / fiqur üçün kiçik önizləmə
class InkPreview(ctx: Context) : View(ctx) {
    var ink = Color.BLACK
    var tool = TL_PEN
    var amount = 0.4f
    private val p = Paint()
    private val dn = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.reset()
        p.isAntiAlias = true
        if (tool == TL_TEXT) {
            p.color = ink
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = widthFor(TL_TEXT, amount) * 360f * dn
            c.drawText("Aa", w / 2f, h / 2f + p.textSize * 0.35f, p)
            return
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = if (tool == TL_HL) Paint.Cap.BUTT else Paint.Cap.ROUND
        p.strokeWidth = widthFor(tool, amount) * 360f * dn
        p.color = if (tool == TL_HL) (ink and 0xFFFFFF) or 0x66000000 else ink
        c.drawLine(w * 0.18f, h / 2f, w * 0.82f, h / 2f, p)
    }
}
