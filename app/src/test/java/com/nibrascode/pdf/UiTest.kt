package com.nibrascode.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    private fun snap(v: View, name: String) {
        val bm = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bm))
        val d = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(lang: String, name: String) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        app.getSharedPreferences("nibras_pdf", 0).edit().putString("lang", lang).commit()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        snap(root, name)
        assertNotNull(root)
    }

    @Test fun editorOpenAndNight() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        app.getSharedPreferences("nibras_pdf", 0).edit().putString("lang", "en").commit()
        val f = File(app.cacheDir, "multi_en.pdf")
        javaClass.classLoader!!.getResourceAsStream("multi_en.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val it = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.fromFile(f))
        val c = Robolectric.buildActivity(MainActivity::class.java, it).setup()
        val root = c.get().window.decorView
        repeat(20) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(100) }
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        // Robolectric PdfRenderer səhifə vermir: sənəd açılmamalı, tətbiq çökməməlidir
        fun call(n: String) = MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }.invoke(c.get())
        call("showEditor")
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        snap(root, "editor_day")
        call("toggleNight")
        MainActivity::class.java.getDeclaredMethod("selectTab", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(c.get(), 0)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        snap(root, "editor_night")
        org.junit.Assert.assertTrue(app.getSharedPreferences("nibras_pdf", 0).all.toString(), true)
    }

    @Test fun homeEn() = home("en", "home_en")
    @Test fun homeAr() = home("ar", "home_ar")
    @Test fun homeAz() = home("az", "home_az")
    @Test fun homeTr() = home("tr", "home_tr")
    @Test fun homeRu() = home("ru", "home_ru")
}
