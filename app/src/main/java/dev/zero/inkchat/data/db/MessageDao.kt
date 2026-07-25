package dev.zero.inkchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Aggregate over a conversation's messages; column aliases match the @Query projection. */
data class TokenUsage(val tokensIn: Int, val tokensOut: Int)

@Dao
interface MessageDao {

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun page(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun listForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun count(conversationId: String): Int

    @Query(
        "SELECT COALESCE(SUM(tokensIn), 0) AS tokensIn, COALESCE(SUM(tokensOut), 0) AS tokensOut " +
            "FROM messages WHERE conversationId = :conversationId"
    )
    fun observeTokenUsage(conversationId: String): Flow<TokenUsage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)
}
