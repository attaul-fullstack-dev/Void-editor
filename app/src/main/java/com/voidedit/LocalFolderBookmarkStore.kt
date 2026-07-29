package com.voidedit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bookmark folder lokal hasil ACTION_OPEN_DOCUMENT_TREE (Fitur E).
 * Tidak menyimpan kredensial, jadi cukup SharedPreferences biasa.
 */
class LocalFolderBookmarkStore(context: Context) {

    data class Bookmark(val id: String, val label: String, val treeUri: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("treeUri", treeUri)
    }

    private val prefs = context.getSharedPreferences("void_local_folders", Context.MODE_PRIVATE)

    fun list(): List<Bookmark> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = json.optString("treeUri")
                if (uri.isBlank()) return@mapNotNull null
                Bookmark(
                    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                    label = json.optString("label").ifBlank { uri.substringAfterLast('/') },
                    treeUri = uri
                )
            }
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    fun add(label: String, treeUri: String): String {
        require(treeUri.isNotBlank()) { "Folder belum dipilih" }
        val id = UUID.randomUUID().toString()
        val entry = Bookmark(id, label.trim().take(60).ifEmpty { "Folder" }, treeUri)
        persist(list().filterNot { it.treeUri == treeUri } + entry)
        return id
    }

    fun rename(id: String, label: String) {
        require(label.isNotBlank()) { "Nama tidak boleh kosong" }
        persist(list().map { if (it.id == id) it.copy(label = label.trim().take(60)) else it })
    }

    fun delete(id: String) = persist(list().filterNot { it.id == id })

    fun get(id: String): Bookmark? = list().firstOrNull { it.id == id }

    private fun persist(entries: List<Bookmark>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "saved_local_folders"
    }
}
