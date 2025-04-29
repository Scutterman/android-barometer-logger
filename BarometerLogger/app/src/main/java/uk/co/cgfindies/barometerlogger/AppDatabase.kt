package uk.co.cgfindies.barometerlogger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import uk.co.cgfindies.barometerlogger.dao.AtmosphericPressureReadingDao
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureMappingSessionReading
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureReading

@Database(entities = [AtmosphericPressureReading::class, AtmosphericPressureMappingSessionReading::class], version = 2)
abstract class AppDatabase: RoomDatabase() {
    companion object {
        private const val DATABASE_NAME = "uk.co.cgfindies.barometriclogger.db"

        fun getDb(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2).build()
        }
    }

    abstract fun atmosphericPressureReadingDao(): AtmosphericPressureReadingDao
}

// I had to add this to deal with a whole load of Room BS
// It got scared when I added a new table and crashed my entire app and refused to install or run
// because it didn't know how to "migrate" the database - aka add the table to the database
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `AtmosphericPressureMappingSessionReading` (`unixTimestamp` INTEGER NOT NULL, `reading` INTEGER NOT NULL, `latitude` INTEGER NOT NULL, `longitude` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
    }
}
