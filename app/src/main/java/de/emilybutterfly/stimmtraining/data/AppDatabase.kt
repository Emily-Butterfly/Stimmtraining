package de.emilybutterfly.stimmtraining.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.emilybutterfly.stimmtraining.data.library.LibraryDao
import de.emilybutterfly.stimmtraining.data.library.LibrarySearch
import de.emilybutterfly.stimmtraining.data.library.LibraryText

@Database(entities = [Recording::class, LibraryText::class, LibrarySearch::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun libraryDao(): LibraryDao

    companion object {
        const val NAME = "stimmtraining.db"

        /** Version 2: Text als Datei (PDF, Word, …) statt als Foto; Tonhöhen-Analyse. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN documentFile TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN documentName TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN pitchMostFrequent INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN pitchLowest INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN pitchHighest INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN pitchVoicedMs INTEGER")
            }
        }

        /** Version 3: Bibliothek mit Lesefortschritt; mehrere Fotoseiten und gelesener Abschnitt pro Aufnahme. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN sectionStartY REAL")
                db.execSQL("ALTER TABLE recordings ADD COLUMN sectionEndY REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_texts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, `title` TEXT NOT NULL, `tags` TEXT NOT NULL, `photoFiles` TEXT, " +
                        "`documentFile` TEXT, `documentName` TEXT, `pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `lastReadAt` INTEGER, `progressOffset` INTEGER, " +
                        "`progressAnchor` TEXT, `progressPage` INTEGER, `progressY` REAL, `progressPageName` TEXT, " +
                        "`progressFraction` REAL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_library_texts_uuid` ON `library_texts` (`uuid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_search` (`textId` INTEGER NOT NULL, `content` TEXT NOT NULL, " +
                        "PRIMARY KEY(`textId`), FOREIGN KEY(`textId`) REFERENCES `library_texts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
