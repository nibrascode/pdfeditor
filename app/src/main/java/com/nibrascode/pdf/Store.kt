package com.nibrascode.pdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Recent(val id: String, val name: String, val time: Long, val pages: Int)

// Son açılanlar: PDF-in cihazdakı şəxsi nüsxəsi, qeydlər (json) və kiçik şəkil (jpg)
class Store(ctx: Context) {
    val dir: File = File(ctx.filesDir, "docs")
    private val prefs = ctx.getSharedPreferences("nibras_pdf", Context.MODE_PRIVATE)

    init {
        dir.mkdirs()
    }

    fun pdf(id: String): File = File(dir, "$id.pdf")

    fun ann(id: String): File = File(dir, "$id.json")

    fun thumb(id: String): File = File(dir, "$id.jpg")

    fun recents(): ArrayList<Recent> {
        val out = ArrayList<Recent>()
        try {
            val arr = JSONArray(prefs.getString("recents", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Recent(o.getString("id"), o.getString("name"), o.getLong("time"), o.getInt("pages")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun write(list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("time", r.time)
            o.put("pages", r.pages)
            arr.put(o)
        }
        prefs.edit().putString("recents", arr.toString()).apply()
    }

    fun touch(id: String, name: String, pages: Int) {
        val list = recents()
        list.removeAll { it.id == id }
        list.add(0, Recent(id, name, System.currentTimeMillis(), pages))
        while (list.size > 12) {
            val old = list.removeAt(list.size - 1)
            wipe(old.id)
        }
        write(list)
    }

    fun remove(id: String) {
        val list = recents()
        list.removeAll { it.id == id }
        write(list)
        wipe(id)
    }

    private fun wipe(id: String) {
        pdf(id).delete()
        ann(id).delete()
        thumb(id).delete()
        dir.listFiles()?.forEach { if (it.name.startsWith(id + "_")) it.delete() }
        prefs.edit().remove("uri_$id").remove("page_$id").remove("pw_$id").remove("ocr_$id").apply()
    }

    // Orijinal faylın ünvanı (üzərinə yazmaq üçün)
    fun setUri(id: String, uri: String?) {
        prefs.edit().putString("uri_$id", uri).apply()
    }

    fun uri(id: String): String? = prefs.getString("uri_$id", null)

    // Qaldığı səhifə
    fun setLastPage(id: String, p: Int) {
        prefs.edit().putInt("page_$id", p).apply()
    }

    fun lastPage(id: String): Int = prefs.getInt("page_$id", 0)

    // Şifrəli sənəd: saxlayanda şifrə yenidən qoyulsun? (yalnız tətbiqin şəxsi yaddaşında)
    fun setPassword(id: String, pw: String?) {
        prefs.edit().putString("pw_$id", pw).apply()
    }

    fun password(id: String): String? = prefs.getString("pw_$id", null)

    var signature: String?
        get() = prefs.getString("sig", null)
        set(v) {
            prefs.edit().putString("sig", v).apply()
        }

    var lang: String
        get() = prefs.getString("lang", "") ?: ""
        set(v) {
            prefs.edit().putString("lang", v).commit()
        }

    // true: «Yadda saxla» orijinal fayla yazır; false: həmişə yeni fayl
    var overwrite: Boolean
        get() = prefs.getBoolean("overwrite", true)
        set(v) {
            prefs.edit().putBoolean("overwrite", v).apply()
        }

    // PDF açılanda tam ekran
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", true)
        set(v) {
            prefs.edit().putBoolean("fullscreen", v).apply()
        }

    fun clearRecents() {
        for (r in recents()) remove(r.id)
    }

    var night: Boolean
        get() = prefs.getBoolean("night", false)
        set(v) {
            prefs.edit().putBoolean("night", v).apply()
        }
}
