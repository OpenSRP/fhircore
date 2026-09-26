package org.smartregister.fhircore.quest.fct

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getBlobOrNull
import androidx.core.database.getFloatOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import com.google.android.fhir.FhirEngine
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.smartregister.fhircore.engine.util.extension.decodeJson

class DatabaseBridge(
    private val context: Context,
    private val fhirEngine: FhirEngine,
) {

    fun execute(arg: String): String {
        val queryRequest = arg.decodeJson<QueryRequest>()

        val dbHelper = DBHelper(context, queryRequest.database, getDatabaseVersion())
        val db = dbHelper.writableDatabase

        val query = queryRequest.query
        val result = when (getQueryType(query)) {
            QueryType.SELECT, QueryType.UNKNOWN -> {

                var cursor: Cursor? = null
                val jsonObject = JSONObject()
                try {
                    cursor = db.rawQuery(query, null)
                    extractAllRecords(cursor, queryRequest, jsonObject)
                } catch (ex: Exception) {
                    jsonObject.put("success", false)
                    jsonObject.put("error", ex.localizedMessage ?: ex.message ?: ex.toString())
                } finally {
                    cursor?.close()
                }
                jsonObject.put("method", "rawQuery")
                jsonObject.toString()
            }

            else -> {
                val jsonObject = JSONObject()
                try {
                    db.execSQL(query)
                    jsonObject.put("success", true)
                } catch (ex: Exception) {
                    jsonObject.put("success", false)
                    jsonObject.put("error", ex.localizedMessage ?: ex.message ?: ex.toString())
                }
                jsonObject.put("method", "execSQL")
                jsonObject.toString()
            }
        }

        return result
    }

    fun runQuery(query: String) : Cursor {
        val dbHelper = DBHelper(context, "resources.db", getDatabaseVersion())
        val db = dbHelper.writableDatabase
        return db.rawQuery(query, null)
    }

    private fun getQueryType(query: String): QueryType {
        return when {
            isFound(query, "^SELECT\\s+") -> QueryType.SELECT
            isFound(query, "^INSERT\\s+") -> QueryType.INSERT
            isFound(query, "^UPDATE\\s+") -> QueryType.UPDATE
            isFound(query, "^DELETE\\s+") -> QueryType.DELETE
            else -> QueryType.UNKNOWN
        }
    }

    private fun isFound(query: String, pattern: String): Boolean {
        return pattern.toRegex(RegexOption.IGNORE_CASE).find(query.trim()) != null
    }

    private fun extractAllRecords(
        cursor: Cursor,
        queryRequest: QueryRequest,
        jsonObject: JSONObject
    ) {
        val jsonArray = JSONArray()

        jsonObject.put("count", cursor.count)

        if (cursor.moveToPosition(queryRequest.offset)) {
            do {
                val obj = JSONObject()
                cursor.columnNames.forEachIndexed { columnIndex, columName ->
                    when (cursor.getType(columnIndex)) {
                        Cursor.FIELD_TYPE_BLOB -> {
                            obj.put(
                                columName,
                                cursor.getBlobOrNull(columnIndex)?.toByteString()?.hex()
                            )
                        }

                        Cursor.FIELD_TYPE_INTEGER -> {
                            obj.put(columName, cursor.getIntOrNull(columnIndex))
                        }

                        Cursor.FIELD_TYPE_FLOAT -> {
                            obj.put(columName, cursor.getFloatOrNull(columnIndex))
                        }

                        else -> {
                            obj.put(columName, cursor.getStringOrNull(columnIndex))
                        }
                    }
                }
                jsonArray.put(obj)
            } while (cursor.position < ((queryRequest.offset - 1) + queryRequest.limit) && cursor.moveToNext())
        }

        val columnsArray = JSONArray()
        cursor.columnNames.forEach(columnsArray::put)

        jsonObject.put("success", true)
        jsonObject.put("data", jsonArray)
        jsonObject.put("columnNames", columnsArray)
    }


    private fun getDatabaseVersion(): Int {
        val fhirDatabase =
            fhirEngine.javaClass.getDeclaredField("database").apply { isAccessible = true }
                .get(fhirEngine)
        val resourceDatabaseImpl =
            fhirDatabase.javaClass.getDeclaredField("db").apply { isAccessible = true }
                .get(fhirDatabase)
        val sqliteOpenHelper =
            resourceDatabaseImpl.javaClass.getMethod("getOpenHelper").invoke(resourceDatabaseImpl)
        val supportSqliteDatabase =
            sqliteOpenHelper.javaClass.getMethod("getReadableDatabase").invoke(sqliteOpenHelper)
        val dbVersion = supportSqliteDatabase.javaClass.getDeclaredMethod("getVersion")
            .invoke(supportSqliteDatabase)
        return dbVersion as Int
    }

    @Serializable
    private data class QueryRequest(
        val database: String,
        val query: String,
        val sortColumn: String? = null,
        val offset: Int,
        val limit: Int,
    )

    private enum class QueryType {
        SELECT, INSERT, UPDATE, DELETE, UNKNOWN
    }

    private class DBHelper(context: Context?, database: String, version: Int) :
        SQLiteOpenHelper(context, database, null, version) {
        override fun onCreate(db: SQLiteDatabase?) {}

        override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    }
}