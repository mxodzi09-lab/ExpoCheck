package pl.expocheck

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class RecordStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("expocheck", Context.MODE_PRIVATE)
    private val gson = Gson()

    var nickname: String
        get() = prefs.getString("nickname", "").orEmpty()
        set(value) = prefs.edit().putString("nickname", value.trim()).apply()

    fun loadRecords(): List<ProductRecord> {
        val json = prefs.getString("records", "[]").orEmpty()
        return runCatching {
            val type = object : TypeToken<List<ProductRecord>>() {}.type
            gson.fromJson<List<ProductRecord>>(json, type).orEmpty().map { record ->
                val oldPrices = runCatching { record.label.prices }.getOrNull().orEmpty()
                if (oldPrices.isEmpty() && record.label.price != null) {
                    record.copy(
                        label = record.label.copy(
                            prices = listOf(DetectedPrice(record.label.price, record.label.unit))
                        )
                    )
                } else {
                    record
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveRecord(record: ProductRecord) {
        val records = loadRecords().toMutableList()
        records.removeAll { it.id == record.id }
        records.add(0, record)
        prefs.edit().putString("records", gson.toJson(records.take(500))).apply()
    }

    fun deleteRecord(id: String) {
        val records = loadRecords().filterNot { it.id == id }
        prefs.edit().putString("records", gson.toJson(records)).apply()
    }

    fun copyPhoto(uri: Uri): String {
        val folder = File(context.filesDir, "exposure_photos").apply { mkdirs() }
        val file = File(folder, "exp_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Nie udało się otworzyć zdjęcia." }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }
}
