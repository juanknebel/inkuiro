package dev.zero.inkchat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.conversationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun conversation(id: String, updatedAt: Long) = ConversationEntity(
        id = id,
        title = "Title $id",
        providerId = "openrouter",
        modelId = "anthropic/claude-sonnet-4-6",
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `observeAll orders by updatedAt descending`() = runTest {
        dao.upsert(conversation("a", updatedAt = 100))
        dao.upsert(conversation("b", updatedAt = 300))
        dao.upsert(conversation("c", updatedAt = 200))

        val all = dao.observeAll().first()

        assertEquals(listOf("b", "c", "a"), all.map { it.id })
    }

    @Test
    fun `page respects limit and offset`() = runTest {
        (1..5).forEach { dao.upsert(conversation("c$it", updatedAt = it.toLong())) }

        val page = dao.page(limit = 2, offset = 2)

        // Descending order: c5, c4 | c3, c2 | c1
        assertEquals(listOf("c3", "c2"), page.map { it.id })
        assertEquals(5, dao.count())
    }

    @Test
    fun `rename updates title and updatedAt`() = runTest {
        dao.upsert(conversation("a", updatedAt = 100))

        dao.rename("a", "New title", updatedAt = 999)

        val updated = dao.getById("a")!!
        assertEquals("New title", updated.title)
        assertEquals(999, updated.updatedAt)
    }

    @Test
    fun `delete removes the conversation and cascades to its messages`() = runTest {
        dao.upsert(conversation("a", updatedAt = 100))
        val messageDao = db.messageDao()
        messageDao.upsert(
            MessageEntity(
                id = "m1",
                conversationId = "a",
                role = "user",
                content = "hi",
                modelId = null,
                tokensIn = null,
                tokensOut = null,
                createdAt = 100,
            )
        )

        dao.delete("a")

        assertNull(dao.getById("a"))
        assertEquals(0, messageDao.count("a"))
    }
}
