// app/src/main/java/com/schedulecalendar/app/data/db/Converters.kt
package com.schedulecalendar.app.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Room TypeConverter：复杂对象 <-> JSON String */
class Converters {
    private val gson = Gson()

    @TypeConverter fun fromStringList(value: String): List<String> =
        gson.fromJson(value, object : TypeToken<List<String>>() {}.type) ?: emptyList()

    @TypeConverter fun toStringList(list: List<String>): String = gson.toJson(list)
}
