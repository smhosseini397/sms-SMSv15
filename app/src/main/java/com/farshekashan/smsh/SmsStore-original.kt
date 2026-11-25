package com.farshekashan.smsh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony

class SmsStore(context: Context) : SQLiteOpenHelper(context, "smsh.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE spam(id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, reviewed INTEGER NOT NULL DEFAULT 0)")
        createMessageCache(db)
    }

    private fun createMessageCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_cache(id INTEGER PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, spam INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 1, read_state INTEGER NOT NULL DEFAULT 1, status INTEGER NOT NULL DEFAULT -1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_cache_address_date ON sms_cache(address, date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMessageCache(db)
        if (oldVersion == 2) db.execSQL("ALTER TABLE sms_cache ADD COLUMN status INTEGER NOT NULL DEFAULT -1")
    }

    /** Persistent on-device archive. It survives Activity and process recreation. */
    fun readCachedMessages(): List<MessageRow> {
        val rows = ArrayList<MessageRow>()
        readableDatabase.rawQuery("SELECT id,address,body,date,spam,type,read_state,status FROM sms_cache ORDER BY date DESC", null).use { c ->
            while (c.moveToNext()) rows += MessageRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0, c.getInt(5), c.getInt(6), c.getInt(7))
        }
        return rows
    }

    fun replaceCachedMessages(rows: List<MessageRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sms_cache", null, null)
            val values = ContentValues()
            for (row in rows) {
                values.clear()
                values.put("id", row.id); values.put("address", row.address); values.put("body", row.body)
                values.put("date", row.date); values.put("spam", if (row.spam) 1 else 0)
                values.put("type", row.type); values.put("read_state", row.read); values.put("status", row.status)
                db.insertWithOnConflict("sms_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun upsertCachedMessage(row: MessageRow) {
        writableDatabase.insertWithOnConflict("sms_cache", null, ContentValues().apply {
            put("id", row.id); put("address", row.address); put("body", row.body); put("date", row.date)
            put("spam", if (row.spam) 1 else 0); put("type", row.type); put("read_state", row.read); put("status", row.status)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }


    /** Marks a whole conversation as read with one SQL statement. */
    fun markConversationRead(address: String) {
        writableDatabase.execSQL(
            "UPDATE sms_cache SET read_state=1 WHERE address=? AND type=? AND read_state=0",
            arrayOf(address, Telephony.Sms.MESSAGE_TYPE_INBOX)
        )
    }

    /** Marks a whole conversation as unread in the persistent message cache. */
    fun markConversationUnread(address: String) {
        writableDatabase.execSQL(
            "UPDATE sms_cache SET read_state=0 WHERE address=? AND type=?",
            arrayOf(address, Telephony.Sms.MESSAGE_TYPE_INBOX)
        )
    }

    fun deleteCachedMessage(id: Long) { deleteCachedMessages(listOf(id)) }

    /** Remove only the selected rows from the persistent cache; keep the rest of the archive intact. */
    fun deleteCachedMessages(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(900).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete("sms_cache", "id IN ($placeholders)", chunk.map { it.toString() }.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Remove one or more complete conversations from the persistent cache. */
    fun deleteCachedAddresses(addresses: Collection<String>) {
        if (addresses.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            addresses.toList().chunked(900).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete("sms_cache", "address IN ($placeholders)", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addSpam(address: String, body: String, date: Long) {
        writableDatabase.insert("spam", null, ContentValues().apply {
            put("address", address); put("body", body); put("date", date); put("reviewed", 0)
        })
    }
    fun spamRows(): List<MessageRow> {
        val out = mutableListOf<MessageRow>()
        readableDatabase.rawQuery("SELECT id,address,body,date FROM spam ORDER BY date DESC", null).use { c ->
            while (c.moveToNext()) out += MessageRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), true)
        }
        return out
    }
    fun deleteSpam(id: Long) { writableDatabase.delete("spam", "id=?", arrayOf(id.toString())) }
    fun deleteSpam(ids: List<Long>) { writableDatabase.beginTransaction(); try { ids.forEach { deleteSpam(it) }; writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() } }
    fun restoreSpam(context: Context, id: Long): Boolean {
        val c = readableDatabase.rawQuery("SELECT address,body,date FROM spam WHERE id=?", arrayOf(id.toString()))
        val values = c.use { if (!it.moveToFirst()) return false else Triple(it.getString(0), it.getString(1), it.getLong(2)) }
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, values.first); put(Telephony.Sms.BODY, values.second)
            put(Telephony.Sms.DATE, values.third); put(Telephony.Sms.READ, 1); put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)
        if (inserted != null) deleteSpam(id)
        return inserted != null
    }
}


data class MessageRow(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val spam: Boolean,
    val type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
    val read: Int = 1,
    val status: Int = -1
)
