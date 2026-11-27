package com.farshekashan.smsh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        } catch (_: Exception) {
            emptyList()
        }

        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: "ناشناس"
        val messageBody = messages.joinToString(separator = "") { message ->
            message.messageBody ?: ""
        }
        val timestamp = messages.first().timestampMillis

        if (SpamFilter.isSpam(context, sender, messageBody)) {
            SmsStore(context).addSpam(sender, messageBody, timestamp)
            return
        }

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, messageBody)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }

        val savedUri = try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: Exception) {
            null
        }

        if (savedUri != null) {
            val id = savedUri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                try {
                    SmsStore(context).upsertCachedMessage(
                        MessageRow(id, sender, messageBody, timestamp, false,
                            Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
                    )
                } catch (_: Exception) {
                    // The system SMS provider remains the source of truth.
                }

                // If MainActivity is alive, update its RAM cache immediately.
                val updateIntent = Intent("com.farshekashan.smsh.SMS_DATA_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("sms_row_id", id)
                    putExtra("sms_address", sender)
                    putExtra("sms_body", messageBody)
                    putExtra("sms_date", timestamp)
                }
                context.sendBroadcast(updateIntent)
            }
            showNotification(context, sender, messageBody)
        }
    }

    private fun showNotification(context: Context, sender: String, messageBody: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "new_sms"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "پیامک‌های جدید",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_sms_address", sender)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            sender.hashCode(),
            openIntent,
            flags
        )

        try {
            val notificationBuilder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.app.Notification.Builder(context, channelId)
                } else {
                    android.app.Notification.Builder(context)
                }

            val notification = notificationBuilder
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentTitle(sender)
                .setContentText(messageBody)
                .setStyle(android.app.Notification.BigTextStyle().bigText(messageBody))
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .build()

            // Keep one notification per sender so opening that conversation can clear it.
            manager.notify(sender.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission may not have been granted.
        }
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS reception is not implemented in this version.
    }
}
