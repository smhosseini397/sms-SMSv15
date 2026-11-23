package com.farshekashan.smsh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Updates the SMS row after the modem reports send or delivery status. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rowId = intent.getLongExtra("sms_row_id", -1L)
        if (rowId <= 0L) return

        val sentCallback = intent.action == "com.farshekashan.smsh.SMS_SENT"
        val deliveredCallback = intent.action == "com.farshekashan.smsh.SMS_DELIVERED"
        if (!sentCallback && !deliveredCallback) return

        val success = resultCode == Activity.RESULT_OK
        val newStatus = when {
            sentCallback && success -> Telephony.Sms.STATUS_PENDING // Sent, delivery not confirmed yet.
            sentCallback && !success -> Telephony.Sms.STATUS_FAILED
            deliveredCallback && success -> Telephony.Sms.STATUS_COMPLETE
            else -> Telephony.Sms.STATUS_PENDING
        }
        val newType = if (sentCallback && success) {
            Telephony.Sms.MESSAGE_TYPE_SENT
        } else if (sentCallback && !success) {
            Telephony.Sms.MESSAGE_TYPE_FAILED
        } else {
            Telephony.Sms.MESSAGE_TYPE_SENT
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

                    // Refresh the visible tick immediately if MainActivity is alive.
                    context.sendBroadcast(Intent("com.farshekashan.smsh.SMS_STATUS_CHANGED").apply {
                        setPackage(context.packageName)
                        putExtra("sms_row_id", rowId)
                        putExtra("sms_status", newStatus)
                        putExtra("sms_type", row.type)
                    })
                }
            }
        } catch (_: Exception) {
            // A status callback must never crash the SMS app.
        }
    }
}
