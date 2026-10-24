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
    private lateinit var editor: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleName: TextView
    private lateinit var titleSub: TextView
    private lateinit var btnNight: ImageView
    private lateinit var btnUndo: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var btnRedo: ImageView
    private lateinit var pv: DocView
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
        try {
            AppFont.tf = android.graphics.Typeface.createFromAsset(assets, "fonts/DejaVuSans.ttf")
        } catch (e: Exception) {
        }
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
        styleTools()
        refreshHome()
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) else systemBars(true)
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
        if (inEditor) onBack() else super.onBackPressed()
    }

    // Geri: əvvəlcə açıq panelləri bağlayır
    private fun onBack() {
        if (grid?.visibility == View.VISIBLE) {
            closeGrid()
        } else if (annot) {
            exitAnnot()
        } else if (::searchBar.isInitialized && searchBar.visibility == View.VISIBLE) {
            closeSearch()
        } else {
            goHome()
        }
    }

    private fun setBackHandler(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (on && backCb == null) {
                val cb = OnBackInvokedCallback { onBack() }
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
        chrome.setPadding(insL, insT, insR, 0)
        val dl = dockHost.layoutParams as FrameLayout.LayoutParams
        dl.bottomMargin = insB
        dockHost.layoutParams = dl
        val pl = panel.layoutParams as FrameLayout.LayoutParams
        pl.bottomMargin = insB + dp(86)
        panel.layoutParams = pl
        val sl = searchBar.layoutParams as FrameLayout.LayoutParams
        sl.topMargin = insT + dp(8)
        searchBar.layoutParams = sl
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
        val tag = label("Oxu · Yaz · İmzala · Paylaş", 13f, Ui.GOLD)
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
        hero.addView(tintedIcon(R.drawable.ic_open, Color.WHITE, 24))
        val ht = label("PDF aç", 17f, Color.WHITE, true)
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

        val note = label("Fayllar yalnız cihazında saxlanılır. İnternet yalnız OCR dil fayllarını bir dəfə yükləmək üçündür.", 12f, Ui.DIM)
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

    // ======================= redaktor (WPS üslubu) =======================

    private lateinit var chrome: LinearLayout
    private lateinit var titleSmall: TextView
    private lateinit var pageBox: TextView
    private lateinit var btnPen: ImageView
    private lateinit var tabRow: LinearLayout
    private lateinit var ribbon: LinearLayout
    private lateinit var ribbonRow: LinearLayout
    private lateinit var pill: LinearLayout
    private lateinit var sideBadge: TextView
    private val tabViews = ArrayList<TextView>()
    private val pillBtns = HashMap<Int, ImageView>()
    private lateinit var colorBtn: ImageView
    private var curTab = -1
    private var chromeOn = true
    private var annot = false
    private var wide = false
    private val tabNames = arrayOf("Ana Səhifə", "Əlavə et", "Redaktə", "Şərhlər", "Çevir", "Alətlər")

    private fun lineBtn(res: Int, desc: String, box: Int = 40, onClick: () -> Unit): ImageView {
        val b = iconButton(res, desc, Ui.ICON, box, 9, onClick)
        b.background = ripple(null, false, dpf(10f))
        return b
    }

    private fun buildEditor(): FrameLayout {
        val e = FrameLayout(this)
        e.setBackgroundColor(0xFFECECEC.toInt())
        wide = resources.configuration.smallestScreenWidthDp >= 600 || resources.configuration.screenWidthDp >= 720

        pv = DocView(this)
        pv.hint = "Yüklənir…"
        pv.onNeed = { i, w -> renderFor(i, w) }
        pv.onPage = { p -> onPageChanged(p) }
        pv.onTap = { setChrome(!chromeOn) }
        pv.onText = { p, nx, ny, a -> askText(p, nx, ny, a, false) }
        pv.onNote = { p, nx, ny, a -> askText(p, nx, ny, a, true) }
        pv.onCommit = { p -> onAnnCommit(p) }
        pv.onScrollState = { s -> if (s) flashBadge() }
        e.addView(pv, FrameLayout.LayoutParams(MP, MP))

        // ---- üst panel ----
        chrome = LinearLayout(this)
        chrome.orientation = LinearLayout.VERTICAL
        chrome.setBackgroundColor(Ui.BAR)
        chrome.elevation = dpf(2f)
        topBar = chrome
        titleSmall = label("", 11f, Ui.DIM)
        titleSmall.gravity = Gravity.CENTER
        titleSmall.maxLines = 1
        titleSmall.ellipsize = TextUtils.TruncateAt.MIDDLE
        titleSmall.setPadding(dp(60), dp(4), dp(60), 0)
        chrome.addView(titleSmall, LinearLayout.LayoutParams(MP, WC))
        titleName = titleSmall
        titleSub = label("", 1f)

        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), 0, dp(4), 0)
        val left = LinearLayout(this)
        left.gravity = Gravity.CENTER_VERTICAL
        left.addView(lineBtn(R.drawable.ln_sidebar, "Səhifələr") { openGrid() })
        if (wide) left.addView(lineBtn(R.drawable.ln_menu, "Menyu") { showMenu() })
        left.addView(lineBtn(R.drawable.ln_cloud, "Yadda saxla") { save() })
        btnUndo = lineBtn(R.drawable.ln_undo, "Geri al") { doUndo() }
        btnRedo = lineBtn(R.drawable.ln_redo, "Təkrar et") { doRedo() }
        left.addView(btnUndo)
        left.addView(btnRedo)
        row.addView(left)

        tabRow = LinearLayout(this)
        tabRow.gravity = Gravity.CENTER
        tabNames.forEachIndexed { i, n ->
            val t = label(n, 14f, Ui.ICON)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(12), dp(8), dp(12), dp(8))
            t.background = ripple(null, false, dpf(8f))
            t.setOnClickListener { selectTab(if (curTab == i) -1 else i) }
            tabViews.add(t)
            tabRow.addView(t, LinearLayout.LayoutParams(WC, WC))
        }
        if (wide) {
            row.addView(tabRow, LinearLayout.LayoutParams(0, WC, 1f))
        } else {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }

        pageBox = label("1", 11f, Ui.ICON, true)
        pageBox.gravity = Gravity.CENTER
        pageBox.background = rounded(0, dpf(3f), Ui.ICON, dp(1))
        pageBox.setOnClickListener { jumpDialog() }
        val pbl = LinearLayout.LayoutParams(WC, dp(20))
        pbl.setMargins(dp(8), 0, dp(8), 0)
        pageBox.minWidth = dp(22)
        pageBox.setPadding(dp(4), 0, dp(4), 0)
        row.addView(pageBox, pbl)
        btnPen = lineBtn(R.drawable.ln_pen, "Qələm — qeyd rejimi") { toggleAnnot() }
        row.addView(btnPen)
        btnNight = lineBtn(R.drawable.ln_search, "Axtar") { openSearch() }
        row.addView(btnNight)
        if (wide) row.addView(lineBtn(R.drawable.ln_share, "Paylaş") { sharePdf() })
        btnMore = lineBtn(if (wide) R.drawable.ln_close else R.drawable.ln_menu, if (wide) "Bağla" else "Menyu") { if (wide) goHome() else showMenu() }
        row.addView(btnMore)
        chrome.addView(row, LinearLayout.LayoutParams(MP, dp(48)))
        if (!wide) {
            val hs = android.widget.HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            hs.overScrollMode = View.OVER_SCROLL_NEVER
            hs.addView(tabRow, FrameLayout.LayoutParams(WC, dp(40)))
            chrome.addView(hs, LinearLayout.LayoutParams(MP, dp(40)))
        }
        val hair = View(this)
        hair.setBackgroundColor(0x14000000)
        chrome.addView(hair, LinearLayout.LayoutParams(MP, 1))

        // ---- ikinci sətir: seçilmiş tabın lenti ----
        ribbon = LinearLayout(this)
        ribbon.gravity = Gravity.CENTER
        ribbon.background = rounded(Color.WHITE, dpf(14f), Ui.LINE, dp(1))
        ribbon.elevation = dpf(3f)
        val rs = android.widget.HorizontalScrollView(this)
        rs.isHorizontalScrollBarEnabled = false
        rs.isFillViewport = true
        ribbonRow = LinearLayout(this)
        ribbonRow.gravity = Gravity.CENTER
        ribbonRow.setPadding(dp(6), dp(4), dp(6), dp(4))
        rs.addView(ribbonRow, FrameLayout.LayoutParams(WC, WC))
        ribbon.addView(rs, LinearLayout.LayoutParams(MP, WC))
        ribbon.visibility = View.GONE
        val rbl = LinearLayout.LayoutParams(MP, WC)
        rbl.setMargins(dp(8), dp(6), dp(8), 0)
        chrome.addView(ribbon, rbl)
        e.addView(chrome, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        // ---- axtarış ----
        searchBar = buildSearchBar()
        val sbl = FrameLayout.LayoutParams(MP, dp(48), Gravity.TOP)
        sbl.setMargins(dp(10), dp(8), dp(10), 0)
        e.addView(searchBar, sbl)

        // ---- sürüşdürərkən yan nişan ----
        sideBadge = label("", 12f, Color.WHITE, true)
        sideBadge.background = rounded(0xCC2E2E33.toInt(), dpf(12f))
        sideBadge.setPadding(dp(10), dp(4), dp(10), dp(4))
        sideBadge.alpha = 0f
        val bl = FrameLayout.LayoutParams(WC, WC, Gravity.END or Gravity.CENTER_VERTICAL)
        bl.rightMargin = dp(10)
        e.addView(sideBadge, bl)
        pillLabel = sideBadge

        // ---- rəng / qalınlıq popoveri ----
        buildPanel()
        e.addView(panel, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // ---- aşağıda üzən alət həbi ----
        pill = LinearLayout(this)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = rounded(Color.WHITE, dpf(28f), Ui.LINE, dp(1))
        pill.elevation = dpf(10f)
        pill.setPadding(dp(6), dp(4), dp(6), dp(4))
        fun pb(t: Int, res: Int, d: String) {
            val b = lineBtn(res, d, 48) { pillTool(t) }
            pillBtns[t] = b
            pill.addView(b)
        }
        pb(TL_PEN, R.drawable.ln_pen, "Qələm")
        pb(TL_HL, R.drawable.ln_marker, "Marker")
        pb(TL_ERASE, R.drawable.ln_eraser, "Pozan")
        pb(TL_SELECT, R.drawable.ln_lasso, "Seç və köçür")
        colorBtn = lineBtn(R.drawable.ln_palette, "Rəng və qalınlıq", 48) { togglePanel() }
        pill.addView(colorBtn)
        pb(TL_SHAPE, R.drawable.ln_shape, "Fiqur")
        pb(TL_VIEW, R.drawable.ln_hand, "Əl — sürüşdür")
        pill.addView(lineBtn(R.drawable.ln_expand, "Tam ekran", 48) { setChrome(!chromeOn) })
        val phs = android.widget.HorizontalScrollView(this)
        phs.isHorizontalScrollBarEnabled = false
        phs.clipToPadding = false
        phs.setPadding(dp(10), dp(12), dp(10), dp(12))
        phs.clipChildren = false
        phs.addView(pill, FrameLayout.LayoutParams(WC, dp(58)))
        dockHost = phs
        phs.visibility = View.GONE
        e.addView(phs, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        dock = pill
        return e
    }

    private fun buildPanel() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
        panel.elevation = dpf(12f)
        panel.setPadding(dp(14), dp(8), dp(14), dp(10))
        panel.visibility = View.GONE

        shapeRow = LinearLayout(this)
        shapeRow.gravity = Gravity.CENTER
        val shapeIcons = intArrayOf(R.drawable.ic_s_line, R.drawable.ic_s_arrow, R.drawable.ic_s_rect, R.drawable.ic_s_oval)
        val shapeDescs = arrayOf("Xətt", "Ox", "Düzbucaqlı", "Elips")
        shapeIcons.forEachIndexed { i, res ->
            val b = iconButton(res, shapeDescs[i], Ui.ICON, 44, 10) {
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
        swRow.gravity = Gravity.CENTER
        for (c in palette) {
            val v = View(this)
            v.setOnClickListener {
                toolInk[tool] = c
                pv.ink = c
                preview.ink = c
                preview.invalidate()
                refreshSwatches()
                styleTools()
            }
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(dp(32), dp(32))
            lp.setMargins(dp(5), dp(8), dp(5), dp(8))
            swRow.addView(v, lp)
        }
        panel.addView(swRow, LinearLayout.LayoutParams(MP, WC))

        val sizeRow = LinearLayout(this)
        sizeRow.gravity = Gravity.CENTER_VERTICAL
        seek = SeekBar(this)
        seek.max = 100
        seek.progressTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.thumbTintList = ColorStateList.valueOf(Ui.GOLD)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x33000000)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setAmount(p / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        sizeRow.addView(seek, LinearLayout.LayoutParams(dp(220), dp(40)))
        preview = InkPreview(this)
        preview.background = rounded(0xFFF4F4F5.toInt(), dpf(10f))
        val pvlp = LinearLayout.LayoutParams(dp(64), dp(38))
        pvlp.leftMargin = dp(12)
        sizeRow.addView(preview, pvlp)
        panel.addView(sizeRow, LinearLayout.LayoutParams(WC, WC))
    }

    // Lent düyməsi: böyük xətti ikon + kiçik yazı
    private fun ribbonItem(res: Int, name: String, onClick: () -> Unit): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        v.background = ripple(null, false, dpf(10f))
        v.addView(tintedIcon(res, Ui.ICON, 24))
        val t = label(name, 11f, Ui.ICON)
        t.setPadding(0, dp(3), 0, 0)
        t.maxLines = 1
        v.addView(t)
        v.setOnClickListener { onClick() }
        v.layoutParams = LinearLayout.LayoutParams(WC, WC)
        v.minimumWidth = dp(64)
        return v
    }

    private fun selectTab(i: Int) {
        curTab = i
        tabViews.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) Ui.GOLD else Ui.ICON)
            t.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            t.paintFlags = if (on) t.paintFlags or Paint.UNDERLINE_TEXT_FLAG else t.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        ribbonRow.removeAllViews()
        if (i < 0) {
            ribbon.visibility = View.GONE
            return
        }
        val items: List<Triple<Int, String, () -> Unit>> = when (i) {
            0 -> listOf(
                Triple(R.drawable.ln_pages, "Səhifələr") { openGrid() },
                Triple(R.drawable.ln_moon, if (pv.night) "Gündüz" else "Gecə") { toggleNight(); selectTab(0) },
                Triple(R.drawable.ln_goto, "Səhifəyə keç") { jumpDialog() },
                Triple(R.drawable.ln_expand, "Tam ekran") { selectTab(-1); setChrome(false) },
                Triple(R.drawable.ln_save, "Yadda saxla") { save() },
                Triple(R.drawable.ln_saveas, "Fərqli saxla") { saveAs() }
            )
            1 -> listOf(
                Triple(R.drawable.ln_text, "Mətn") { enterAnnot(TL_TEXT) },
                Triple(R.drawable.ln_image, "Şəkil") { pickImage() },
                Triple(R.drawable.ln_sign, "İmza") { signDialog() },
                Triple(R.drawable.ln_note, "Qeyd") { enterAnnot(TL_NOTE) },
                Triple(R.drawable.ln_shape, "Fiqur") { enterAnnot(TL_SHAPE) },
                Triple(R.drawable.ln_add, "Boş səhifə") { insertBlank(page) }
            )
            2 -> listOf(
                Triple(R.drawable.ln_pages, "Səhifələr") { openGrid() },
                Triple(R.drawable.ln_rotate, "Sağa döndər") { rotatePage(page, 90) },
                Triple(R.drawable.ln_rotl, "Sola döndər") { rotatePage(page, -90) },
                Triple(R.drawable.ln_copy, "Dublikat") { duplicatePage(page) },
                Triple(R.drawable.ln_trash, "Səhifəni sil") { if (pages.size > 1) confirmDeletePage(page) },
                Triple(R.drawable.ln_extract, "Çıxar") { extractDialog() }
            )
            3 -> listOf(
                Triple(R.drawable.ln_text, "Mətn") { enterAnnot(TL_TEXT) },
                Triple(R.drawable.ln_marker, "Marker") { enterAnnot(TL_HL) },
                Triple(R.drawable.ln_image, "Şəkil") { pickImage() },
                Triple(R.drawable.ln_add, "Boş səhifə") { insertBlank(page) },
                Triple(R.drawable.ln_sign, "İmza") { signDialog() },
                Triple(R.drawable.ln_note, "Qeyd") { enterAnnot(TL_NOTE) },
                Triple(R.drawable.ln_lasso, "Seç") { enterAnnot(TL_SELECT) },
                Triple(R.drawable.ln_trash, "Seçiləni sil") { if (!pv.deleteSelected()) toast("Əvvəlcə «Seç» ilə qeydə toxun") }
            )
            4 -> listOf(
                Triple(R.drawable.ln_png, "Səhifə → PNG") { exportPng(page) },
                Triple(R.drawable.ln_image, "Hamısı → JPEG") { shareImages() },
                Triple(R.drawable.ln_merge, "Birləşdir") { pickMerge() },
                Triple(R.drawable.ln_split, "Böl") { splitDialog() },
                Triple(R.drawable.ln_extract, "Səhifə çıxar") { extractDialog() }
            )
            else -> listOf(
                Triple(R.drawable.ln_search, "Axtar") { openSearch() },
                Triple(R.drawable.ln_ocr, "OCR (skan)") { ocrAll() },
                Triple(R.drawable.ln_lock, "Şifrə") { passwordMenu() },
                Triple(R.drawable.ln_print, "Çap et") { printPdf() },
                Triple(R.drawable.ln_share, "Paylaş") { sharePdf() }
            )
        }
        for ((res, n, f) in items) ribbonRow.addView(ribbonItem(res, n, f))
        ribbon.visibility = View.VISIBLE
    }

    private fun toggleAnnot() {
        if (annot) exitAnnot() else enterAnnot(TL_PEN)
    }

    private fun enterAnnot(t: Int) {
        annot = true
        if (curTab != 3) selectTab(3)
        btnPen.background = rounded(0x14000000, dpf(10f))
        dockHost.visibility = View.VISIBLE
        setTool(t)
        if (!chromeOn) setChrome(true)
    }

    private fun exitAnnot() {
        annot = false
        btnPen.background = ripple(null, false, dpf(10f))
        dockHost.visibility = View.GONE
        panel.visibility = View.GONE
        selectTab(-1)
        setTool(TL_VIEW)
    }

    private fun pillTool(t: Int) {
        if (t == tool && t != TL_VIEW && t != TL_ERASE && t != TL_SELECT) {
            togglePanel()
            return
        }
        setTool(t)
    }

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }
        if (!((tool >= TL_PEN && tool <= TL_SHAPE) || tool == TL_NOTE)) setTool(TL_PEN)
        showPanel()
    }

    private fun showPanel() {
        seek.progress = (toolAmt[tool] * 100f).toInt()
        preview.tool = if (tool == TL_NOTE) TL_TEXT else tool
        preview.ink = toolInk[tool]
        preview.amount = toolAmt[tool]
        preview.invalidate()
        shapeRow.visibility = if (tool == TL_SHAPE) View.VISIBLE else View.GONE
        refreshSwatches()
        refreshShapeBtns()
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(140).start()
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
        for ((t, b) in pillBtns) {
            val sel = t == tool
            b.background = if (sel) rounded(0x17000000, dpf(14f)) else ripple(null, false, dpf(14f))
            b.imageTintList = ColorStateList.valueOf(if (sel && (t == TL_PEN || t == TL_HL)) toolInk[t] else Ui.ICON)
        }
        if (::colorBtn.isInitialized) {
            val c = if ((tool >= TL_PEN && tool <= TL_SHAPE) || tool == TL_NOTE) toolInk[tool] else Ui.ICON
            colorBtn.imageTintList = ColorStateList.valueOf(c)
        }
    }

    private fun updatePanel() {
        val show = (tool >= TL_PEN && tool <= TL_SHAPE) || tool == TL_NOTE
        if (!show) panel.visibility = View.GONE else if (panel.visibility == View.VISIBLE) showPanel()
    }

    // Tam ekran: sistem panelləri və alətlər gizlənir, toxunanda görünür
    private fun setChrome(on: Boolean) {
        chromeOn = on
        chrome.animate().cancel()
        if (on) {
            chrome.visibility = View.VISIBLE
            chrome.animate().translationY(0f).alpha(1f).setDuration(180).start()
        } else {
            chrome.animate().translationY(-chrome.height.toFloat()).alpha(0f).setDuration(180)
                .withEndAction { if (!chromeOn) chrome.visibility = View.INVISIBLE }.start()
            if (!annot) panel.visibility = View.GONE
        }
        systemBars(on)
    }

    private fun systemBars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (show) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    private val hideBadge = Runnable { sideBadge.animate().alpha(0f).setDuration(250).start() }

    private fun flashBadge() {
        if (pages.isEmpty()) return
        sideBadge.text = "${page + 1} / ${pages.size}"
        sideBadge.animate().cancel()
        sideBadge.alpha = 1f
        ui.removeCallbacks(hideBadge)
        ui.postDelayed(hideBadge, 900)
    }

    private fun onPageChanged(p: Int) {
        page = p
        pageBox.text = (p + 1).toString()
        curId?.let { store.setLastPage(it, p) }
        flashBadge()
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

    // Ümumi tarixçə: annotasiya (səhifə nömrəsi) və ya səhifə əməliyyatı (snapshot)
    private class HPage(val pdf: File, val json: String, val page: Int)

    private val hist = ArrayList<Any>()
    private val redoHist = ArrayList<Any>()

    private fun clearHistory() {
        for (h in hist + redoHist) if (h is HPage) h.pdf.delete()
        hist.clear()
        redoHist.clear()
    }

    private fun onAnnCommit(p: Int) {
        hist.add(p)
        for (h in redoHist) if (h is HPage) h.pdf.delete()
        redoHist.clear()
        refreshHistory()
    }

    private fun doUndo() {
        val h = hist.removeLastOrNull() ?: return
        if (h is Int) {
            if (pages.getOrNull(h)?.doUndo() == true) redoHist.add(h)
            pv.invalidate()
            refreshHistory()
        } else if (h is HPage) {
            restoreSnapshot(h, redoHist)
        }
    }

    private fun doRedo() {
        val h = redoHist.removeLastOrNull() ?: return
        if (h is Int) {
            if (pages.getOrNull(h)?.doRedo() == true) hist.add(h)
            pv.invalidate()
            refreshHistory()
        } else if (h is HPage) {
            restoreSnapshot(h, hist)
        }
    }

    private fun refreshHistoryButtons() {
        btnUndo.alpha = if (hist.isNotEmpty()) 1f else 0.3f
        btnRedo.alpha = if (redoHist.isNotEmpty()) 1f else 0.3f
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
            REQ_MERGE -> mergePdf(u)
            REQ_IMAGE -> insertImage(u)
            REQ_EXTRACT -> writePending(u)
            REQ_SPLIT -> splitInto(u)
            REQ_PNG -> writePending(u)
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
            val ratios = pageRatios(r)
            val list = ArrayList<PageData>()
            repeat(n) { list.add(PageData()) }
            try {
                val af = store.ann(id)
                if (af.exists()) jsonToAnns(af.readText(), list)
            } catch (e: Exception) {
            }
            runOnUiThread {
                dlg.dismiss()
                installDoc(id, name, fd, r, list, ratios)
            }
        } catch (e: Throwable) {
            if (e is SecurityException) {
                runOnUiThread {
                    dlg.dismiss()
                    askPassword(id, name, false)
                }
                return
            }
            val fresh = store.recents().none { it.id == id }
            if (fresh) store.pdf(id).delete()
            runOnUiThread {
                dlg.dismiss()
                toast("PDF açıla bilmədi")
            }
        }
    }

    private fun installDoc(id: String, name: String, fd: ParcelFileDescriptor, r: PdfRenderer, list: ArrayList<PageData>, ratios: FloatArray) {
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
        clearHistory()
        if (annot) exitAnnot()
        setTool(TL_VIEW)
        showEditor()
        val start = store.lastPage(id).coerceIn(0, list.size - 1)
        page = start
        pageBox.text = (start + 1).toString()
        pv.setDoc(ratios, pages, start)
        refreshHistoryButtons()
        setChrome(false)
    }

    // Hər səhifənin hündürlük/en nisbəti (exec thread)
    private fun pageRatios(r: PdfRenderer): FloatArray {
        val out = FloatArray(r.pageCount)
        for (i in 0 until r.pageCount) {
            val pg = r.openPage(i)
            out[i] = pg.height.toFloat() / max(1, pg.width)
            pg.close()
        }
        return out
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
        closeGrid()
        if (annot) exitAnnot()
        selectTab(-1)
        setChrome(true)
        clearHistory()
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
        if (i < 0 || i >= pages.size) return
        pv.scrollToPage(i)
    }

    // DocView-un istədiyi səhifəni lazım olan endə çəkir
    private fun renderFor(i: Int, w: Int) {
        val r = renderer ?: return
        val ver = docVer
        val id = curId
        exec.execute {
            if (ver != docVer || r !== renderer) return@execute
            var ok = false
            runOnUiThreadSync { ok = pv.wants(i) }
            if (!ok) {
                runOnUiThread { pv.failed(i) }
                return@execute
            }
            try {
                val res = renderPage(r, i, w)
                if (i == 0 && id != null) makeThumb(id, res.first)
                runOnUiThread { if (ver == docVer && r === renderer) pv.setBitmap(i, res.first) else pv.failed(i) }
            } catch (e: Throwable) {
                runOnUiThread { pv.failed(i) }
            }
        }
    }

    private fun runOnUiThreadSync(f: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            try {
                f()
            } finally {
                latch.countDown()
            }
        }
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
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

    // ======================= saxlamaq / paylaşmaq =======================

    private fun snapshot(): List<List<Ann>> = pages.map { ArrayList(it.anns) }

    // Qeydləri vektor kimi orijinal PDF-ə yazır (mətn seçilə bilən qalır); alınmasa şəkil kimi yazır. exec thread-də
    private fun exportTo(os: java.io.OutputStream, snap: List<List<Ann>>, only: Set<Int>? = null, tv: TextView? = null) {
        val id = curId ?: throw IOException("no doc")
        try {
            val buf = java.io.ByteArrayOutputStream()
            PdfOps.export(store.pdf(id), snap, buf, only, loadOcr(id))
            val pw = store.password(id)
            if (pw != null) PdfOps.encrypt(buf.toByteArray(), pw, os) else buf.writeTo(os)
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

    private fun askText(pg: Int, nx: Float, ny: Float, a: Ann?, note: Boolean) {
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
            .setPositiveButton("OK") { _, _ -> commitText(pg, a, et.text.toString().trim(), nx, ny, note) }
            .setNegativeButton("Ləğv et", null)
        if (a != null) b.setNeutralButton("Sil") { _, _ -> pv.removeAnn(pg, a) }
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun commitText(pg: Int, a: Ann?, s: String, nx: Float, ny: Float, note: Boolean) {
        if (a == null) {
            if (s.isNotEmpty()) pv.addText(pg, s, nx, ny, note)
        } else if (s.isEmpty()) {
            pv.removeAnn(pg, a)
        } else {
            pv.editAnnText(pg, a, s)
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
        pv.addImage(page, name, bm.height.toFloat() / bm.width, wFrac)
        enterAnnot(TL_SELECT)
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

    // ======================= OCR =======================

    private fun ocrFile(id: String) = File(store.dir, id + "_ocr.json")

    private fun loadOcr(id: String): List<List<OcrWord>?>? = try {
        val f = ocrFile(id)
        if (f.exists()) Ocr.fromJson(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun ocrAll() {
        val id = curId ?: return
        val dl = if (Ocr.ready(this)) "" else "\n\nİlk dəfə dil faylları yüklənəcək (~10 MB, internet lazımdır). Sonra tam oflayn işləyir."
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Mətni tanı (OCR)")
            .setMessage("Skan edilmiş səhifələrdəki yazılar cihazda tanınacaq (Azərbaycan, ərəb, ingilis). Bundan sonra axtarış işləyəcək, saxlananda PDF-ə görünməz mətn qatı əlavə olunacaq.$dl")
            .setPositiveButton("Başla") { _, _ -> runOcr(id) }
            .setNegativeButton("Ləğv et", null)
            .show()
    }

    private fun runOcr(id: String) {
        val r = renderer ?: return
        val ver = docVer
        val (dlg, tv) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                if (!Ocr.ready(this)) Ocr.download(this) { l -> runOnUiThread { tv.text = "Dil faylı yüklənir: $l" } }
                // mətn qatı olan səhifələri ötür
                val texts = try { PdfOps.extract(store.pdf(id)) { _, _ -> } } catch (e: Throwable) { null }
                val n = r.pageCount
                val prev = loadOcr(id)
                val res = ArrayList<List<OcrWord>?>()
                val t = Ocr.open(this)
                try {
                    for (i in 0 until n) {
                        if (ver != docVer) throw InterruptedException()
                        val has = texts?.getOrNull(i)?.text?.isNotBlank() == true
                        val old = prev?.getOrNull(i)
                        if (has) { res.add(null); continue }
                        if (old != null) { res.add(old); continue }
                        runOnUiThread { tv.text = "Tanınır… ${i + 1}/$n" }
                        val (bm, _) = renderPage(r, i, 2000)
                        res.add(Ocr.recognize(t, bm))
                        bm.recycle()
                    }
                } finally {
                    t.recycle()
                }
                ocrFile(id).writeText(Ocr.toJson(res))
                val cnt = res.count { it != null }
                runOnUiThread {
                    dlg.dismiss()
                    texts_reset()
                    toast(if (cnt == 0) "Bütün səhifələrdə artıq mətn var — OCR lazım deyil" else "$cnt səhifə tanındı ✓ İndi axtara bilərsən")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast(if (e is java.io.IOException) "Dil faylları yüklənmədi — interneti yoxla" else "OCR alınmadı")
                }
            }
        }
    }

    private fun texts_reset() {
        texts = null
        textsFor = null
        hits = emptyList()
    }

    // ======================= şifrə =======================

    private fun passwordField(): Pair<FrameLayout, EditText> {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.hint = "Şifrə"
        et.setTextColor(Ui.TEXT)
        et.setHintTextColor(Ui.DIM)
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MP, WC))
        return Pair(box, et)
    }

    private fun askPassword(id: String, name: String, wrong: Boolean) {
        val (box, et) = passwordField()
        val d = AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle(if (wrong) "Şifrə səhvdir, yenidən yaz" else "Bu PDF şifrəlidir")
            .setMessage("«$name» faylını açmaq üçün şifrəni yaz.")
            .setView(box)
            .setPositiveButton("Aç") { _, _ -> unlock(id, name, et.text.toString()) }
            .setNegativeButton("Ləğv et") { _, _ ->
                if (store.recents().none { it.id == id }) store.pdf(id).delete()
            }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun unlock(id: String, name: String, pw: String) {
        val (dlg, _) = progressDialog("Açılır…")
        exec.execute {
            val src = store.pdf(id)
            val tmp = File(store.dir, "tmp_dec_" + System.nanoTime() + ".pdf")
            try {
                PdfOps.decrypt(src, pw, tmp)
                src.delete()
                if (!tmp.renameTo(src)) {
                    tmp.copyTo(src, true)
                    tmp.delete()
                }
                store.setPassword(id, pw)
                loadStored(id, name, dlg)
                runOnUiThread { toast("Saxlayanda şifrə qorunacaq (Alətlər → Şifrə ilə dəyişə bilərsən)") }
            } catch (e: Throwable) {
                tmp.delete()
                runOnUiThread {
                    dlg.dismiss()
                    if (e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) askPassword(id, name, true)
                    else toast("Bu PDF açıla bilmədi")
                }
            }
        }
    }

    private fun passwordMenu() {
        val id = curId ?: return
        val has = store.password(id) != null
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add((if (has) "Şifrəni dəyiş" else "Şifrə qoy") to {
            val (box, et) = passwordField()
            AlertDialog.Builder(this, R.style.NbDialog)
                .setTitle("Yeni şifrə")
                .setMessage("Saxlanan və paylaşılan PDF bu şifrə ilə qorunacaq (AES-256).")
                .setView(box)
                .setPositiveButton("OK") { _, _ ->
                    val p = et.text.toString()
                    if (p.length < 4) toast("Ən azı 4 simvol") else {
                        store.setPassword(id, p)
                        toast("Şifrə təyin olundu — saxlayanda tətbiq olunacaq")
                    }
                }
                .setNegativeButton("Ləğv et", null)
                .show()
        })
        if (has) acts.add("Şifrəni sil (saxlananda şifrəsiz olsun)" to {
            store.setPassword(id, null)
            toast("Şifrə silindi — növbəti saxlamada PDF şifrəsiz olacaq")
        })
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle(if (has) "Sənəd şifrəlidir" else "Sənəd şifrəsizdir")
            .setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
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
        items.add(2, "Səhifələr (düzəlt, döndər, sil)" to { openGrid() })
        items.add("PDF birləşdir…" to { pickMerge() })
        items.add("Səhifələri ayrıca PDF kimi çıxar…" to { extractDialog() })
        items.add("PDF-i böl…" to { splitDialog() })
        items.add("Səhifəni şəkil kimi saxla" to { exportPng(page) })
        items.add("Bütün səhifələri şəkil kimi paylaş" to { shareImages() })
    }

    // ======================= səhifələrin idarəsi =======================

    private var grid: FrameLayout? = null
    private var gridView: android.widget.GridView? = null
    private var thumbs = arrayOfNulls<Bitmap>(0)
    private var pendingFile: File? = null
    private var pendingMime = "application/pdf"
    private var splitN = 0

    private fun openGrid() {
        if (renderer == null) return
        val g = grid ?: buildGrid().also { grid = it }
        if (thumbs.size != pages.size) thumbs = arrayOfNulls(pages.size)
        (gridView?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        g.setPadding(insL, insT, insR, insB)
        g.visibility = View.VISIBLE
        g.alpha = 0f
        g.animate().alpha(1f).setDuration(160).start()
        gridView?.setSelection(page)
    }

    private fun closeGrid() {
        grid?.visibility = View.GONE
    }

    private fun buildGrid(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Ui.BG)
        f.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, dp(6), 0)
        head.addView(iconButton(R.drawable.ic_back, "Geri") { closeGrid() })
        val t = label("Səhifələr", 17f, Ui.TEXT, true)
        t.setPadding(dp(6), 0, 0, 0)
        head.addView(t, LinearLayout.LayoutParams(0, WC, 1f))
        head.addView(iconButton(R.drawable.ic_add, "Boş səhifə əlavə et", Ui.TEXT, 44, 10) { insertBlank(pages.size - 1) })
        head.addView(iconButton(R.drawable.ic_doc, "PDF birləşdir", Ui.GOLD, 44, 10) { pickMerge() })
        col.addView(head, LinearLayout.LayoutParams(MP, dp(56)))
        val hint = label("Toxun — səhifəyə keç · Uzun bas — döndər, köçür, sil, çıxar", 12f, Ui.DIM)
        hint.setPadding(dp(16), 0, dp(16), dp(8))
        col.addView(hint)
        val gv = android.widget.GridView(this)
        gv.numColumns = android.widget.GridView.AUTO_FIT
        gv.columnWidth = dp(110)
        gv.stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
        gv.verticalSpacing = dp(12)
        gv.horizontalSpacing = dp(12)
        gv.setPadding(dp(14), dp(4), dp(14), dp(24))
        gv.clipToPadding = false
        gv.selector = android.graphics.drawable.ColorDrawable(0)
        gv.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = pages.size
            override fun getItem(p: Int): Any = p
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val cell = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val iv = ImageView(this@MainActivity)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.adjustViewBounds = false
                    addView(iv, LinearLayout.LayoutParams(MP, dp(140)))
                    val tv = label("", 12f, Ui.DIM, true)
                    tv.gravity = Gravity.CENTER
                    tv.setPadding(0, dp(6), 0, 0)
                    addView(tv, LinearLayout.LayoutParams(MP, WC))
                }
                val iv = cell.getChildAt(0) as ImageView
                val tv = cell.getChildAt(1) as TextView
                val cur = p == page
                iv.background = rounded(Ui.SURFACE, dpf(8f), if (cur) Ui.GOLD else Ui.LINE, dp(if (cur) 2 else 1))
                iv.setPadding(dp(4), dp(4), dp(4), dp(4))
                tv.text = (p + 1).toString()
                tv.setTextColor(if (cur) Ui.GOLD else Ui.DIM)
                val b = thumbs.getOrNull(p)
                iv.setImageBitmap(b)
                if (b == null) requestThumb(p)
                return cell
            }
        }
        gv.setOnItemClickListener { _, _, p, _ ->
            closeGrid()
            showPage(p)
        }
        gv.setOnItemLongClickListener { _, v, p, _ ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pageActions(p)
            true
        }
        gridView = gv
        col.addView(gv, LinearLayout.LayoutParams(MP, 0, 1f))
        f.addView(col, FrameLayout.LayoutParams(MP, MP))
        f.visibility = View.GONE
        (editor.parent as FrameLayout).addView(f, FrameLayout.LayoutParams(MP, MP))
        return f
    }

    private val thumbBusy = HashSet<Int>()

    private fun requestThumb(p: Int) {
        val r = renderer ?: return
        if (!thumbBusy.add(p)) return
        val ver = docVer
        val anns = ArrayList(pages[p].anns)
        exec.execute {
            try {
                val (bm, _) = renderPage(r, p, dp(110) * 2 / 2 + 120)
                val c = android.graphics.Canvas(bm)
                val pt = Paint()
                for (a in anns) drawAnn(c, a, 0f, 0f, bm.width.toFloat(), bm.height.toFloat(), pt)
                runOnUiThread {
                    thumbBusy.remove(p)
                    if (ver == docVer && p < thumbs.size) {
                        thumbs[p] = bm
                        (gridView?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { thumbBusy.remove(p) }
            }
        }
    }

    private fun pageActions(p: Int) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Sağa döndər ↻" to { rotatePage(p, 90) })
        acts.add("Sola döndər ↺" to { rotatePage(p, -90) })
        if (p > 0) acts.add("Əvvələ köçür" to { movePage(p, p - 1) })
        if (p < pages.size - 1) acts.add("Sonraya köçür" to { movePage(p, p + 1) })
        if (p > 0) acts.add("Ən əvvələ köçür" to { movePage(p, 0) })
        acts.add("Dublikat et" to { duplicatePage(p) })
        acts.add("Sonrasına boş səhifə əlavə et" to { insertBlank(p) })
        acts.add("Bu səhifəni ayrıca PDF kimi çıxar" to { extractPages(setOf(p), "_s" + (p + 1)) })
        acts.add("Şəkil kimi saxla" to { exportPng(p) })
        if (pages.size > 1) acts.add("Sil" to { confirmDeletePage(p) })
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Səhifə ${p + 1}")
            .setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
            .show()
    }

    private fun confirmDeletePage(p: Int) {
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle("Səhifə ${p + 1} silinsin?")
            .setMessage("Səhifə və onun üzərindəki qeydlər silinəcək.")
            .setPositiveButton("Sil") { _, _ ->
                val specs = ArrayList<PageSpec>()
                val np = ArrayList<PageData>()
                for (i in pages.indices) if (i != p) {
                    specs.add(PageSpec(0, i))
                    np.add(pages[i])
                }
                applyPages(specs, np, emptyList(), min(page, specs.size - 1))
            }
            .setNegativeButton("Ləğv et", null)
            .show()
    }

    private fun freshCopy(pd: PageData): PageData {
        val n = PageData()
        for (a in pd.anns) {
            val c = Ann(a.type, a.ink, a.w, a.text)
            c.p.addAll(a.p)
            n.anns.add(c)
        }
        return n
    }

    private fun rotatePage(p: Int, deg: Int) {
        val specs = pages.indices.map { PageSpec(0, it, if (it == p) deg else 0) }
        val np = ArrayList(pages)
        val pd = freshCopy(pages[p])
        for (a in pd.anns) rotateAnn(a, deg > 0)
        np[p] = pd
        applyPages(specs, np, emptyList(), p)
    }

    private fun movePage(from: Int, to: Int) {
        val order = pages.indices.toMutableList()
        val x = order.removeAt(from)
        order.add(to, x)
        applyPages(order.map { PageSpec(0, it) }, order.map { pages[it] }, emptyList(), to)
    }

    private fun duplicatePage(p: Int) {
        val specs = ArrayList<PageSpec>()
        val np = ArrayList<PageData>()
        for (i in pages.indices) {
            specs.add(PageSpec(0, i))
            np.add(pages[i])
            if (i == p) {
                specs.add(PageSpec(0, i))
                np.add(freshCopy(pages[i]))
            }
        }
        applyPages(specs, np, emptyList(), p + 1)
    }

    private fun insertBlank(after: Int) {
        val r = renderer ?: return
        exec.execute {
            var w = 595f
            var h = 842f
            try {
                val pg = r.openPage(after.coerceIn(0, r.pageCount - 1))
                w = pg.width.toFloat()
                h = pg.height.toFloat()
                pg.close()
            } catch (e: Throwable) {
            }
            runOnUiThread {
                val specs = ArrayList<PageSpec>()
                val np = ArrayList<PageData>()
                for (i in pages.indices) {
                    specs.add(PageSpec(0, i))
                    np.add(pages[i])
                    if (i == after) {
                        specs.add(PageSpec(0, -1, 0, w, h))
                        np.add(PageData())
                    }
                }
                applyPages(specs, np, emptyList(), after + 1)
            }
        }
    }

    // Yeni səhifə sırasını PdfBox ilə yazır, sənədi yenidən açır (geri alına bilər)
    private fun applyPages(specs: List<PageSpec>, np: List<PageData>, extra: List<File>, goTo: Int) {
        val id = curId ?: return
        replaceWorking({ tmp -> PdfOps.rebuild(listOf(store.pdf(id)) + extra, specs, tmp); for (f in extra) f.delete() }, np, null, goTo, hist, true)
    }

    private fun restoreSnapshot(h: HPage, pushTo: ArrayList<Any>) {
        replaceWorking({ tmp -> h.pdf.copyTo(tmp, true) }, null, h.json, h.page, pushTo, false) { h.pdf.delete() }
    }

    // İşçi nüsxəni əvəz edir: əvvəlcə cari vəziyyətin snapshot-ı pushTo-ya yazılır
    private fun replaceWorking(
        produce: (File) -> Unit, np: List<PageData>?, json: String?, goTo: Int,
        pushTo: ArrayList<Any>, clearRedo: Boolean, done: (() -> Unit)? = null
    ) {
        val id = curId ?: return
        saveAnnotationsNow()
        val curJson = annsToJson(pages)
        val curPage = page
        val (dlg, _) = progressDialog("Səhifələr yenilənir…")
        exec.execute {
            val tmp = File(store.dir, "tmp_ops_" + System.nanoTime() + ".pdf")
            val snap = File(store.dir, id + "_hist_" + System.nanoTime() + ".pdf")
            try {
                store.pdf(id).copyTo(snap, true)
                produce(tmp)
                try {
                    renderer?.close()
                    pfd?.close()
                } catch (e: Exception) {
                }
                val dest = store.pdf(id)
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, true)
                    tmp.delete()
                }
                val fd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                val ratios = pageRatios(r)
                val list: List<PageData> = np ?: ArrayList<PageData>().also { l ->
                    repeat(r.pageCount) { l.add(PageData()) }
                    if (json != null) try { jsonToAnns(json, l) } catch (e: Exception) {}
                }
                runOnUiThread {
                    dlg.dismiss()
                    renderer = r
                    pfd = fd
                    pages.clear()
                    for (pd in list) pages.add(pd)
                    for (pd in pages) { pd.undo.clear(); pd.redo.clear() }
                    // annotasiya addımları səhifə nömrələrinə bağlıdır — səhifə əməliyyatından sonra yalnız snapshot-lar qalır
                    hist.removeAll { it is Int }
                    redoHist.removeAll { it is Int }
                    if (clearRedo) {
                        for (h in redoHist) if (h is HPage) h.pdf.delete()
                        redoHist.clear()
                    }
                    pushTo.add(HPage(snap, curJson, curPage))
                    done?.invoke()
                    resetDocState()
                    thumbs = arrayOfNulls(pages.size)
                    thumbBusy.clear()
                    store.thumb(id).delete()
                    ocrFile(id).delete()
                    store.touch(id, docName, pages.size)
                    saveAnnotationsNow()
                    (gridView?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                    val g = goTo.coerceIn(0, pages.size - 1)
                    page = g
                    pv.setDoc(ratios, pages, g)
                    refreshHistoryButtons()
                }
            } catch (e: Throwable) {
                tmp.delete()
                snap.delete()
                runOnUiThread {
                    dlg.dismiss()
                    toast("Əməliyyat alınmadı")
                    if (curId != null) {
                        val (d2, _) = progressDialog("Açılır…")
                        exec.execute { loadStored(id, docName, d2) }
                    }
                }
            }
        }
    }

    private fun pickMerge() {
        if (renderer == null) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        startActivityForResult(i, REQ_MERGE)
    }

    private fun mergePdf(u: Uri) {
        val (dlg, _) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                val f = File(cacheDir, "merge_" + System.nanoTime() + ".pdf")
                contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: throw IOException()
                val n = PdfOps.pageCount(f)
                runOnUiThread {
                    dlg.dismiss()
                    val first = pages.size
                    val specs = ArrayList<PageSpec>()
                    val np = ArrayList<PageData>()
                    for (i in pages.indices) {
                        specs.add(PageSpec(0, i))
                        np.add(pages[i])
                    }
                    for (i in 0 until n) {
                        specs.add(PageSpec(1, i))
                        np.add(PageData())
                    }
                    applyPages(specs, np, listOf(f), first)
                    toast("$n səhifə sona əlavə olunur")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Bu PDF birləşdirilə bilmədi")
                }
            }
        }
    }

    // "1-3, 5, 8-" kimi aralığı oxuyur (0-dan başlayan indekslər)
    private fun parseRange(s: String, n: Int): Set<Int> {
        val out = HashSet<Int>()
        for (part in s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d*)\\s*[-–]\\s*(\\d*)$").find(part)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: n
                for (k in min(a, b)..max(a, b)) if (k in 1..n) out.add(k - 1)
            } else {
                val k = part.toIntOrNull()
                if (k != null && k in 1..n) out.add(k - 1)
            }
        }
        return out
    }

    private fun inputDialog(title: String, hint: String, initial: String, numeric: Boolean, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.hint = hint
        et.setTextColor(Ui.TEXT)
        et.setHintTextColor(Ui.DIM)
        if (numeric) et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setSelection(et.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(et, FrameLayout.LayoutParams(MP, WC))
        AlertDialog.Builder(this, R.style.NbDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Ləğv et", null)
            .show()
    }

    private fun extractDialog() {
        if (renderer == null) return
        inputDialog("Hansı səhifələr? (1–${pages.size})", "məs: 1-3, 5, 8", "${page + 1}", false) { s ->
            val set = parseRange(s, pages.size)
            if (set.isEmpty()) toast("Səhifə aralığı düzgün deyil") else extractPages(set, "_secilmis")
        }
    }

    private fun extractPages(set: Set<Int>, suffix: String) {
        val snap = snapshot()
        val (dlg, _) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                val f = File(cacheDir, "pending.pdf")
                f.outputStream().use { exportTo(it, snap, set) }
                runOnUiThread {
                    dlg.dismiss()
                    pendingFile = f
                    pendingMime = "application/pdf"
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "application/pdf"
                    i.putExtra(Intent.EXTRA_TITLE, baseName() + suffix + ".pdf")
                    startActivityForResult(i, REQ_EXTRACT)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Çıxarmaq alınmadı")
                }
            }
        }
    }

    private fun writePending(u: Uri) {
        val f = pendingFile ?: return
        exec.execute {
            try {
                contentResolver.openOutputStream(u, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: throw IOException()
                f.delete()
                runOnUiThread { showSaved(u, true) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Saxlama xətası") }
            }
        }
    }

    private fun splitDialog() {
        if (renderer == null) return
        if (pages.size < 2) {
            toast("Bölmək üçün ən azı 2 səhifə lazımdır")
            return
        }
        inputDialog("Hər faylda neçə səhifə olsun?", "məs: 1", "1", true) { s ->
            val n = s.toIntOrNull()
            if (n == null || n < 1 || n >= pages.size) {
                toast("1 ilə ${pages.size - 1} arasında rəqəm yaz")
            } else {
                splitN = n
                toast("Faylların saxlanacağı qovluğu seç")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SPLIT)
            }
        }
    }

    private fun splitInto(tree: Uri) {
        val n = splitN
        if (n < 1) return
        val snap = snapshot()
        val total = pages.size
        val (dlg, tv) = progressDialog("Bölünür…")
        exec.execute {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                var part = 0
                var start = 0
                while (start < total) {
                    part++
                    val end = min(total, start + n)
                    val set = (start until end).toSet()
                    runOnUiThread { tv.text = "Bölünür… $part" }
                    val name = baseName() + "_" + part + (if (n == 1) "" else "_s${start + 1}-$end") + ".pdf"
                    val du = android.provider.DocumentsContract.createDocument(contentResolver, parent, "application/pdf", name) ?: throw IOException()
                    contentResolver.openOutputStream(du, "wt")?.use { exportTo(it, snap, set) } ?: throw IOException()
                    start = end
                }
                val parts = part
                runOnUiThread {
                    dlg.dismiss()
                    toast("$parts fayl yaradıldı ✓")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Bölmək alınmadı")
                }
            }
        }
    }

    // Səhifəni qeydləri ilə birlikdə yüksək keyfiyyətli şəklə çevirir (exec thread)
    private fun pageImage(r: PdfRenderer, p: Int, anns: List<Ann>): Bitmap {
        val (bm, _) = renderPage(r, p, 2000)
        val c = android.graphics.Canvas(bm)
        val pt = Paint()
        for (a in anns) drawAnn(c, a, 0f, 0f, bm.width.toFloat(), bm.height.toFloat(), pt)
        return bm
    }

    private fun exportPng(p: Int) {
        val r = renderer ?: return
        val anns = ArrayList(pages[p].anns)
        val (dlg, _) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                val bm = pageImage(r, p, anns)
                val f = File(cacheDir, "pending.png")
                f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bm.recycle()
                runOnUiThread {
                    dlg.dismiss()
                    pendingFile = f
                    pendingMime = "image/png"
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "image/png"
                    i.putExtra(Intent.EXTRA_TITLE, baseName() + "_s" + (p + 1) + ".png")
                    startActivityForResult(i, REQ_PNG)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Şəkil yaradıla bilmədi")
                }
            }
        }
    }

    private fun shareImages() {
        val r = renderer ?: return
        val snap = snapshot()
        val (dlg, tv) = progressDialog("Hazırlanır…")
        exec.execute {
            try {
                val dir = ShareProvider.dir(this)
                val uris = ArrayList<Uri>()
                val safe = baseName().replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                for (p in snap.indices) {
                    runOnUiThread { tv.text = "Hazırlanır… ${p + 1}/${snap.size}" }
                    val bm = pageImage(r, p, snap[p])
                    val f = File(dir, safe + "_s" + (p + 1) + ".jpg")
                    f.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bm.recycle()
                    uris.add(ShareProvider.uriFor(f))
                }
                runOnUiThread {
                    dlg.dismiss()
                    val i = Intent(Intent.ACTION_SEND_MULTIPLE)
                    i.type = "image/jpeg"
                    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    val cd = android.content.ClipData.newRawUri("", uris[0])
                    for (k in 1 until uris.size) cd.addItem(android.content.ClipData.Item(uris[k]))
                    i.clipData = cd
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Şəkilləri paylaş"))
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    toast("Paylaşmaq mümkün olmadı")
                }
            }
        }
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
        pv.setHits(emptyMap(), -1, emptyList())
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
                val t0 = PdfOps.extract(store.pdf(id)) { i, n -> runOnUiThread { searchCount.text = "${i + 1}/$n" } }
                val oc = loadOcr(id)
                val t = t0.mapIndexed { i, p -> if (p.text.isBlank()) oc?.getOrNull(i)?.let { Ocr.pageText(it) } ?: p else p }
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
            pv.setHits(emptyMap(), -1, emptyList())
            val noText = texts?.all { it.text.isBlank() } == true
            if (noText) {
                AlertDialog.Builder(this, R.style.NbDialog)
                    .setTitle("Mətn qatı yoxdur")
                    .setMessage("Bu PDF skan edilmiş görünür. Axtarmaq üçün əvvəlcə mətni tanıyaq (OCR)?")
                    .setPositiveButton("OCR et") { _, _ -> ocrAll() }
                    .setNegativeButton("Bağla", null)
                    .show()
            } else toast("Tapılmadı")
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
        applyPageHits()
        if (h.page != page) showPage(h.page)
    }

    private fun applyPageHits() {
        if (hits.isEmpty()) {
            pv.setHits(emptyMap(), -1, emptyList())
            return
        }
        val cur = hits.getOrNull(hitIdx)
        val all = HashMap<Int, ArrayList<RectF>>()
        for (h in hits) if (h !== cur) all.getOrPut(h.page) { ArrayList() }.addAll(h.rects)
        pv.setHits(all, cur?.page ?: -1, cur?.rects ?: emptyList())
    }

    private fun buildSearchBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(Color.WHITE, dpf(22f), Ui.LINE, dp(1))
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
