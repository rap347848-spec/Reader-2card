package com.talalqasem.netcard

import com.google.mlkit.vision.text.Text

/**
 * يستخرج الرقمين من نص البطاقة:
 * الرقم الطويل (اليوزر) والرقم القصير الذي تحته (كلمة السر).
 */
object CardParser {
    data class CardPair(val user: String, val pass: String)

    private data class Item(val digits: String, val top: Int)

    private val digitRun = Regex("\\d+(?:[ \\-]\\d+)*")

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun parse(text: Text, minUser: Int, maxPass: Int): CardPair? {
        val items = ArrayList<Item>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val t = normalize(line.text)
                val top = line.boundingBox?.top ?: 0
                for (m in digitRun.findAll(t)) {
                    val d = m.value.filter { it.isDigit() }
                    if (d.length >= 3) items.add(Item(d, top))
                }
            }
        }
        // اليوزر: أطول رقم (وعند التساوي الأعلى في البطاقة)
        val user = items.filter { it.digits.length >= minUser }
            .maxWithOrNull(compareBy<Item>({ it.digits.length }, { -it.top })) ?: return null
        // كلمة السر: أقرب رقم قصير تحت اليوزر
        val pass = items.filter {
            it !== user && it.digits.length in 3..maxPass &&
                it.digits.length < user.digits.length && it.top > user.top
        }.minByOrNull { it.top } ?: return null
        return CardPair(user.digits, pass.digits)
    }
}
