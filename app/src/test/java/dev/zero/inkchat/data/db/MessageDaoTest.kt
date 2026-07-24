package dev.zero.inkchat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
        runTest {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = "conv",
                    title = "Test",
                    providerId = "openrouter",
                    modelId = "m",
                    createdAt = 0,
                    updatedAt = 0,
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(id: String, createdAt: Long, role: String = "user") = MessageEntity(
        id = id,
        conversationId = "conv",
        role = role,
        content = "content $id",
        modelId = null,
        tokensIn = null,
        tokensOut = null,
        createdAt = createdAt,
    )

    @Test
    fun `page returns messages in chronological order with limit and offset`() = runTest {
        (1..5).forEach { dao.upsert(message("m$it", createdAt = it.toLong())) }

        assertEquals(listOf("m1", "m2"), dao.page("conv", limit = 2, offset = 0).map { it.id })
        assertEquals(listOf("m3", "m4"), dao.page("conv", limit = 2, offset = 2).map { it.id })
        assertEquals(listOf("m5"), dao.page("conv", limit = 2, offset = 4).map { it.id })
        assertEquals(5, dao.count("conv"))
    }

    @Test
    fun `upsert with the same id replaces the message`() = runTest {
        dao.upsert(message("m1", createdAt = 1))
        dao.upsert(message("m1", createdAt = 1).copy(content = "edited"))

        val all = dao.observeForConversation("conv").first()
        assertEquals(1, all.size)
        assertEquals("edited", all.single().content)
    }

    @Test
    fun `messages with the same createdAt tie-break by id`() = runTest {
        dao.upsert(message("b", createdAt = 10))
        dao.upsert(message("a", createdAt = 10))

        val all = dao.observeForConversation("conv").first()
        assertEquals(listOf("a", "b"), all.map { it.id })
    }
}
