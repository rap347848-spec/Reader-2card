package com.talalqasem.netcard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedCardsActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var cards = mutableListOf<Card>()
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved)
        list = findViewById(R.id.cardsList)
        empty = findViewById(R.id.emptyText)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            if (cards.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("مسح كل البطاقات؟")
                .setMessage("سيتم حذف جميع البطاقات المحفوظة وسجلها.")
                .setPositiveButton("مسح") { _, _ ->
                    CardStore.clear(this)
                    refresh()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        list.setOnItemClickListener { _, _, pos, _ -> showActions(cards[pos]) }
        refresh()
    }

    private fun refresh() {
        cards = CardStore.all(this)
        list.adapter = object : ArrayAdapter<Card>(this, 0, cards) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_card, parent, false)
                val c = cards[position]
                row.findViewById<TextView>(R.id.tvUser).text = "اليوزر: ${c.user}"
                row.findViewById<TextView>(R.id.tvPass).text = "كلمة السر: ${c.pass}"
                val saved = "حُفظت: ${fmt.format(Date(c.savedAt))}"
                val used = if (c.uses > 0)
                    "\nآخر استخدام: ${fmt.format(Date(c.lastUsed))} · النتيجة: ${c.lastResult} · عدد المرات: ${c.uses}"
                else "\nلم تُستخدم بعد"
                row.findViewById<TextView>(R.id.tvInfo).text = saved + used
                return row
            }
        }
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showActions(c: Card) {
        AlertDialog.Builder(this)
            .setTitle(c.user)
            .setItems(arrayOf("تسجيل الدخول بهذه البطاقة", "نسخ الرقمين", "حذف البطاقة")) { _, which ->
                when (which) {
                    0 -> {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra("user", c.user).putExtra("pass", c.pass)
                        )
                        finish()
                    }
                    1 -> {
                        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("card", "${c.user}\n${c.pass}"))
                        Toast.makeText(this, "تم نسخ الرقمين ✓", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        CardStore.delete(this, c.user)
                        refresh()
                    }
                }
            }
            .show()
    }
}
