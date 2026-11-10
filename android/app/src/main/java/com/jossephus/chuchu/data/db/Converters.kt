package com.jossephus.chuchu.data.db

import androidx.room.TypeConverter
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.MultiplexerType
import com.jossephus.chuchu.model.PortForward
import com.jossephus.chuchu.model.Transport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromTransport(value: Transport): String = value.name

    @TypeConverter
    fun toTransport(value: String): Transport = Transport.valueOf(value)

    @TypeConverter
    fun fromAuthMethod(value: AuthMethod): String = value.name

    @TypeConverter
    fun toAuthMethod(value: String): AuthMethod = AuthMethod.valueOf(value)

    @TypeConverter
    fun fromMultiplexer(value: MultiplexerType?): String? = value?.id

    @TypeConverter
    fun toMultiplexer(value: String?): MultiplexerType? = value?.let { MultiplexerType.fromPersistedValue(it) }

    @TypeConverter
    fun fromPortForwards(value: List<PortForward>): String = json.encodeToString(value)

    @TypeConverter
    fun toPortForwards(value: String?): List<PortForward> =
        value?.let { runCatching { json.decodeFromString<List<PortForward>>(it) }.getOrNull() }.orEmpty()

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
