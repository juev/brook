package org.evsyukov.brook.data.social

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom

/** No entry foreign key: history must outlive read-entry retention and prevent a second post. */
@Entity(tableName = "social_deliveries", indices = [Index(value = ["entryId", "destinationKey"], unique = true)])
data class Delivery(
    @PrimaryKey val id: String = DeliveryIds.next(),
    val entryId: Long,
    val destination: String,
    /** Stable service/server/user identity, without the connection's revocable generation. */
    val destinationKey: String = destination.substringBeforeLast('#', destination),
    val title: String,
    val url: String,
    val createdAt: Long,
    val state: String = PENDING,
    val firstAttemptAt: Long = 0,
    val remoteId: String = "",
) {
    companion object {
        const val PENDING = "pending"
        const val SENT = "sent"
        const val AUTH = "auth"
        const val INVALID = "invalid"
        const val UNKNOWN = "unknown"
        const val CANCELED = "canceled"
    }
}

/** AT Protocol TID: microsecond clock plus a random 10-bit clock identifier. */
private object DeliveryIds {
    private const val ALPHABET = "234567abcdefghijklmnopqrstuvwxyz"
    private val clock = SecureRandom().nextInt(1024).toLong()
    private var micros = 0L
    @Synchronized fun next(): String {
        micros = maxOf(micros + 1, System.currentTimeMillis() * 1000)
        var value = (micros shl 10) or clock
        val chars = CharArray(13)
        for (i in 12 downTo 0) {
            chars[i] = ALPHABET[(value and 31).toInt()]
            value = value ushr 5
        }
        return chars.concatToString()
    }
}

@Dao
interface DeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delivery: Delivery)

    @Query("SELECT * FROM social_deliveries ORDER BY createdAt, id")
    suspend fun all(): List<Delivery>

    @Query("SELECT * FROM social_deliveries ORDER BY createdAt DESC, id DESC LIMIT 100")
    fun observe(): Flow<List<Delivery>>

    @Update
    suspend fun update(delivery: Delivery)

    @Query("UPDATE social_deliveries SET state = 'canceled' WHERE destination = :destination AND state != 'sent'")
    suspend fun cancel(destination: String)

    @Query("UPDATE social_deliveries SET state = 'pending' WHERE destination = :destination AND state = 'auth'")
    suspend fun retryAuth(destination: String)
}