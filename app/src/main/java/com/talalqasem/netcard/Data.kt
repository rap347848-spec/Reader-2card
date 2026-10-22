package com.talalqasem.netcard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Card(
    val user: String,
    val pass: String,
    val savedAt: Long,
    var lastUsed: Long = 0L,
    var lastResult: String = "",
    var uses: Int = 0
)

/** تخزين البطاقات المحفوظة مع سجل الاستخدام */
object CardStore {
    private const val KEY = "list"

    private fun prefs(c: Context) = c.getSharedPreferences("cards", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Card> {
        val out = mutableListOf<Card>()
        try {
            val arr = JSONArray(prefs(c).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Card(
                        o.getString("u"), o.getString("p"), o.optLong("s"),
                        o.optLong("l"), o.optString("r"), o.optInt("n")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun write(c: Context, list: List<Card>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("u", it.user).put("p", it.pass).put("s", it.savedAt)
                    .put("l", it.lastUsed).put("r", it.lastResult).put("n", it.uses)
            )
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** يحفظ البطاقة. يرجع true إذا كانت جديدة. */
    fun save(c: Context, user: String, pass: String): Boolean {
        val list = all(c)
        val existing = list.firstOrNull { it.user == user }
        if (existing != null && existing.pass == pass) return false
        if (existing != null) list.remove(existing)
        list.add(0, Card(user, pass, System.currentTimeMillis()))
        write(c, list)
        return true
    }

    fun recordUse(c: Context, user: String, result: String) {
        val list = all(c)
        val card = list.firstOrNull { it.user == user } ?: return
        card.lastUsed = System.currentTimeMillis()
        card.lastResult = result
        card.uses += 1
        write(c, list)
    }

    fun delete(c: Context, user: String) {
        val list = all(c)
        list.removeAll { it.user == user }
        write(c, list)
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(KEY).apply()
    }
}

/** الإعدادات المتقدمة */
object AppSettings {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun advanced(c: Context) = p(c).getBoolean("adv", false)
    fun setAdvanced(c: Context, v: Boolean) = p(c).edit().putBoolean("adv", v).apply()

    fun minUser(c: Context) = if (advanced(c)) p(c).getInt("minUser", 8) else 8
    fun maxPass(c: Context) = if (advanced(c)) p(c).getInt("maxPass", 8) else 8
    fun flashDefault(c: Context) = advanced(c) && p(c).getBoolean("flash", false)

    fun savedMinUser(c: Context) = p(c).getInt("minUser", 8)
    fun savedMaxPass(c: Context) = p(c).getInt("maxPass", 8)
    fun savedFlash(c: Context) = p(c).getBoolean("flash", false)

    fun save(c: Context, minUser: Int, maxPass: Int, flash: Boolean) {
        p(c).edit().putInt("minUser", minUser).putInt("maxPass", maxPass)
            .putBoolean("flash", flash).apply()
    }
}
