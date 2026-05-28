package com.jossephus.chuchu.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey

@Database(
    entities = [HostProfile::class, SshKey::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = AppDatabase.Migration6To7::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, spec = AppDatabase.Migration9To10::class),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostProfileDao(): HostProfileDao
    abstract fun sshKeyDao(): SshKeyDao

    @DeleteColumn(tableName = "host_profiles", columnName = "keyPath")
    class Migration6To7 : AutoMigrationSpec

    @RenameColumn(
        tableName = "host_profiles",
        fromColumnName = "postConnectActionId",
        toColumnName = "postConnectCommand",
    )
    class Migration9To10 : AutoMigrationSpec

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chuchu.db",
                )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
