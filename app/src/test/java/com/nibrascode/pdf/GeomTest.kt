package com.nibrascode.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeomTest {
    private fun near(a: Float, b: Float) = assertEquals(b, a, 1e-3f)

    @Test fun smoothFollowsFreehandPath() {
        // Zigzag: marker must not collapse to a straight line
        val p = listOf(0f, 0f, 0.1f, 0.2f, 0.2f, 0f, 0.3f, 0.2f)
        val s = Geom.smooth(p, 0f, 0f, 100f, 100f)!!
        near(s.sx, 0f); near(s.ex, 30f); near(s.ey, 20f)
        assertEquals(2 * 4, s.quads.size)
        near(s.quads[0], 10f); near(s.quads[1], 20f)   // control = sample
        near(s.quads[2], 15f); near(s.quads[3], 10f)   // midpoint
    }

    @Test fun smoothTwoPointsIsLine() {
        val s = Geom.smooth(listOf(0.1f, 0.5f, 0.9f, 0.5f), 10f, 20f, 100f, 200f)!!
        assertEquals(0, s.quads.size); near(s.sx, 20f); near(s.sy, 120f); near(s.ex, 100f)
    }

    @Test fun displayMatrixCornersAllRotations() {
        // cropBox 10,20 w=200 h=300
        val m0 = Geom.displayMatrix(0, 10f, 20f, 200f, 300f)
        assertEquals(Pair(10f, 320f), Geom.apply(m0, 0f, 0f))
        assertEquals(Pair(210f, 20f), Geom.apply(m0, 200f, 300f))
        val m90 = Geom.displayMatrix(90, 10f, 20f, 200f, 300f) // displayed W=300,H=200
        assertEquals(Pair(10f, 20f), Geom.apply(m90, 0f, 0f))
        assertEquals(Pair(210f, 320f), Geom.apply(m90, 300f, 200f))
        val m180 = Geom.displayMatrix(180, 10f, 20f, 200f, 300f)
        assertEquals(Pair(210f, 20f), Geom.apply(m180, 0f, 0f))
        val m270 = Geom.displayMatrix(-90, 10f, 20f, 200f, 300f)
        assertEquals(Pair(210f, 320f), Geom.apply(m270, 0f, 0f))
        assertEquals(Pair(10f, 20f), Geom.apply(m270, 300f, 200f))
    }

    @Test fun farEnoughFilter() {
        assertTrue(!Geom.farEnough(0f, 0f, 0.5f, 0.5f, 1f))
        assertTrue(Geom.farEnough(0f, 0f, 1f, 1f, 1f))
    }

    @Test fun rotateAnnRoundTrip() {
        val a = Ann(A_PEN, 0, 0.01f); a.add(0.2f, 0.7f)
        rotateAnn(a, true); near(a.p[0], 0.3f); near(a.p[1], 0.2f)
        rotateAnn(a, false); near(a.p[0], 0.2f); near(a.p[1], 0.7f)
    }

    @Test fun undoRedoAddRemoveMove() {
        val pd = PageData()
        val a = Ann(A_HL, 0, 0.02f); a.add(0.1f, 0.1f); a.add(0.2f, 0.3f)
        pd.addAnn(a)
        val old = a2f(a); a.p[0] = 0.5f; pd.commitMove(a, old)
        pd.removeAnn(a)
        assertTrue(pd.anns.isEmpty())
        pd.doUndo(); assertEquals(1, pd.anns.size)
        pd.doUndo(); near(a.p[0], 0.1f)
        pd.doUndo(); assertTrue(pd.anns.isEmpty())
        pd.doRedo(); pd.doRedo(); near(a.p[0], 0.5f)
    }
}
