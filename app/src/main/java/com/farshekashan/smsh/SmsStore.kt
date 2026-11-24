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
        createTrash(db)
    }

    private fun createMessageCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_cache(id INTEGER PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, spam INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 1, read_state INTEGER NOT NULL DEFAULT 1, status INTEGER NOT NULL DEFAULT -1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_cache_address_date ON sms_cache(address, date)")
    }

    private fun createTrash(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trash_messages(trash_id INTEGER PRIMARY KEY AUTOINCREMENT, batch_id TEXT NOT NULL, original_id INTEGER NOT NULL, address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, spam INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 1, read_state INTEGER NOT NULL DEFAULT 1, status INTEGER NOT NULL DEFAULT -1, deleted_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trash_batch_address ON trash_messages(batch_id,address)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trash_deleted_at ON trash_messages(deleted_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMessageCache(db)
        if (oldVersion == 2) db.execSQL("ALTER TABLE sms_cache ADD COLUMN status INTEGER NOT NULL DEFAULT -1")
        if (oldVersion < 4) createTrash(db)
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


    /** Saves messages to a recoverable trash batch before removing them from Android's SMS provider. */
    fun moveToTrash(rows: List<MessageRow>, batchId: String, deletedAt: Long = System.currentTimeMillis()): Int {
        if (rows.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        var saved = 0
        try {
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("batch_id", batchId); put("original_id", row.id); put("address", row.address)
                    put("body", row.body); put("date", row.date); put("spam", if (row.spam) 1 else 0)
                    put("type", row.type); put("read_state", row.read); put("status", row.status)
                    put("deleted_at", deletedAt)
                }
                if (db.insert("trash_messages", null, values) == -1L) {
                    throw IllegalStateException("Could not archive every message")
                }
                saved++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return saved
    }

    fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int =
        writableDatabase.delete("trash_messages", "deleted_at<=?", arrayOf((now - 24L * 60L * 60L * 1000L).toString()))

    fun trashGroups(): List<TrashGroup> {
        val groups = ArrayList<TrashGroup>()
        readableDatabase.rawQuery(
            "SELECT batch_id,address,COUNT(*),MAX(deleted_at),MAX(date) FROM trash_messages GROUP BY batch_id,address ORDER BY MAX(deleted_at) DESC", null
        ).use { c ->
            while (c.moveToNext()) groups += TrashGroup(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }
        return groups
    }

    fun restoreTrashGroup(context: Context, batchId: String, address: String): Int {
        val rows = ArrayList<Pair<Long, MessageRow>>()
        readableDatabase.rawQuery(
            "SELECT trash_id,original_id,address,body,date,spam,type,read_state,status FROM trash_messages WHERE batch_id=? AND address=? ORDER BY date ASC",
            arrayOf(batchId, address)
        ).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to MessageRow(
                c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) != 0,
                c.getInt(6), c.getInt(7), c.getInt(8)
            )
        }
        var restored = 0
        rows.forEach { (trashId, row) ->
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, row.address); put(Telephony.Sms.BODY, row.body)
                put(Telephony.Sms.DATE, row.date); put(Telephony.Sms.READ, row.read)
                put(Telephony.Sms.TYPE, row.type); put(Telephony.Sms.STATUS, row.status)
            }
            try {
                val inserted = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                if (inserted != null) {
                    writableDatabase.delete("trash_messages", "trash_id=?", arrayOf(trashId.toString()))
                    restored++
                }
            } catch (_: Exception) { }
        }
        return restored
    }

    fun permanentlyDeleteTrashGroup(batchId: String, address: String): Int =
        writableDatabase.delete("trash_messages", "batch_id=? AND address=?", arrayOf(batchId, address))

    /** Roll back a newly-created trash batch when the SMS provider rejects deletion. */
    fun discardTrashBatch(batchId: String): Int =
        writableDatabase.delete("trash_messages", "batch_id=?", arrayOf(batchId))

    fun trashMessageCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM trash_messages", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
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

data class TrashGroup(
    val batchId: String, val address: String, val count: Int, val deletedAt: Long, val latestDate: Long
)

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
