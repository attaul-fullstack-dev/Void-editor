package com.voidedit

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Penyimpanan konfigurasi SFTP tersimpan (Fitur A).
 *
 * Password disimpan di EncryptedSharedPreferences (AES-256, kunci di Android Keystore).
 * Bila keystore/enkripsi tidak dapat dibuka (ROM aneh, keystore korup), store menjadi
 * [available] = false dan aplikasi TIDAK crash — pemanggil cukup menampilkan pesan agar
 * user connect manual seperti sebelumnya.
 */
class SftpConnectionStore(context: Context) {

    data class Saved(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        val authType: String,          // "password" | "key"
        val password: String?,
        val privateKeyPath: String?,
        val passphrase: String?
    ) {
        /** JSON untuk UI — TANPA kredensial. */
        fun toPublicJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("host", host)
            .put("port", port)
            .put("username", username)
            .put("authType", authType)

        /** JSON lengkap — hanya dipakai saat user membuka dialog Edit. */
        fun toDetailJson(): JSONObject = toPublicJson()
            .put("password", password ?: "")
            .put("privateKeyPath", privateKeyPath ?: "")
            .put("passphrase", passphrase ?: "")

        fun toStorageJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("host", host)
            .put("port", port)
            .put("username", username)
            .put("authType", authType)
            .put("password", password ?: "")
            .put("privateKeyPath", privateKeyPath ?: "")
            .put("passphrase", passphrase ?: "")
    }

    private val prefs: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "void_sftp_connections",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrNull()

    /** false = enkripsi tidak tersedia; UI harus meminta user connect manual. */
    val available: Boolean get() = prefs != null

    fun list(): List<Saved> {
        val store = prefs ?: return emptyList()
        val raw = runCatching { store.getString(KEY, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> parse(array.optJSONObject(index)) }
        }.getOrElse {
            // Data rusak/tidak bisa didekripsi: bersihkan agar tidak berulang, jangan crash.
            runCatching { store.edit().remove(KEY).apply() }
            emptyList()
        }
    }

    fun get(id: String): Saved? = list().firstOrNull { it.id == id }

    fun save(label: String, config: SftpManager.Config, authType: String): String {
        requireStore()
        val id = UUID.randomUUID().toString()
        val entry = Saved(
            id = id,
            label = cleanLabel(label, config),
            host = config.host,
            port = config.port,
            username = config.username,
            authType = authType,
            password = config.password,
            privateKeyPath = config.privateKeyPath,
            passphrase = config.passphrase
        )
        // Host+user+port yang sama diperlakukan sebagai koneksi yang sama (hindari duplikat).
        val next = list().filterNot {
            it.host == entry.host && it.port == entry.port && it.username == entry.username
        } + entry
        persist(next)
        return id
    }

    fun update(id: String, label: String, config: SftpManager.Config, authType: String) {
        requireStore()
        val current = list()
        require(current.any { it.id == id }) { "Koneksi tersimpan tidak ditemukan" }
        persist(current.map { saved ->
            if (saved.id != id) saved
            else saved.copy(
                label = cleanLabel(label, config),
                host = config.host,
                port = config.port,
                username = config.username,
                authType = authType,
                password = config.password,
                privateKeyPath = config.privateKeyPath,
                passphrase = config.passphrase
            )
        })
    }

    fun rename(id: String, label: String) {
        requireStore()
        require(label.isNotBlank()) { "Nama tidak boleh kosong" }
        val current = list()
        require(current.any { it.id == id }) { "Koneksi tersimpan tidak ditemukan" }
        persist(current.map { if (it.id == id) it.copy(label = label.trim().take(60)) else it })
    }

    fun delete(id: String) {
        requireStore()
        persist(list().filterNot { it.id == id })
    }

    private fun persist(entries: List<Saved>) {
        val store = requireStore()
        val array = JSONArray()
        entries.forEach { array.put(it.toStorageJson()) }
        store.edit().putString(KEY, array.toString()).apply()
    }

    private fun requireStore(): SharedPreferences =
        prefs ?: error("Penyimpanan terenkripsi tidak tersedia di perangkat ini")

    private fun parse(json: JSONObject?): Saved? {
        if (json == null) return null
        val host = json.optString("host")
        val username = json.optString("username")
        if (host.isBlank() || username.isBlank()) return null
        return Saved(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            label = json.optString("label").ifBlank { "$username@$host" },
            host = host,
            port = json.optInt("port", 22),
            username = username,
            authType = json.optString("authType").ifBlank { "password" },
            password = json.optString("password").ifEmpty { null },
            privateKeyPath = json.optString("privateKeyPath").ifEmpty { null },
            passphrase = json.optString("passphrase").ifEmpty { null }
        )
    }

    private fun cleanLabel(label: String, config: SftpManager.Config): String =
        label.trim().take(60).ifEmpty { "${config.username}@${config.host}" }

    private companion object {
        const val KEY = "saved_sftp_connections"
    }
}
