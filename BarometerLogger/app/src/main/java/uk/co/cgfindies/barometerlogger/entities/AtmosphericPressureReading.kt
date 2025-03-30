package uk.co.cgfindies.barometerlogger.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class AtmosphericPressureReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    val unixTimestamp: Long,
    val reading: Int,
    val deltaIncrease: Int,
    val deltaDecrease: Int
)

@Entity
data class AtmosphericPressureMappingSessionReading (
    val unixTimestamp: Long,
    val reading: Int,
    val latitude: Long,
    val longitude: Long,
    val sessionId: String,

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0
)

data class AtmosphericPressureReadingDailySummary (
    val summaryDate: String,
    val minReading: Int,
    val maxReading: Int,
    val maxDeltaIncrease: Int,
    val maxDeltaDecrease: Int
)

data class MappedReadingSessionSummary (
    val startUnixTimestamp: Long,
    val endUnixTimestamp: Long,
    val numberOfReadings: Int,
    val sessionId: String
)