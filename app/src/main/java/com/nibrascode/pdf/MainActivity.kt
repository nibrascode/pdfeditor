@file:Suppress("DEPRECATION")

package com.nibrascode.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val REQ_OPEN = 1
private const val REQ_SAVE = 2
private const val REQ_MERGE = 3
private const val REQ_IMAGE = 4
private const val REQ_EXTRACT = 5
private const val REQ_SPLIT = 6
private const val REQ_PNG = 7
private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MAX_W = 3000

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var home: FrameLayout
    private lateinit var homeBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleName: TextView
    private lateinit var titleSub: TextView
    private lateinit var btnNight: ImageView
    private lateinit var btnUndo: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var btnRedo: ImageView
    private lateinit var pv: PageView
    private lateinit var pillLabel: TextView
    private lateinit var panel: LinearLayout
    private lateinit var shapeRow: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var preview: InkPreview
    private lateinit var dock: LinearLayout
    private lateinit var dockHost: View
    private lateinit var searchBar: LinearLayout
    private lateinit var searchEt: EditText
    private lateinit var searchCount: TextView
    private var hits: List<Hit> = emptyList()
    private var hitIdx = -1
    private var texts: List<PdfOps.PageText>? = null
    private var textsFor: String? = null
    private var docVer = 0

    private val toolBtns = ArrayList<ImageView>()
    private val swatches = ArrayList<View>()
    private val shapeBtns = ArrayList<ImageView>()

    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0

    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveAnnotationsNow() }
    private val pages = ArrayList<PageData>()
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var curId: String? = null
    private var docName = ""
    private var page = 0

    @Volatile
    private var gen = 0

    @Volatile
    private var hiGen = 0
    private var tool = TL_VIEW
    private var shape = 0
    private var inEditor = false
    private var backCb: Any? = null

    private val palette = intArrayOf(
        0xFF111827.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
        0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()
    )

    // Alət nömrəsinə görə (TL_VIEW=0 ... TL_ERASE=5)
    private val toolInk = intArrayOf(0, 0xFF1E88E5.toInt(), 0xFFFDD835.toInt(), 0xFF111827.toInt(), 0xFFE53935.toInt(), 0, 0xFF111827.toInt())
    private val toolAmt = floatArrayOf(0f, 0.30f, 0.50f, 0.40f, 0.30f, 0f, 0.30f)

    // ======================= həyat dövrü =======================

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = Store(this)
        PdfOps.init(this)
        ImgCache.dir = store.dir
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        home = buildHome()
        editor = buildEditor()
        editor.visibility = View.GONE
        root.addView(home, FrameLayout.LayoutParams(MP, MP))
        root.addView(editor, FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            readInsets(ins)
            applyInsets()
            ins
        }
        pv.night = store.night
        styleNight()
        styleTools()
        refreshHome()
        root.requestApplyInsets()

        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleIntent(i)
    }

    // «Bununla aç» (VIEW) və «Paylaş» (SEND) ilə gələn PDF-lər
    private fun handleIntent(inc: Intent?) {
        if (inc == null) return
        val u: Uri? = when (inc.action) {
            Intent.ACTION_VIEW -> inc.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) inc.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else inc.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (u != null) {
            val writable = inc.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            openUri(u, if (writable) u else null)
            inc.action = null
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(saveRunnable)
        saveAnnotationsNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(saveRunnable)
        val r = renderer
        val f = pfd
        renderer = null
        pfd = null
        exec.execute {
            try {
                r?.close()
                f?.close()
            } catch (e: Exception) {
            }
        }
        exec.shutdown()
    }

    override fun onBackPressed() {
        if (inEditor) goHome() else super.onBackPressed()
    }

    private fun setBackHandler(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (on && backCb == null) {
                val cb = OnBackInvokedCallback { goHome() }
                d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
                backCb = cb
            } else if (!on) {
                val cb = backCb
                if (cb is OnBackInvokedCallback) d.unregisterOnBackInvokedCallback(cb)
                backCb = null
            }
        }
    }

    // ======================= kənar boşluqlar (edge-to-edge) =======================

    private fun readInsets(ins: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insL = b.left
            insT = b.top
            insR = b.right
            insB = b.bottom
        } else {
            insL = ins.systemWindowInsetLeft
            insT = ins.systemWindowInsetTop
            insR = ins.systemWindowInsetRight
            insB = ins.systemWindowInsetBottom
        }
    }

    private fun applyInsets() {
        homeBox.setPadding(insL + dp(20), insT + dp(18), insR + dp(20), insB + dp(24))
        topBar.setPadding(insL + dp(6), insT, insR + dp(6), 0)
        topBar.layoutParams.height = dp(56) + insT
        topBar.requestLayout()
        val bottom = insB + dp(14)
        val dl = dockHost.layoutParams as FrameLayout.LayoutParams
        dl.bottomMargin = bottom
        dl.leftMargin = insL + dp(8)
        dl.rightMargin = insR + dp(8)
        dockHost.layoutParams = dl
        val pl = panel.layoutParams as FrameLayout.LayoutParams
        pl.bottomMargin = bottom + dp(60) + dp(10)
        pl.leftMargin = insL + dp(12)
        pl.rightMargin = insR + dp(12)
        panel.layoutParams = pl
        pv.setPads(0, bottom + dp(60) + dp(6))
    }

    // ======================= ana səhifə =======================

    private fun buildHome(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Ui.BG)
        val sc = ScrollView(this)
        sc.isFillViewport = true
        sc.overScrollMode = View.OVER_SCROLL_NEVER
        homeBox = LinearLayout(this)
        homeBox.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = FrameLayout(this)
        logo.background = rounded(Ui.SURFACE, dpf(18f), Ui.GOLD, dp(1))
        val logoImg = ImageView(this)
        logoImg.setImageResource(R.drawable.ic_launcher_fg)
        logoImg.scaleX = 1.7f
        logoImg.scaleY = 1.7f
        logo.addView(logoImg, FrameLayout.LayoutParams(MP, MP))
        head.addView(logo, LinearLayout.LayoutParams(dp(60), dp(60)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, 0, 0)
        col.addView(label("Nibras PDF", 26f, Ui.TEXT, true))
        val tag = label("Oxu · Yaz · Rənglə", 13f, Ui.GOLD)
        tag.letterSpacing = 0.06f
        col.addView(tag)
        head.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        homeBox.addView(head, LinearLayout.LayoutParams(MP, WC))
        homeBox.addView(space(26))

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.HORIZONTAL
        hero.gravity = Gravity.CENTER
        hero.background = ripple(goldGrad(dpf(20f)), false, dpf(20f))
        hero.elevation = dpf(8f)
        hero.addView(tintedIcon(R.drawable.ic_open, Ui.BG, 24))
        val ht = label("PDF aç", 17f, Ui.BG, true)
        ht.setPadding(dp(10), 0, 0, 0)
        hero.addView(ht)
        hero.setOnClickListener { pickPdf() }
        homeBox.addView(hero, LinearLayout.LayoutParams(MP, dp(60)))
        homeBox.addView(space(28))

        val sec = label("SON AÇILANLAR", 12f, Ui.DIM, true)
        sec.letterSpacing = 0.14f
        homeBox.addView(sec)
        homeBox.addView(space(12))
        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL
        homeBox.addView(recentBox, LinearLayout.LayoutParams(MP, WC))

        val note = label("Fayllar yalnız cihazında saxlanılır. İnternet tələb olunmur.", 12f, Ui.DIM)
        note.gravity = Gravity.CENTER
        note.setPadding(0, dp(24), 0, 0)
        homeBox.addView(note, LinearLayout.LayoutParams(MP, WC))

        sc.addView(homeBox, FrameLayout.LayoutParams(MP, WC))
        f.addView(sc, FrameLayout.LayoutParams(MP, MP))
        return f
    }

    private fun refreshHome() {
        recentBox.removeAllViews()
        val list = store.recents()
        if (list.isEmpty()) {
            val t = label("Hələ heç bir sənəd açılmayıb.\nBaşlamaq üçün «PDF aç» düyməsinə toxun.", 14f, Ui.DIM)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(36), 0, dp(36))
            recentBox.addView(t, LinearLayout.LayoutParams(MP, WC))
            return
        }
        for (r in list) recentBox.addView(recentCard(r))
    }

    private fun recentCard(r: Recent): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(10), dp(10), dp(6), dp(10))
        card.background = ripple(rounded(Ui.SURFACE, dpf(18f), Ui.LINE, dp(1)), false, dpf(18f))
        val lp = LinearLayout.LayoutParams(MP, WC)
        lp.bottomMargin = dp(10)
        card.layoutParams = lp

        val th = ImageView(this)
        th.clipToOutline = true
        val tf = store.thumb(r.id)
        val bm = if (tf.exists()) BitmapFactory.decodeFile(tf.path) else null
        if (bm != null) {
            th.background = rounded(0xFFFFFFFF.toInt(), dpf(8f))
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            th.setImageBitmap(bm)
        } else {
            th.background = rounded(Ui.SURFACE2, dpf(8f))
            th.scaleType = ImageView.ScaleType.CENTER_INSIDE
            th.setImageResource(R.drawable.ic_doc)
            th.imageTintList = ColorStateList.valueOf(Ui.GOLD)
            th.setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        card.addView(th, LinearLayout.LayoutParams(dp(54), dp(74)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(6), 0)
        val nm = label(r.name, 15f, Ui.TEXT, true)
        nm.maxLines = 2
        nm.ellipsize = TextUtils.TruncateAt.END
        val meta = label("${r.pages} səhifə · ${ago(r.time)}", 12f, Ui.DIM)
        meta.setPadding(0, dp(4), 0, 0)
        col.addView(nm)
        col.addView(meta)
        card.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        card.addView(iconButton(R.drawable.ic_delete, "Sil", Ui.DIM, 40, 9) { confirmRemove(r) })
        card.setOnClickListener { openRecent(r) }
        return card
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60000
        return when {
            m < 1 -> "indicə"
            m < 60 -> "$m dəq əvvəl"
            m < 1440 -> "${m / 60} saat əvvəl"
            else -> "${m / 1440} gün əvvəl"
        }
    }

    private fun confirmRemove(r: Recent) {
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Siyahıdan silinsin?")
            .setMessage("«${r.name}» və onun qeydləri bu cihazdan silinəcək. Orijinal fayla toxunulmur.")
            .setPositiveButton("Sil") { _, _ ->
                store.remove(r.id)
                refreshHome()
            }
            .setNegativeButton("Ləğv et", null)
            .show()
    }

    // ======================= redaktor =======================

    private fun buildEditor(): LinearLayout {
        val e = LinearLayout(this)
        e.orientation = LinearLayout.VERTICAL
        e.setBackgroundColor(Ui.BG)

        // ---- üst panel ----
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(Ui.BG)
        topBar.layoutParams = LinearLayout.LayoutParams(MP, dp(56))
        topBar.addView(iconButton(R.drawable.ic_back, "Geri") { goHome() })
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setPadding(dp(4), 0, dp(4), 0)
        titleName = label("", 15f, Ui.TEXT, true)
        titleName.maxLines = 1
        titleName.ellipsize = TextUtils.TruncateAt.END
        titleSub = label("", 11f, Ui.DIM)
        tcol.addView(titleName)
        tcol.addView(titleSub)
        topBar.addView(tcol, LinearLayout.LayoutParams(0, WC, 1f))
        btnNight = iconButton(R.drawable.ic_search, "Axtar", Ui.TEXT, 40, 9) { openSearch() }
        btnUndo = iconButton(R.drawable.ic_undo, "Geri al", Ui.TEXT, 40, 9) { pv.undo() }
        btnRedo = iconButton(R.drawable.ic_redo, "Təkrar et", Ui.TEXT, 40, 9) { pv.redo() }
        val btnSave = iconButton(R.drawable.ic_save, "Yadda saxla", Ui.GOLD, 40, 9) { save() }
        btnMore = iconButton(R.drawable.ic_more, "Daha çox", Ui.TEXT, 40, 9) { showMenu() }
        topBar.addView(btnNight)
        topBar.addView(btnUndo)
        topBar.addView(btnRedo)
        topBar.addView(btnSave)
        topBar.addView(btnMore)
        e.addView(topBar)

        // ---- səhifə sahəsi ----
        val host = FrameLayout(this)
        pv = PageView(this)
        pv.hint = "Yüklənir…"
        pv.canFlip = { d -> page + d in 0 until pages.size }
        pv.onFlip = { d -> showPage(page + d) }
        pv.onText = { nx, ny, a -> askText(nx, ny, a, false) }
        pv.onNote = { nx, ny, a -> askText(nx, ny, a, true) }
        pv.onSettle = { s -> onZoomSettled(s) }
        pv.onHistory = { refreshHistory() }
        host.addView(pv, FrameLayout.LayoutParams(MP, MP))

        // səhifə nömrəsi
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = rounded(0xE6101A2E.toInt(), dpf(18f), Ui.LINE, dp(1))
        pill.elevation = dpf(6f)
        pill.addView(iconButton(R.drawable.ic_chev_l, "Əvvəlki səhifə", Ui.TEXT, 36, 8) { showPage(page - 1) })
        pillLabel = label("1 / 1", 13f, Ui.TEXT, true)
        pillLabel.gravity = Gravity.CENTER
        pillLabel.minWidth = dp(54)
        pillLabel.setOnClickListener { jumpDialog() }
        pill.addView(pillLabel, LinearLayout.LayoutParams(WC, MP))
        pill.addView(iconButton(R.drawable.ic_chev_r, "Növbəti səhifə", Ui.TEXT, 36, 8) { showPage(page + 1) })
        val plp = FrameLayout.LayoutParams(WC, dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        plp.topMargin = dp(10)
        host.addView(pill, plp)

        searchBar = buildSearchBar()
        val sbl = FrameLayout.LayoutParams(MP, dp(48), Gravity.TOP)
        sbl.setMargins(dp(10), dp(8), dp(10), 0)
        host.addView(searchBar, sbl)

        // ---- seçimlər paneli ----
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(0xF01D2B49.toInt(), dpf(22f), Ui.LINE, dp(1))
        panel.elevation = dpf(10f)
        panel.setPadding(dp(14), dp(8), dp(14), dp(12))
        panel.visibility = View.GONE

        shapeRow = LinearLayout(this)
        shapeRow.orientation = LinearLayout.HORIZONTAL
        shapeRow.gravity = Gravity.CENTER
        val shapeIcons = intArrayOf(R.drawable.ic_s_line, R.drawable.ic_s_arrow, R.drawable.ic_s_rect, R.drawable.ic_s_oval)
        val shapeDescs = arrayOf("Xətt", "Ox", "Düzbucaqlı", "Elips")
        shapeIcons.forEachIndexed { i, res ->
            val b = iconButton(res, shapeDescs[i], Ui.TEXT, 44, 10) {
                shape = i
                pv.shape = i
                refreshShapeBtns()
            }
            val lp = b.layoutParams as LinearLayout.LayoutParams
            lp.setMargins(dp(4), 0, dp(4), 0)
            shapeBtns.add(b)
            shapeRow.addView(b, lp)
        }
        panel.addView(shapeRow, LinearLayout.LayoutParams(MP, WC))

        val swRow = LinearLayout(this)
        swRow.orientation = LinearLayout.HORIZONTAL
        swRow.gravity = Gravity.CENTER
        for (c in palette) {
            val v = View(this)
            v.setOnClickListener {
                toolInk[tool] = c
                pv.ink = c
                preview.ink = c
                preview.invalidate()
                refreshSwatches()
            }
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
            lp.setMargins(dp(5), dp(8), dp(5), dp(8))
            swRow.addView(v, lp)
        }
        panel.addView(swRow, LinearLayout.LayoutParams(MP, WC))

        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        sizeRow.gravity = Gravity.CENTER_VERTICAL
        seek = SeekBar(this)
        seek.max = 100
        seek.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setAmount(p / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        sizeRow.addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f))
        preview = InkPreview(this)
        preview.background = rounded(0xFFFFFFFF.toInt(), dpf(10f))
        val pvlp = LinearLayout.LayoutParams(dp(64), dp(38))
        pvlp.leftMargin = dp(12)
        sizeRow.addView(preview, pvlp)
        panel.addView(sizeRow, LinearLayout.LayoutParams(MP, WC))

        // ---- alətlər (yuxarıdakı panelin altında üzən dok) ----
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.background = rounded(0xF2101A2E.toInt(), dpf(32f), Ui.LINE, dp(1))
        dock.elevation = dpf(12f)
        dock.setPadding(dp(6), dp(6), dp(6), dp(6))
        val icons = intArrayOf(
            R.drawable.ic_cursor, R.drawable.ic_pen, R.drawable.ic_marker,
            R.drawable.ic_text, R.drawable.ic_shape, R.drawable.ic_eraser, R.drawable.ic_note
        )
        val descs = arrayOf("Oxu və sürüşdür", "Qələm", "Marker", "Mətn", "Fiqur", "Pozan", "Yapışqan qeyd")
        icons.forEachIndexed { i, res ->
            val b = ImageView(this)
            b.setImageResource(res)
            b.scaleType = ImageView.ScaleType.FIT_CENTER
            b.contentDescription = descs[i]
            b.setPadding(dp(12), dp(12), dp(12), dp(12))
            b.setOnClickListener {
                setTool(i)
                b.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            toolBtns.add(b)
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.setMargins(dp(2), 0, dp(2), 0)
            dock.addView(b, lp)
        }
        // Şəkil və imza əlavə etmək (alət deyil, birdəfəlik əməliyyat)
        val sep = View(this)
        sep.setBackgroundColor(Ui.LINE)
        val slp = LinearLayout.LayoutParams(dp(1), dp(28))
        slp.setMargins(dp(4), 0, dp(4), 0)
        dock.addView(sep, slp)
        dock.addView(iconButton(R.drawable.ic_image, "Şəkil əlavə et", Ui.TEXT, 48, 12) { pickImage() })
        dock.addView(iconButton(R.drawable.ic_sign, "İmza", Ui.TEXT, 48, 12) { signDialog() })
        val dockScroll = android.widget.HorizontalScrollView(this)
        dockScroll.isHorizontalScrollBarEnabled = false
        dockScroll.overScrollMode = View.OVER_SCROLL_NEVER
        dockScroll.addView(dock, FrameLayout.LayoutParams(WC, dp(60)))
        dockHost = dockScroll
        host.addView(dockScroll, FrameLayout.LayoutParams(WC, dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        host.addView(panel, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))

        e.addView(host, LinearLayout.LayoutParams(MP, 0, 1f))
        return e
    }

    private fun setTool(t: Int) {
        tool = t
        pv.tool = t
        if ((t >= TL_PEN && t <= TL_SHAPE) || t == TL_NOTE) {
            pv.ink = toolInk[t]
            pv.amount = toolAmt[t]
        }
        pv.shape = shape
        styleTools()
        updatePanel()
    }

    private fun styleTools() {
        toolBtns.forEachIndexed { i, b ->
            val sel = i == tool
            b.imageTintList = ColorStateList.valueOf(if (sel) Ui.BG else Ui.TEXT)
            b.background = if (sel) oval(Ui.GOLD) else ripple(null, true)
        }
    }

    private fun updatePanel() {
        panel.animate().cancel()
        val show = (tool >= TL_PEN && tool <= TL_SHAPE) || tool == TL_NOTE
        if (show) {
            seek.progress = (toolAmt[tool] * 100f).toInt()
            preview.tool = if (tool == TL_NOTE) TL_TEXT else tool
            preview.ink = toolInk[tool]
            preview.amount = toolAmt[tool]
            preview.invalidate()
            shapeRow.visibility = if (tool == TL_SHAPE) View.VISIBLE else View.GONE
            refreshSwatches()
            refreshShapeBtns()
            if (panel.visibility != View.VISIBLE) {
                panel.alpha = 0f
                panel.visibility = View.VISIBLE
                panel.animate().alpha(1f).setDuration(160).start()
            } else {
                panel.alpha = 1f
            }
        } else if (panel.visibility == View.VISIBLE) {
            panel.animate().alpha(0f).setDuration(120).withEndAction { panel.visibility = View.GONE }.start()
        }
    }

    private fun refreshSwatches() {
        swatches.forEachIndexed { i, v ->
            val sel = palette[i] == toolInk[tool]
            v.background = oval(palette[i], if (sel) Color.WHITE else Ui.LINE, dp(if (sel) 3 else 1))
            v.scaleX = if (sel) 1.15f else 1f
            v.scaleY = if (sel) 1.15f else 1f
        }
    }

    private fun refreshShapeBtns() {
        shapeBtns.forEachIndexed { i, b ->
            val sel = i == shape
            b.imageTintList = ColorStateList.valueOf(if (sel) Ui.GOLD else Ui.TEXT)
            b.background = if (sel) rounded(0x22FFFFFF, dpf(12f), Ui.GOLD, dp(1)) else ripple(null, true)
        }
    }

    private fun setAmount(v: Float) {
        toolAmt[tool] = v
        pv.amount = v
        preview.amount = v
        preview.invalidate()
    }

    private fun toggleNight() {
        val v = !pv.night
        pv.night = v
        store.night = v
        styleNight()
    }

    private fun styleNight() {
    }

    private fun refreshHistoryButtons() {
        btnUndo.alpha = if (pv.canUndo()) 1f else 0.3f
        btnRedo.alpha = if (pv.canRedo()) 1f else 0.3f
    }

    // Hər dəyişiklikdən sonra qeydlər avtomatik saxlanılır
    private fun refreshHistory() {
        refreshHistoryButtons()
        ui.removeCallbacks(saveRunnable)
        ui.postDelayed(saveRunnable, 900)
    }

    // ======================= faylı açmaq =======================

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        startActivityForResult(i, REQ_OPEN)
    }

    // Üzərinə yaz (orijinal fayla yazma icazəsi varsa), yoxsa «Fərqli saxla»
    private fun save() {
        val id = curId ?: return
        val u = store.uri(id)
        if (u == null) {
            saveAs()
            return
        }
        savePdf(Uri.parse(u), false)
    }

    private fun saveAs() {
        if (renderer == null) return
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_TITLE, baseName() + "_redakte.pdf")
        startActivityForResult(i, REQ_SAVE)
    }

    private fun baseName(): String = docName.replace(Regex("(?i)\\.pdf$"), "").ifEmpty { "sened" }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                var keep: Uri? = null
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    keep = u
                } catch (e: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e2: Exception) {
                    }
                }
                openUri(u, keep)
            }
            REQ_SAVE -> savePdf(u, true)
            //REQ_MERGE -> mergePdf(u)
            REQ_IMAGE -> insertImage(u)
            //REQ_EXTRACT -> writePending(u)
            //REQ_SPLIT -> splitInto(u)
            //REQ_PNG -> writePending(u)
        }
    }

    private fun queryName(u: Uri): String? {
        return try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openUri(uri: Uri, writeUri: Uri? = null) {
        val (dlg, _) = progressDialog("Açılır…")
        val name = queryName(uri) ?: "document.pdf"
        exec.execute {
            try {
                val inp = contentResolver.openInputStream(uri) ?: throw IOException("null stream")
                val md = MessageDigest.getInstance("SHA-1")
                val tmp = File(store.dir, "tmp_" + System.nanoTime())
                DigestInputStream(inp, md).use { d -> tmp.outputStream().use { o -> d.copyTo(o) } }
                val id = md.digest().joinToString("") { b -> "%02x".format(b) }.substring(0, 16)
                val dest = store.pdf(id)
                if (dest.exists()) {
                    tmp.delete()
                } else if (!tmp.renameTo(dest)) {
                    throw IOException("rename failed")
                }
                store.setUri(id, writeUri?.toString())
                loadStored(id, name, dlg)
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("PDF açıla bilmədi")
                }
            }
        }
    }

    private fun openRecent(r: Recent) {
        if (!store.pdf(r.id).exists()) {
            store.remove(r.id)
            refreshHome()
            toast("Fayl tapılmadı")
            return
        }
        val (dlg, _) = progressDialog("Açılır…")
        exec.execute { loadStored(r.id, r.name, dlg) }
    }

    // exec thread-də işləyir
    private fun loadStored(id: String, name: String, dlg: AlertDialog) {
        try {
            val fd = ParcelFileDescriptor.open(store.pdf(id), ParcelFileDescriptor.MODE_READ_ONLY)
            val r = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
            val n = r.pageCount
            val list = ArrayList<PageData>()
            repeat(n) { list.add(PageData()) }
            try {
                val af = store.ann(id)
                if (af.exists()) jsonToAnns(af.readText(), list)
            } catch (e: Exception) {
            }
            runOnUiThread {
                dlg.dismiss()
                installDoc(id, name, fd, r, list)
            }
        } catch (e: Throwable) {
            val fresh = store.recents().none { it.id == id }
            if (fresh) store.pdf(id).delete()
            runOnUiThread {
                dlg.dismiss()
                toast(if (e is SecurityException) "Parollu PDF dəstəklənmir" else "PDF açıla bilmədi")
            }
        }
    }

    private fun installDoc(id: String, name: String, fd: ParcelFileDescriptor, r: PdfRenderer, list: ArrayList<PageData>) {
        saveAnnotationsNow()
        val oldR = renderer
        val oldFd = pfd
        renderer = r
        pfd = fd
        curId = id
        docName = name
        pages.clear()
        pages.addAll(list)
        resetDocState()
        exec.execute {
            try {
                oldR?.close()
                oldFd?.close()
            } catch (e: Exception) {
            }
        }
        store.touch(id, name, list.size)
        titleName.text = name
        titleSub.text = "${list.size} səhifə"
        pv.clear()
        pv.hint = "Yüklənir…"
        styleNight()
        setTool(TL_VIEW)
        showEditor()
        showPage(store.lastPage(id).coerceIn(0, list.size - 1))
        refreshHistoryButtons()
    }

    private fun resetDocState() {
        docVer++
        texts = null
        textsFor = null
        if (::searchBar.isInitialized) closeSearch()
    }

    private fun closeDoc() {
        val r = renderer
        val f = pfd
        renderer = null
        pfd = null
        curId = null
        pages.clear()
        resetDocState()
        exec.execute {
            try {
                r?.close()
                f?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun showEditor() {
        inEditor = true
        home.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.alpha = 0f
        editor.animate().alpha(1f).setDuration(200).start()
        setBackHandler(true)
    }

    private fun goHome() {
        ui.removeCallbacks(saveRunnable)
        saveAnnotationsNow()
        closeDoc()
        pv.clear()
        inEditor = false
        editor.visibility = View.GONE
        home.visibility = View.VISIBLE
        home.alpha = 0f
        home.animate().alpha(1f).setDuration(180).start()
        refreshHome()
        setBackHandler(false)
    }

    private fun saveAnnotationsNow() {
        val id = curId ?: return
        if (pages.isEmpty()) return
        try {
            store.ann(id).writeText(annsToJson(pages))
        } catch (e: Exception) {
        }
    }

    // ======================= səhifələrin çəkilişi =======================

    private fun showPage(i: Int) {
        val r = renderer ?: return
        if (i < 0 || i >= pages.size) return
        page = i
        curId?.let { store.setLastPage(it, i) }
        pillLabel.text = "${i + 1} / ${pages.size}"
        val my = ++gen
        val tw = (resources.displayMetrics.widthPixels * 1.6f).toInt().coerceIn(900, 2000)
        val id = curId
        exec.execute {
            if (my != gen) return@execute
            try {
                val res = renderPage(r, i, tw)
                if (i == 0 && id != null) makeThumb(id, res.first)
                runOnUiThread {
                    if (my == gen && r === renderer) {
                        pv.setPage(res.first, pages[i], res.second)
                        applyPageHits()
                        refreshHistoryButtons()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { pv.cancelFlip() }
            }
        }
    }

    private fun renderPage(r: PdfRenderer, i: Int, tw: Int): Pair<Bitmap, Float> {
        val pg = r.openPage(i)
        try {
            val ratio = pg.height.toFloat() / pg.width
            val th = (tw * ratio).toInt().coerceIn(1, 7000)
            val bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return Pair(bm, ratio)
        } finally {
            pg.close()
        }
    }

    private fun makeThumb(id: String, bm: Bitmap) {
        val f = store.thumb(id)
        if (f.exists()) return
        try {
            val w = 280
            val h = (w * bm.height.toFloat() / bm.width).toInt().coerceAtLeast(1)
            val t = Bitmap.createScaledBitmap(bm, w, h, true)
            f.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            t.recycle()
        } catch (e: Exception) {
        }
    }

    // Yaxınlaşdırdıqdan sonra səhifəni daha yüksək keyfiyyətdə yenidən çəkir (mətn kəskin görünsün)
    private fun onZoomSettled(scale: Float) {
        val r = renderer ?: return
        val need = (resources.displayMetrics.widthPixels * scale * 1.2f).toInt()
        val have = pv.bitmapWidth()
        if (scale < 1.4f || have <= 0 || have >= need || have >= MAX_W) return
        val tw = min(MAX_W, need)
        val my = gen
        val hg = ++hiGen
        val i = page
        exec.execute {
            if (my != gen || hg != hiGen) return@execute
            try {
                val res = renderPage(r, i, tw)
                runOnUiThread {
                    if (my == gen && r === renderer) pv.replaceBitmap(res.first, res.second)
                }
            } catch (e: Throwable) {
            }
        }
    }

    // ======================= saxlamaq / paylaşmaq =======================

    private fun snapshot(): List<List<Ann>> = pages.map { ArrayList(it.anns) }

    // Qeydləri vektor kimi orijinal PDF-ə yazır (mətn seçilə bilən qalır); alınmasa şəkil kimi yazır. exec thread-də
    private fun exportTo(os: java.io.OutputStream, snap: List<List<Ann>>, only: Set<Int>? = null, tv: TextView? = null) {
        val id = curId ?: throw IOException("no doc")
        try {
            val buf = java.io.ByteArrayOutputStream()
            PdfOps.export(store.pdf(id), snap, buf, only)
            buf.writeTo(os)
        } catch (e: Throwable) {
            rasterExport(os, snap, only, tv)
        }
    }

    private fun rasterExport(os: java.io.OutputStream, snap: List<List<Ann>>, only: Set<Int>?, tv: TextView?) {
        val r = renderer ?: throw IOException("no renderer")
        val doc = PdfDocument()
        val pt = Paint()
        val bp = Paint()
        bp.isFilterBitmap = true
        var k = 0
        for (i in 0 until min(snap.size, r.pageCount)) {
            if (only != null && i !in only) continue
            if (tv != null) runOnUiThread { tv.text = "Saxlanılır… " + (i + 1) + "/" + snap.size }
            val pg = r.openPage(i)
            val wPt = pg.width
            val hPt = pg.height
            val sc = min(2.5f, 3200f / max(wPt, hPt))
            val bm = Bitmap.createBitmap((wPt * sc).toInt(), (hPt * sc).toInt(), Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            try {
                pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } finally {
                pg.close()
            }
            val info = PdfDocument.PageInfo.Builder(wPt, hPt, ++k).create()
            val p = doc.startPage(info)
            p.canvas.drawBitmap(bm, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), bp)
            for (a in snap[i]) drawAnn(p.canvas, a, 0f, 0f, wPt.toFloat(), hPt.toFloat(), pt)
            doc.finishPage(p)
            bm.recycle()
        }
        doc.writeTo(os)
        doc.close()
    }

    private fun savePdf(out: Uri, isNew: Boolean) {
        if (renderer == null) return
        val snap = snapshot()
        val (dlg, tv) = progressDialog("Saxlanılır…")
        exec.execute {
            try {
                val os = contentResolver.openOutputStream(out, "wt") ?: throw IOException("null stream")
                os.use { exportTo(it, snap, null, tv) }
                runOnUiThread {
                    dlg.dismiss()
                    showSaved(out, isNew)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    if (!isNew) {
                        toast("Orijinal fayla yazmaq olmadı — yeni fayl seç")
                        curId?.let { store.setUri(it, null) }
                        saveAs()
                    } else {
                        toast("Saxlama xətası")
                    }
                }
            }
        }
    }

    // Paylaşmaq / çap üçün müvəqqəti fayl (cache/share). exec thread-də
    private fun exportTemp(snap: List<List<Ann>>, only: Set<Int>? = null, suffix: String = ""): File {
        val dir = ShareProvider.dir(this)
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000) it.delete() }
        val f = File(dir, baseName().replace(Regex("[\\\\/:*?\"<>|]"), "_") + suffix + ".pdf")
        f.outputStream().use { exportTo(it, snap, only) }
        return f
    }

    private fun sharePdf() {
        if (renderer == null) return
        val snap = snapshot()
        val (dlg, _) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                val f = exportTemp(snap)
                runOnUiThread {
                    dlg.dismiss()
                    shareUri(ShareProvider.uriFor(f))
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Paylaşmaq mümkün olmadı")
                }
            }
        }
    }

    private fun printPdf() {
        if (renderer == null) return
        val snap = snapshot()
        val (dlg, _) = progressDialog("Çapa hazırlanır…")
        exec.execute {
            try {
                val f = exportTemp(snap, null, "_cap")
                runOnUiThread {
                    dlg.dismiss()
                    val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
                    pm.print(docName, PdfPrintAdapter(f, baseName() + ".pdf"), null)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Çap mümkün olmadı")
                }
            }
        }
    }

    private fun showSaved(uri: Uri, isNew: Boolean) {
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Saxlandı ✓")
            .setMessage(if (isNew) "PDF yeni faylda saxlanıldı. İstəsən indi paylaşa bilərsən." else "Dəyişikliklər orijinal fayla yazıldı.")
            .setPositiveButton("Paylaş") { _, _ -> shareUri(uri) }
            .setNegativeButton("Bağla", null)
            .show()
    }

    private fun shareUri(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "application/pdf"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.clipData = android.content.ClipData.newRawUri("", uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Paylaş"))
        } catch (e: Exception) {
            toast("Paylaşmaq mümkün olmadı")
        }
    }

    // ======================= dialoqlar =======================

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun progressDialog(msg: String): Pair<AlertDialog, TextView> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(24), dp(22), dp(24), dp(22))
        val pb = ProgressBar(this)
        pb.indeterminateTintList = ColorStateList.valueOf(Ui.GOLD)
        box.addView(pb, LinearLayout.LayoutParams(dp(36), dp(36)))
        val tv = label(msg, 15f, Ui.TEXT)
        tv.setPadding(dp(18), 0, 0, 0)
        box.addView(tv)
        val d = AlertDialog.Builder(this, R.style.NbDialog).setView(box).setCancelable(false).create()
        d.show()
        return Pair(d, tv)
    }

    private fun askText(nx: Float, ny: Float, a: Ann?, note: Boolean) {
        val et = EditText(this)
        et.setText(a?.text ?: "")
        et.setSelection(et.text.length)
        et.hint = "Mətn yaz…"
        et.setTextColor(Ui.TEXT)
        et.setHintTextColor(Ui.DIM)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.minLines = 2
        et.gravity = Gravity.TOP or Gravity.START
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MP, WC))
        val b = AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle(if (note) (if (a == null) "Yapışqan qeyd" else "Qeydi dəyiş") else if (a == null) "Mətn əlavə et" else "Mətni dəyiş")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> commitText(a, et.text.toString().trim(), nx, ny, note) }
            .setNegativeButton("Ləğv et", null)
        if (a != null) b.setNeutralButton("Sil") { _, _ -> pv.removeAnn(a) }
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun commitText(a: Ann?, s: String, nx: Float, ny: Float, note: Boolean) {
        if (a == null) {
            if (s.isNotEmpty()) {
                if (note) pv.addNote(s, nx, ny) else pv.addText(s, nx, ny)
            }
        } else if (s.isEmpty()) {
            pv.removeAnn(a)
        } else {
            pv.editText(a, s)
        }
    }

    // ======================= şəkil və imza =======================

    private fun pickImage() {
        if (renderer == null) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, REQ_IMAGE)
    }

    // Bitmap-i sənədin qovluğunda saxlayır və səhifəyə qoyur
    private fun placeBitmap(bm: Bitmap, png: Boolean, wFrac: Float) {
        val id = curId ?: return
        val name = id + "_img_" + System.nanoTime() + if (png) ".png" else ".jpg"
        File(store.dir, name).outputStream().use {
            bm.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
        }
        pv.addImage(name, bm.height.toFloat() / bm.width, wFrac)
        setTool(TL_VIEW)
        toast("Yerini dəyişmək üçün sürüşdür, küncdən böyüt")
    }

    private fun insertImage(u: Uri) {
        val (dlg, _) = progressDialog("Şəkil hazırlanır…")
        exec.execute {
            try {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var ss = 1
                while (max(o.outWidth, o.outHeight) / ss > 3200) ss *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = ss
                var bm = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: throw IOException()
                val m = max(bm.width, bm.height)
                if (m > 1800) {
                    val k = 1800f / m
                    bm = Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true)
                }
                val png = bm.hasAlpha()
                val b2 = bm
                runOnUiThread {
                    dlg.dismiss()
                    placeBitmap(b2, png, 0.5f)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Şəkil açıla bilmədi")
                }
            }
        }
    }

    private fun signDialog() {
        if (renderer == null) return
        val saved = store.signature?.let { File(store.dir, it) }?.takeIf { it.exists() }
        if (saved != null) {
            AlertDialog.Builder(this, R.style.NbDialog)
                .setTitle("İmza")
                .setItems(arrayOf("Saxlanmış imzanı qoy", "Yeni imza çək")) { _, w ->
                    if (w == 0) {
                        val bm = BitmapFactory.decodeFile(saved.path)
                        if (bm != null) placeBitmap(bm, true, 0.3f)
                    } else {
                        drawSignature()
                    }
                }
                .show()
        } else {
            drawSignature()
        }
    }

    private fun drawSignature() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(8), dp(18), 0)
        val pad = SignPad(this)
        pad.background = rounded(Color.WHITE, dpf(12f))
        pad.clipToOutline = true
        col.addView(pad, LinearLayout.LayoutParams(MP, dp(200)))
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER
        row.setPadding(0, dp(10), 0, 0)
        for (c in intArrayOf(0xFF111827.toInt(), 0xFF1E3A8A.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt())) {
            val v = View(this)
            v.background = oval(c, Ui.LINE, dp(1))
            v.setOnClickListener { pad.ink = c }
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
            lp.setMargins(dp(6), 0, dp(6), 0)
            row.addView(v, lp)
        }
        val clr = label("Təmizlə", 14f, Ui.GOLD, true)
        clr.setPadding(dp(16), dp(6), dp(6), dp(6))
        clr.setOnClickListener { pad.clear() }
        row.addView(clr)
        col.addView(row)
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("İmzanı çək")
            .setView(col)
            .setPositiveButton("Qoy") { _, _ ->
                val bm = pad.export()
                if (bm != null) {
                    try {
                        val name = "signature_" + System.currentTimeMillis() + ".png"
                        store.signature?.let { File(store.dir, it).delete() }
                        File(store.dir, name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        store.signature = name
                    } catch (e: Exception) {
                    }
                    placeBitmap(bm, true, 0.3f)
                }
            }
            .setNegativeButton("Ləğv et", null)
            .show()
    }

    // ======================= menyu =======================

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Yadda saxla" to { save() })
        items.add("Fərqli saxla…" to { saveAs() })
        items.add("Paylaş" to { sharePdf() })
        items.add("Çap et" to { printPdf() })
        items.add((if (pv.night) "Gündüz rejimi" else "Gecə rejimi") to { toggleNight() })
        items.add("Səhifəyə keç…" to { jumpDialog() })
        moreItems(items)
        val pm = android.widget.PopupMenu(this, btnMore)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, it.first) }
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun moreItems(items: ArrayList<Pair<String, () -> Unit>>) {
    }

    // ======================= mətn axtarışı =======================

    private fun openSearch() {
        if (renderer == null) return
        searchBar.visibility = View.VISIBLE
        searchEt.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchEt, 0)
    }

    private fun closeSearch() {
        searchBar.visibility = View.GONE
        hits = emptyList()
        hitIdx = -1
        searchCount.text = ""
        pv.hits = emptyList()
        pv.curHits = emptyList()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEt.windowToken, 0)
    }

    private fun runSearch() {
        val q = searchEt.text.toString()
        val id = curId ?: return
        if (q.isBlank()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEt.windowToken, 0)
        val cached = texts
        if (cached != null && textsFor == id) {
            applyHits(PdfOps.search(cached, q))
            return
        }
        searchCount.text = "…"
        val my = docVer
        exec.execute {
            try {
                val t = PdfOps.extract(store.pdf(id)) { i, n -> runOnUiThread { searchCount.text = "${i + 1}/$n" } }
                runOnUiThread {
                    if (my == docVer && id == curId) {
                        texts = t
                        textsFor = id
                        applyHits(PdfOps.search(t, q))
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { searchCount.text = "xəta" }
            }
        }
    }

    private fun applyHits(h: List<Hit>) {
        hits = h
        if (h.isEmpty()) {
            hitIdx = -1
            searchCount.text = "0"
            pv.hits = emptyList()
            pv.curHits = emptyList()
            val noText = texts?.all { it.text.isBlank() } == true
            toast(if (noText) "Bu PDF-də mətn qatı yoxdur (skan edilmiş ola bilər)" else "Tapılmadı")
            return
        }
        hitIdx = h.indexOfFirst { it.page >= page }.let { if (it < 0) 0 else it }
        gotoHit()
    }

    private fun stepHit(d: Int) {
        if (hits.isEmpty()) {
            runSearch()
            return
        }
        hitIdx = (hitIdx + d + hits.size) % hits.size
        gotoHit()
    }

    private fun gotoHit() {
        val h = hits.getOrNull(hitIdx) ?: return
        searchCount.text = "${hitIdx + 1}/${hits.size}"
        if (h.page != page) showPage(h.page) else applyPageHits()
    }

    private fun applyPageHits() {
        if (hits.isEmpty()) {
            pv.hits = emptyList()
            pv.curHits = emptyList()
            return
        }
        val cur = hits.getOrNull(hitIdx)
        pv.hits = hits.filter { it.page == page && it !== cur }.flatMap { it.rects }
        pv.curHits = if (cur != null && cur.page == page) cur.rects else emptyList()
    }

    private fun buildSearchBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xF2101A2E.toInt(), dpf(22f), Ui.GOLD, dp(1))
        bar.elevation = dpf(10f)
        bar.setPadding(dp(12), 0, dp(4), 0)
        bar.addView(tintedIcon(R.drawable.ic_search, Ui.GOLD, 20))
        searchEt = EditText(this)
        searchEt.hint = "Mətndə axtar…"
        searchEt.setTextColor(Ui.TEXT)
        searchEt.setHintTextColor(Ui.DIM)
        searchEt.background = null
        searchEt.textSize = 15f
        searchEt.isSingleLine = true
        searchEt.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        searchEt.setOnEditorActionListener { _, _, _ ->
            hits = emptyList()
            runSearch()
            true
        }
        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                hits = emptyList()
                searchCount.text = ""
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        bar.addView(searchEt, LinearLayout.LayoutParams(0, WC, 1f))
        searchCount = label("", 12f, Ui.DIM, true)
        searchCount.setPadding(dp(4), 0, dp(4), 0)
        bar.addView(searchCount)
        val up = iconButton(R.drawable.ic_chev_l, "Əvvəlki", Ui.TEXT, 36, 8) { stepHit(-1) }
        up.rotation = 90f
        val dn = iconButton(R.drawable.ic_chev_r, "Növbəti", Ui.TEXT, 36, 8) { stepHit(1) }
        dn.rotation = 90f
        bar.addView(up)
        bar.addView(dn)
        bar.addView(iconButton(R.drawable.ic_close, "Bağla", Ui.DIM, 36, 9) { closeSearch() })
        bar.visibility = View.GONE
        return bar
    }

    private fun jumpDialog() {
        if (pages.isEmpty()) return
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText((page + 1).toString())
        et.setSelection(et.text.length)
        et.setTextColor(Ui.TEXT)
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MP, WC))
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Səhifəyə keç (1–${pages.size})")
            .setView(box)
            .setPositiveButton("Keç") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null) showPage((n - 1).coerceIn(0, pages.size - 1))
            }
            .setNegativeButton("Ləğv et", null)
            .show()
    }
}
