package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedTestDatabaseMigrationTest {

    @Test
    fun migration3To4_preservesExistingHistoryAndAddsServerColumns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_3_4_test_${System.nanoTime()}.db"

        // 1. Create a real version-3 SQLite database with the exact Room v3 schema and seed a row.
        val v3Helper = object : SQLiteOpenHelper(context, dbName, null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `test_results` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `connectionType` TEXT NOT NULL,
                        `ping` REAL,
                        `jitter` REAL,
                        `download` REAL,
                        `upload` REAL,
                        `downloadLoadedLatency` REAL,
                        `uploadLoadedLatency` REAL,
                        `bufferbloatGrade` TEXT,
                        `networkName` TEXT,
                        `cellularNetworkTag` TEXT,
                        `deviceModel` TEXT,
                        `wifiBand` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_test_results_timestamp` ON `test_results` (`timestamp`)",
                )
                db.execSQL(
                    """
                    INSERT INTO `test_results` (
                        timestamp, connectionType, ping, jitter, download, upload,
                        downloadLoadedLatency, uploadLoadedLatency, bufferbloatGrade,
                        networkName, cellularNetworkTag, deviceModel, wifiBand
                    ) VALUES (
                        1700000001000, 'WIFI', 14.2, 1.5, 210.5, 95.3,
                        22.0, 26.0, 'A', 'HomeFiber', NULL, 'Pixel', '5 GHz'
                    )
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v3Helper.writableDatabase.close()
        v3Helper.close()

        // 2. Open with Room at version 4 using MIGRATION_3_4 (no destructive migration).
        val roomDb = Room.databaseBuilder(context, SpeedTestDatabase::class.java, dbName)
            .addMigrations(
                SpeedTestDatabase.MIGRATION_1_2,
                SpeedTestDatabase.MIGRATION_2_3,
                SpeedTestDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val repo = HistoryRepository(roomDb.testResultDao())

            // Existing v3 row survived intact with null server columns.
            val beforeInsert = repo.allResults.first()
            assertEquals(1, beforeInsert.size)
            val legacy = beforeInsert.first()
            assertEquals(1700000001000L, legacy.timestamp)
            assertEquals(ConnectionType.WIFI, legacy.connectionType)
            assertEquals(210.5, legacy.download!!, 0.01)
            assertEquals("HomeFiber", legacy.networkName)
            assertEquals("5 GHz", legacy.wifiBand)
            assertNull(legacy.serverName)
            assertNull(legacy.serverLocation)

            // Inserting a new v4 row persists serverName and serverLocation.
            repo.saveResult(
                TestResult(
                    timestamp = 1700000002000L,
                    connectionType = ConnectionType.MOBILE,
                    ping = 18.0,
                    jitter = 2.1,
                    download = 140.0,
                    upload = 45.0,
                    serverName = "Cloudflare",
                    serverLocation = "Mumbai",
                ),
            )

            val afterInsert = repo.allResults.first()
            assertEquals(2, afterInsert.size)
            val newest = afterInsert.first()
            assertEquals("Cloudflare", newest.serverName)
            assertEquals("Mumbai", newest.serverLocation)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
