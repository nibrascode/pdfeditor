package com.nibrascode.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfOpsTest {
    private lateinit var dir: File

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PdfOps.init(ctx)
        dir = File(ctx.cacheDir, "t").apply { mkdirs() }
    }

    private fun res(n: String): File {
        val f = File(dir, n)
        javaClass.classLoader!!.getResourceAsStream(n).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun text(b: ByteArray, pw: String = ""): String = PDDocument.load(b, pw).use { PDFTextStripper().getText(it) }

    @Test fun exportWritesSelectableTypedTextAndKeepsOriginal() {
        val src = res("multi_en.pdf")
        val anns = List(6) { ArrayList<Ann>() }
        anns[0].add(Ann(A_TEXT, 0xFF000000.toInt(), 0.03f, "Hello Nibras").also { it.add(0.1f, 0.5f) })
        val pen = Ann(A_PEN, 0xFFFF0000.toInt(), 0.01f); for (i in 0..20) pen.add(0.1f + i * 0.02f, 0.6f + (i % 3) * 0.01f)
        anns[0].add(pen)
        val hl = Ann(A_HL, 0xFFFFFF00.toInt(), 0.03f); hl.add(0.1f, 0.7f); hl.add(0.3f, 0.75f); hl.add(0.5f, 0.7f)
        anns[1].add(hl)
        val out = ByteArrayOutputStream()
        PdfOps.export(src, anns, out)
        val t = text(out.toByteArray())
        assertTrue(t, t.contains("Hello Nibras"))
        assertTrue(t.contains("elephant"))
        PDDocument.load(out.toByteArray()).use { assertEquals(6, it.numberOfPages) }
    }

    @Test fun arabicTypedTextIsExtractable() {
        val src = res("multi_en.pdf")
        val anns = List(6) { ArrayList<Ann>() }
        anns[0].add(Ann(A_TEXT, 0xFF000000.toInt(), 0.03f, "سلام").also { it.add(0.1f, 0.3f) })
        val out = ByteArrayOutputStream()
        PdfOps.export(src, anns, out)
        val t = text(out.toByteArray())
        assertTrue(t, t.contains("س") || t.contains("ﺳ") || t.contains("ﺱ"))
    }

    @Test fun exportSubsetOnlySelectedPages() {
        val out = ByteArrayOutputStream()
        PdfOps.export(res("multi_en.pdf"), List(6) { emptyList<Ann>() }, out, setOf(1, 3))
        val t = text(out.toByteArray())
        assertTrue(t.contains("Page 2") && t.contains("Page 4") && !t.contains("Page 1:"))
    }

    @Test fun rebuildReorderRotateDuplicateInsertMerge() {
        val a = res("multi_en.pdf"); val b = res("arabic.pdf")
        val dest = File(dir, "r.pdf")
        val specs = listOf(PageSpec(0, 2), PageSpec(0, 0, 90), PageSpec(0, 0), PageSpec(0, -1), PageSpec(1, 0))
        PdfOps.rebuild(listOf(a, b), specs, dest)
        PDDocument.load(dest).use {
            assertEquals(5, it.numberOfPages)
            assertEquals(90, it.getPage(1).rotation)
            val s = PDFTextStripper(); s.startPage = 1; s.endPage = 1
            assertTrue(s.getText(it).contains("Page 3"))
        }
        assertEquals(5, PdfOps.pageCount(dest))
    }

    @Test fun passwordEncryptDecrypt() {
        val out = ByteArrayOutputStream()
        PdfOps.encrypt(res("multi_en.pdf").readBytes(), "pw1", out)
        var failed = false
        try { text(out.toByteArray()) } catch (e: Exception) { failed = true }
        assertTrue("must need password", failed)
        assertTrue(text(out.toByteArray(), "pw1").contains("elephant"))
        val enc = File(dir, "e.pdf").apply { writeBytes(out.toByteArray()) }
        val dec = File(dir, "d.pdf")
        PdfOps.decrypt(enc, "pw1", dec)
        assertTrue(text(dec.readBytes()).contains("elephant"))
        val lock = File(dir, "l2.pdf"); PdfOps.decrypt(res("locked.pdf"), "test123", lock)
        assertEquals(6, PdfOps.pageCount(lock))
    }

    @Test fun searchFindsKeyword() {
        val pt = PdfOps.extract(res("multi_en.pdf")) { _, _ -> }
        val hits = PdfOps.search(pt, "elephant")
        assertEquals(6, hits.size)
        assertTrue(hits[0].rects.isNotEmpty())
    }

    @Test fun annotationsJsonRoundTrip() {
        val p = listOf(PageData(), PageData())
        val a = Ann(A_NOTE, 0xFF112233.toInt(), 0.02f, "note\nline2"); a.add(0.2f, 0.3f); p[1].anns.add(a)
        val q = listOf(PageData(), PageData())
        jsonToAnns(annsToJson(p), q)
        assertEquals("note\nline2", q[1].anns[0].text)
        assertEquals(0.3f, q[1].anns[0].p[1], 1e-5f)
    }
}
