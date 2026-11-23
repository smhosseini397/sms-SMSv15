package com.farshekashan.smsh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.Telephony

/** Handles the sent/delivered PendingIntents created by MainActivity. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rowId = intent.getLongExtra("sms_row_id", -1L)
        if (rowId <= 0L) return

        val isSentCallback = intent.action == "com.farshekashan.smsh.SMS_SENT"
        val isDeliveredCallback = intent.action == "com.farshekashan.smsh.SMS_DELIVERED"
        if (!isSentCallback && !isDeliveredCallback) return

        val sentSuccessfully = resultCode == Activity.RESULT_OK
        val values = ContentValues()
        if (isSentCallback) {
            values.put(
                Telephony.Sms.TYPE,
                if (sentSuccessfully) Telephony.Sms.MESSAGE_TYPE_SENT
                else Telephony.Sms.MESSAGE_TYPE_FAILED
            )
            values.put(
                Telephony.Sms.STATUS,
                if (sentSuccessfully) Telephony.Sms.STATUS_COMPLETE
                else Telephony.Sms.STATUS_FAILED
            )
        } else if (sentSuccessfully) {
            values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
        }

        try {
            if (values.size() > 0) {
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms._ID}=?",
                    arrayOf(rowId.toString())
                )
            }

            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.STATUS
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
                        cursor.getInt(6)
                    )
                    SmsStore(context).upsertCachedMessage(row)
                }
            }
        } catch (_: Exception) {
            // A status callback must never crash the SMS app.
        }
    }
}
