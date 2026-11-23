package com.farshekashan.smsh

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.os.Build

/** Receives newly delivered SMS messages for the default SMS application. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent).toList() }
            catch (_: Exception) { emptyList() }
        if (parts.isEmpty()) return

        val sender = parts.first().originatingAddress ?: "ناشناس"
        val body = parts.joinToString(separator = "") { it.messageBody ?: "" }
        val date = parts.first().timestampMillis
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val uri = try { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
            catch (_: Exception) { null }
        val id = uri?.lastPathSegment?.toLongOrNull()
        if (id != null) {
            try {
                SmsStore(context).upsertCachedMessage(
                    MessageRow(id, sender, body, date, false, Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
                )
            } catch (_: Exception) {}
        }
        context.sendBroadcast(Intent("com.farshekashan.smsh.SMS_DATA_CHANGED").setPackage(context.packageName))
    }
}

/** Updates the local/provider state as SMS sending and delivery callbacks arrive. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("sms_row_id", -1L)
        if (id <= 0L) return
        val delivered = intent.action == "com.farshekashan.smsh.SMS_DELIVERED"
        val sent = intent.action == "com.farshekashan.smsh.SMS_SENT"
        if (!delivered && !sent) return

        val success = resultCode == android.app.Activity.RESULT_OK
        var deliveryConfirmed = false
        if (delivered) {
            try {
                val pdu = intent.getByteArrayExtra("pdu")
                val format = intent.getStringExtra("format")
                if (pdu != null) {
                    val report = if (Build.VERSION.SDK_INT >= 23 && !format.isNullOrBlank()) {
                        SmsMessage.createFromPdu(pdu, format)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsMessage.createFromPdu(pdu)
                    }
                    deliveryConfirmed = report.status in 0..31
                }
            } catch (_: Exception) {}
        }
        val type = when {
            delivered && deliveryConfirmed -> Telephony.Sms.MESSAGE_TYPE_SENT
            delivered -> Telephony.Sms.MESSAGE_TYPE_SENT
            success -> Telephony.Sms.MESSAGE_TYPE_SENT
            else -> Telephony.Sms.MESSAGE_TYPE_FAILED
        }
        val status = when {
            delivered && deliveryConfirmed -> Telephony.Sms.STATUS_COMPLETE
            delivered -> Telephony.Sms.STATUS_PENDING
            success -> Telephony.Sms.STATUS_PENDING
            else -> Telephony.Sms.STATUS_FAILED
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.STATUS, status)
        }
        try {
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms._ID}=?", arrayOf(id.toString()))
        } catch (_: Exception) {}

        // Refresh the persisted cache row from the provider so all message fields remain intact.
        try {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                    Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS),
                "${Telephony.Sms._ID}=?", arrayOf(id.toString()), null)?.use { c ->
                if (c.moveToFirst()) {
                    SmsStore(context).upsertCachedMessage(
                        MessageRow(id, c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2),
                            false, c.getInt(3), c.getInt(4), c.getInt(5))
                    )
                }
            }
        } catch (_: Exception) {}
        context.sendBroadcast(Intent("com.farshekashan.smsh.SMS_DATA_CHANGED").setPackage(context.packageName))
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS handling is not part of this SMS-only implementation.
    }
}
