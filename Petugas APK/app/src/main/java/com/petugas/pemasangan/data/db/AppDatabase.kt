package com.petugas.pemasangan.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [InstallationEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun installationDao(): InstallationDao

    companion object {
        /** v1 -> v2: tambah kolom status pemasangan (v3.0). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE installations ADD COLUMN status TEXT NOT NULL DEFAULT 'SELESAI'"
                )
            }
        }

        /** v2 -> v3 (v1.16-m17): catatan pusat + status kirim ke pusat. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE installations ADD COLUMN catatanPusat TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE installations ADD COLUMN catatanPusatAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE installations ADD COLUMN kirimPusatAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "petugas_pemasangan.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
