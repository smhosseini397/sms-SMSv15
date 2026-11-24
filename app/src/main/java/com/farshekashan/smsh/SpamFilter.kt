package com.farshekashan.smsh

import android.content.Context
import java.util.Locale

/**
 * Heuristic Persian SMS advertising detector.
 *
 * It deliberately favors multiple independent advertising signals over a single
 * generic word, so ordinary bank, delivery and verification messages are less
 * likely to be classified as advertising.
 */
object SpamFilter {
    private val highConfidencePhrases = listOf(
        "لغو11", "لغو 11", "لغو پیامک", "ارسال عدد 11", "ارسال عدد ۱۱",
        "فرصت محدود", "همین حالا خرید", "خرید اقساطی", "فروش ویژه",
        "تخفیف ویژه", "جشنواره فروش", "برنده شدید", "شما برنده",
        "جایزه نقدی", "هدیه رایگان", "اعتبار رایگان", "پیشنهاد شگفت انگیز",
        "پیشنهاد شگفت‌انگیز", "فقط امروز", "تخفیف استثنایی",
        "ثبت سفارش", "خرید آنلاین", "همین الان سفارش"
    )

    private val promotionalSignals = listOf(
        "تخفیف", "حراج", "فروش ویژه", "جشنواره", "کد تخفیف", "کوپن",
        "هدیه", "رایگان", "جایزه", "باشگاه مشتریان", "دعوت به خرید",
        "خرید کنید", "سفارش دهید", "همین حالا", "همین الان", "فرصت استثنایی",
        "فروشگاه", "محصولات", "ارسال رایگان", "اقساطی", "پیش خرید",
        "پیش‌خرید", "بازارچه", "تبلیغات", "کمپین", "فقط تا", "ویژه شما",
        "مشاهده محصولات", "لینک خرید", "ثبت نام", "ثبت‌نام"
    )

    private val callToActionSignals = listOf(
        "کلیک کنید", "اینجا کلیک", "برای خرید", "برای ثبت سفارش",
        "جهت خرید", "مشاهده و خرید", "از دست ندهید", "همراه ما باشید",
        "به سایت", "وارد لینک", "لغو11", "لغو 11"
    )

    private val serviceOrSecuritySignals = listOf(
        "رمز پویا", "رمز یکبار مصرف", "رمز یک‌بار مصرف", "کد تایید",
        "کد تأیید", "کد ورود", "کد اعتبارسنجی", "رمز ورود",
        "برداشت", "واریز", "مانده حساب", "تراکنش", "صورتحساب",
        "ابلاغیه", "مرسوله", "رهگیری مرسوله", "کد رهگیری", "ناحیه پستی"
    )

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه')
        .replace('۰', '0').replace('۱', '1').replace('۲', '2')
        .replace('۳', '3').replace('۴', '4').replace('۵', '5')
        .replace('۶', '6').replace('۷', '7').replace('۸', '8')
        .replace('۹', '9')
        .replace(Regex("[\\u200c\\u200f\\u200e]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun isSpam(context: Context, sender: String, body: String): Boolean {
        val prefs = context.getSharedPreferences("smsh", Context.MODE_PRIVATE)
        val normalizedSender = normalize(sender)
        val normalizedBody = normalize(body)

        val trusted = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        if (trusted.any { it.isNotBlank() && normalizedSender.contains(normalize(it)) }) return false

        val blockedNumbers = (prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()) +
            (prefs.getStringSet("blocked", emptySet()) ?: emptySet())
        if (blockedNumbers.any { it.isNotBlank() && normalizedSender.contains(normalize(it)) }) return true

        val blockedTexts = prefs.getStringSet("blocked_texts", emptySet()) ?: emptySet()
        if (blockedTexts.any { it.isNotBlank() && normalizedBody.contains(normalize(it)) }) return true

        val customTerms = prefs.getStringSet("terms", emptySet()) ?: emptySet()
        if (customTerms.any { it.isNotBlank() && normalizedBody.contains(normalize(it)) }) return true

        if (!prefs.getBoolean("auto_spam_enabled", true)) return false
        if (normalizedBody.isBlank()) return false

        // Explicit advertising language is sufficient on its own.
        if (highConfidencePhrases.any { normalizedBody.contains(normalize(it)) }) return true

        // Do not classify recognizable transactional/security notices on weak signals.
        val serviceMessage = serviceOrSecuritySignals.any { normalizedBody.contains(normalize(it)) }
        val promoCount = promotionalSignals.count { normalizedBody.contains(normalize(it)) }
        val actionCount = callToActionSignals.count { normalizedBody.contains(normalize(it)) }
        val hasUrl = Regex("(https?://|www\\.)").containsMatchIn(normalizedBody)
        val hasShortCodeUnsubscribe = Regex("(لغو|انصراف)\\s*\\d{1,3}").containsMatchIn(normalizedBody)

        if (serviceMessage && promoCount < 2 && !hasShortCodeUnsubscribe) return false

        // Two different promotional signals, or one promotional signal plus
        // a purchase call-to-action/link, provide a stronger default decision.
        if (promoCount >= 2) return true
        if (promoCount >= 1 && (actionCount >= 1 || hasUrl)) return true
        if (hasShortCodeUnsubscribe && (promoCount >= 1 || hasUrl)) return true
        return false
    }
}
