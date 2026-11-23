package com.farshekashan.smsh

import android.content.Context
import java.util.Locale

/** User-configurable SMS filtering. Rules are stored in SharedPreferences ("smsh"). */
object SpamFilter {
    private val defaultTerms = listOf(
        "لغو11", "لغو 11", "تخفیف ویژه", "فروش ویژه", "جشنواره فروش", "همین حالا خرید",
        "خرید اقساطی", "برنده شدید", "شما برنده", "جایزه نقدی", "هدیه رایگان",
        "اعتبار رایگان", "فرصت محدود", "پیشنهاد شگفت", "بازارچه", "تبلیغات",
        "ارسال عدد ۱۱", "لغو پیامک"
    )

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').trim()

    fun isSpam(context: Context, sender: String, body: String): Boolean {
        val prefs = context.getSharedPreferences("smsh", Context.MODE_PRIVATE)
        val normalizedSender = normalize(sender)
        val normalizedBody = normalize(body)
        val trusted = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        if (trusted.any { it.isNotBlank() && normalizedSender.contains(normalize(it)) }) return false

        val blockedNumbers = (prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()) +
            (prefs.getStringSet("blocked", emptySet()) ?: emptySet()) // legacy compatibility
        if (blockedNumbers.any { it.isNotBlank() && normalizedSender.contains(normalize(it)) }) return true

        val blockedTexts = prefs.getStringSet("blocked_texts", emptySet()) ?: emptySet()
        if (blockedTexts.any { it.isNotBlank() && normalizedBody.contains(normalize(it)) }) return true

        val customTerms = prefs.getStringSet("terms", emptySet()) ?: emptySet()
        if (customTerms.any { it.isNotBlank() && normalizedBody.contains(normalize(it)) }) return true

        if (!prefs.getBoolean("auto_spam_enabled", true)) return false
        return defaultTerms.any { normalizedBody.contains(normalize(it)) }
    }
}
