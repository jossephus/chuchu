package com.example.chuchu.data.db

import androidx.room.TypeConverter
import com.example.chuchu.model.AuthMethod
import com.example.chuchu.model.Transport

class Converters {
    @TypeConverter
    fun fromTransport(value: Transport): String = value.name

    @TypeConverter
    fun toTransport(value: String): Transport = Transport.valueOf(value)

    @TypeConverter
    fun fromAuthMethod(value: AuthMethod): String = value.name

    @TypeConverter
    fun toAuthMethod(value: String): AuthMethod = AuthMethod.valueOf(value)

}
