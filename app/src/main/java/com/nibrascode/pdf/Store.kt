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
    }

    var night: Boolean
        get() = prefs.getBoolean("night", false)
        set(v) {
            prefs.edit().putBoolean("night", v).apply()
        }
}
