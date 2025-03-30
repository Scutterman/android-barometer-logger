package uk.co.cgfindies.barometerlogger.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureMappingSessionReading
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureReading
import uk.co.cgfindies.barometerlogger.entities.AtmosphericPressureReadingDailySummary
import uk.co.cgfindies.barometerlogger.entities.MappedReadingSessionSummary

@Dao
interface AtmosphericPressureReadingDao {
    @Query("SELECT * FROM AtmosphericPressureReading where unixTimestamp >= :from AND unixTimestamp <= :to ORDER BY unixTimestamp asc")
    suspend fun getAll(from: Long, to: Long): List<AtmosphericPressureReading>

    @Query("SELECT * FROM AtmosphericPressureReading ORDER BY unixTimestamp desc LIMIT 1")
    suspend fun getLatestReading(): AtmosphericPressureReading?

    @Query("SELECT * FROM AtmosphericPressureReading WHERE unixTimestamp >= cast(strftime('%s', datetime('now', '-1 day')) as int)")
    suspend fun getAllReadingsInPast24Hours(): List<AtmosphericPressureReading>

    @Query("SELECT date(unixTimestamp, 'unixepoch') as summaryDate, min(reading) as minReading, max(reading) as maxReading, max(deltaIncrease) as maxDeltaIncrease, max(deltaDecrease) as maxDeltaDecrease FROM AtmosphericPressureReading group by summaryDate order by summaryDate desc")
    suspend fun getAllGroupedByDay(): List<AtmosphericPressureReadingDailySummary>

    @Query("SELECT * FROM AtmosphericPressureMappingSessionReading WHERE sessionId = :sessionId order by id asc")
    suspend fun getAllMapped(sessionId: String): List<AtmosphericPressureMappingSessionReading>

    @Query("SELECT MIN(unixTimestamp) AS startUnixTimestamp, MAX(unixTimestamp) AS endUnixTimestamp, COUNT(*) AS numberOfReadings, sessionId FROM AtmosphericPressureMappingSessionReading group by sessionId order by startUnixTimestamp ASC")
    suspend fun getAllMappedSummary(): List<MappedReadingSessionSummary>

    @Query("DELETE FROM AtmosphericPressureMappingSessionReading WHERE sessionId = :sessionId")
    suspend fun deleteMappedReadingsBySessionId(sessionId: String)

    @Insert
    suspend fun insertAll(vararg readings: AtmosphericPressureReading)

    @Insert
    suspend fun insertAll(vararg readings: AtmosphericPressureMappingSessionReading)
}