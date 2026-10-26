package com.autoattendance.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class AttemptResult(val label: String) {
    PENDING("Pending"),
    SUCCESS("Success"),
    FAILED("Failed"),
    SKIPPED("Skipped"),
}

enum class Trigger(val label: String) {
    SCHEDULED("Scheduled"),
    DRY_RUN("Dry run (not submitted)"),
    TEST_EXECUTE("Test execution"),
}

/**
 * One automation attempt, as shown on the Automation Logs screen. Holds no credentials, OTPs or
 * tokens – only what the automation itself chose and observed.
 */
@Entity(tableName = "attempts")
data class AttemptLog(
    @PrimaryKey val id: String,
    val operation: Operation,
    val trigger: Trigger,
    /** Planned alarm time; null for manual test runs. */
    val scheduledAt: Long?,
    val startedAt: Long,
    /** When the final Sign In / Sign Out button was pressed; null if it never was. */
    val executedAt: Long? = null,
    val finishedAt: Long? = null,
    val location: String?,
    val previousStatus: AttendanceStatus = AttendanceStatus.UNKNOWN,
    val finalStatus: AttendanceStatus = AttendanceStatus.UNKNOWN,
    val result: AttemptResult = AttemptResult.PENDING,
    val message: String? = null,
)

@Dao
interface AttemptDao {
    @Query("SELECT * FROM attempts ORDER BY startedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<AttemptLog>>

    @Upsert
    suspend fun upsert(attempt: AttemptLog)

    /** Keeps only the newest [keep] attempts so the table cannot grow without limit. */
    @Query("DELETE FROM attempts WHERE id NOT IN (SELECT id FROM attempts ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM attempts")
    suspend fun clear()
}

@Database(entities = [AttemptLog::class], version = 1, exportSchema = false)
abstract class AttemptDatabase : RoomDatabase() {
    abstract fun attempts(): AttemptDao

    companion object {
        fun create(context: Context): AttemptDatabase =
            Room.databaseBuilder(context, AttemptDatabase::class.java, "automation_logs.db").build()
    }
}
