package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TestResultEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class SpeedTestDatabase : RoomDatabase() {
    abstract fun testResultDao(): TestResultDao

    companion object {
        @Volatile
        private var instance: SpeedTestDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE test_results ADD COLUMN networkName TEXT")
                db.execSQL("ALTER TABLE test_results ADD COLUMN cellularNetworkTag TEXT")
                db.execSQL("ALTER TABLE test_results ADD COLUMN deviceModel TEXT")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE test_results ADD COLUMN wifiBand TEXT")
            }
        }

        fun getInstance(context: Context): SpeedTestDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpeedTestDatabase::class.java,
                    "speedtest_history.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
