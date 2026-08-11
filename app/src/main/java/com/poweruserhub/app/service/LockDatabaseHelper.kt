package com.poweruserhub.app.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.poweruserhub.app.model.SettingLock

class LockDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "setting_locks.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "locks"
        private const val COLUMN_ID = "id"
        private const val COLUMN_KEY = "setting_key"
        private const val COLUMN_NAMESPACE = "namespace"
        private const val COLUMN_DESIRED_VALUE = "desired_value"
        private const val COLUMN_IS_ENABLED = "is_enabled"
        private const val COLUMN_LAST_VERIFIED = "last_verified"
        private const val COLUMN_STATUS = "status"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_KEY TEXT UNIQUE,
                $COLUMN_NAMESPACE TEXT,
                $COLUMN_DESIRED_VALUE TEXT,
                $COLUMN_IS_ENABLED INTEGER,
                $COLUMN_LAST_VERIFIED TEXT,
                $COLUMN_STATUS TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun getAllLocks(): List<SettingLock> {
        val list = mutableListOf<SettingLock>()
        val db = readableDatabase
        db.query(TABLE_NAME, null, null, null, null, null, null).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val keyIdx = cursor.getColumnIndexOrThrow(COLUMN_KEY)
                val nsIdx = cursor.getColumnIndexOrThrow(COLUMN_NAMESPACE)
                val valIdx = cursor.getColumnIndexOrThrow(COLUMN_DESIRED_VALUE)
                val enabledIdx = cursor.getColumnIndexOrThrow(COLUMN_IS_ENABLED)
                val verifiedIdx = cursor.getColumnIndexOrThrow(COLUMN_LAST_VERIFIED)
                val statusIdx = cursor.getColumnIndexOrThrow(COLUMN_STATUS)
                
                do {
                    val id = cursor.getInt(idIdx)
                    val key = cursor.getString(keyIdx)
                    val namespace = cursor.getString(nsIdx)
                    val desiredValue = cursor.getString(valIdx)
                    val isEnabled = cursor.getInt(enabledIdx) == 1
                    val lastVerified = cursor.getString(verifiedIdx)
                    val status = cursor.getString(statusIdx)
                    
                    list.add(SettingLock(id, key, namespace, desiredValue, isEnabled, lastVerified, status))
                } while (cursor.moveToNext())
            }
        }
        return list
    }

    fun insertOrUpdateLock(lock: SettingLock): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_KEY, lock.key)
            put(COLUMN_NAMESPACE, lock.namespace)
            put(COLUMN_DESIRED_VALUE, lock.desiredValue)
            put(COLUMN_IS_ENABLED, if (lock.isEnabled) 1 else 0)
            put(COLUMN_LAST_VERIFIED, lock.lastVerified)
            put(COLUMN_STATUS, lock.status)
        }
        
        val rows = db.update(TABLE_NAME, values, "$COLUMN_KEY = ?", arrayOf(lock.key))
        return if (rows == 0) {
            db.insert(TABLE_NAME, null, values) != -1L
        } else {
            true
        }
    }

    fun updateLockStatus(key: String, status: String, timestamp: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_STATUS, status)
            put(COLUMN_LAST_VERIFIED, timestamp)
        }
        val rows = db.update(TABLE_NAME, values, "$COLUMN_KEY = ?", arrayOf(key))
        return rows > 0
    }

    fun deleteLock(key: String): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_NAME, "$COLUMN_KEY = ?", arrayOf(key))
        return rows > 0
    }
}
