package com.nibrascode.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Qələm/alət ayarları tətbiq yenidən açıldıqdan sonra da qalır
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolPrefsTest {
    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun defaultsWhenNothingSaved() {
        val s = Store(ctx())
        assertEquals(TL_PEN, s.lastTool)
        assertEquals(0xFF1E88E5.toInt(), s.toolInk(TL_PEN, 0xFF1E88E5.toInt()))
        assertEquals(0.3f, s.toolAmt(TL_PEN, 0.3f), 1e-6f)
        assertEquals(false, s.hlStraight)
        assertEquals(0, s.shape)
    }

    @Test fun settingsSurviveNewStoreInstance() {
        val a = Store(ctx())
        a.setToolInk(TL_PEN, 0xFFE53935.toInt())
        a.setToolAmt(TL_PEN, 0.75f)
        a.setToolInk(TL_HL, 0xFF43A047.toInt())
        a.hlStraight = true
        a.shape = 2
        a.lastTool = TL_HL
        a.lastTool = TL_VIEW // əl rejimi son alət kimi yadda saxlanmır
        val b = Store(ctx()) // "yenidən açılış"
        assertEquals(0xFFE53935.toInt(), b.toolInk(TL_PEN, 0))
        assertEquals(0.75f, b.toolAmt(TL_PEN, 0f), 1e-6f)
        assertEquals(0xFF43A047.toInt(), b.toolInk(TL_HL, 0))
        assertTrue(b.hlStraight)
        assertEquals(2, b.shape)
        assertEquals(TL_HL, b.lastTool)
    }

    @Test fun activityRestoresPenSettings() {
        val st = Store(ctx())
        st.setToolInk(TL_PEN, 0xFF8E24AA.toInt())
        st.setToolAmt(TL_PEN, 0.9f)
        st.lastTool = TL_PEN
        val act = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val f = MainActivity::class.java.getDeclaredField("toolInk").apply { isAccessible = true }
        val g = MainActivity::class.java.getDeclaredField("toolAmt").apply { isAccessible = true }
        assertEquals(0xFF8E24AA.toInt(), (f.get(act) as IntArray)[TL_PEN])
        assertEquals(0.9f, (g.get(act) as FloatArray)[TL_PEN], 1e-6f)
    }
}
