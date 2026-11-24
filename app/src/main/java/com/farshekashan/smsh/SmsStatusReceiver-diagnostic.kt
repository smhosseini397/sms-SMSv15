package com.farshekashan.smsh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Updates the SMS row after the modem reports send or delivery status. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rowId = intent.getLongExtra("sms_row_id", -1L)
        if (rowId <= 0L) return

        val sentCallback = intent.action == "com.farshekashan.smsh.SMS_SENT"
        val deliveredCallback = intent.action == "com.farshekashan.smsh.SMS_DELIVERED"
        if (!sentCallback && !deliveredCallback) return

        val partCount = intent.getIntExtra("sms_part_count", 1).coerceAtLeast(1)
        val partIndex = intent.getIntExtra("sms_part_index", 0).coerceAtLeast(0)
        val success = resultCode == Activity.RESULT_OK
        val modemErrorCode = intent.getIntExtra("errorCode", -1)
        Log.i(
            "SMS_SEND_DEBUG",
            "callback=${intent.action}, row=$rowId, part=$partIndex/$partCount, result=$resultCode, errorCode=$modemErrorCode"
        )
        val prefs = context.getSharedPreferences("sms_part_status", Context.MODE_PRIVATE)
        val prefix = if (sentCallback) "sent" else "delivered"
        val partKey = "$prefix:$rowId:$partIndex"

        // Each multipart segment has its own PendingIntent. Do not mark the single
        // conversation row as sent/delivered until every segment reports back.
        val completed = prefs.edit().putBoolean(partKey, success).commit()
        if (!completed) return

        var callbacksReceived = 0
        var allSucceeded = true
        for (index in 0 until partCount) {
            val key = "$prefix:$rowId:$index"
            if (prefs.contains(key)) {
                callbacksReceived++
                if (!prefs.getBoolean(key, false)) allSucceeded = false
            }
        }
        if (callbacksReceived < partCount) return

        val newStatus: Int
        val newType: Int
        if (sentCallback) {
            // A delivery report may race with the final sent callback. Preserve COMPLETE
            // if all delivery callbacks have already arrived successfully.
            var allDelivered = true
            var deliveredCount = 0
            for (index in 0 until partCount) {
                val key = "delivered:$rowId:$index"
                if (prefs.contains(key)) {
                    deliveredCount++
                    if (!prefs.getBoolean(key, false)) allDelivered = false
                }
            }
            newStatus = when {
                !allSucceeded -> Telephony.Sms.STATUS_FAILED
                deliveredCount == partCount && allDelivered -> Telephony.Sms.STATUS_COMPLETE
                else -> Telephony.Sms.STATUS_PENDING
            }
            newType = if (allSucceeded) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        } else {
            // A delivery report is only definitive when every segment is confirmed.
            newStatus = if (allSucceeded) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_PENDING
            newType = Telephony.Sms.MESSAGE_TYPE_SENT
        }

        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.STATUS, newStatus)
                if (sentCallback) put(Telephony.Sms.TYPE, newType)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID}=?",
                arrayOf(rowId.toString())
            )

            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms._ID}=?",
                arrayOf(rowId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val row = MessageRow(
                        cursor.getLong(0),
                        cursor.getString(1) ?: "",
                        cursor.getString(2) ?: "",
                        cursor.getLong(3),
                        false,
                        cursor.getInt(4),
                        cursor.getInt(5),
                        newStatus
                    )
                    SmsStore(context).upsertCachedMessage(row)
                    context.sendBroadcast(Intent("com.farshekashan.smsh.SMS_STATUS_CHANGED").apply {
                        setPackage(context.packageName)
                        putExtra("sms_row_id", rowId)
                        putExtra("sms_status", newStatus)
                        putExtra("sms_type", row.type)
                    })
                }
            }

            // Keep callback markers until both sent and delivery callbacks have
            // completed; on some networks delivery reports can race with sent reports.
            var sentComplete = true
            var deliveredComplete = true
            for (index in 0 until partCount) {
                if (!prefs.contains("sent:$rowId:$index")) sentComplete = false
                if (!prefs.contains("delivered:$rowId:$index")) deliveredComplete = false
            }
            if (sentComplete && deliveredComplete) {
                val editor = prefs.edit()
                for (index in 0 until partCount) {
                    editor.remove("sent:$rowId:$index")
                    editor.remove("delivered:$rowId:$index")
                }
                editor.commit()
            }
        } catch (_: Exception) {
            // A status callback must never crash the SMS app.
        }
    }
}
