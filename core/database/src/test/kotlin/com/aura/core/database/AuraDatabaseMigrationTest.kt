package com.aura.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A user upgrading from v0.1 must keep every conversation, memory and goal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuraDatabaseMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private var db: AuraDatabase? = null

    private fun open(): AuraDatabase =
        Room.databaseBuilder(ctx, AuraDatabase::class.java, name)
            .addMigrations(AuraDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also { db = it; it.openHelper.writableDatabase } // forces the migration + Room's schema validation

    @After fun tearDown() { db?.close(); ctx.deleteDatabase(name) }

    @Test fun `v0_1 history is kept in a legacy conversation`() = runBlocking {
        LegacyV1.create(ctx, name)
        val d = open()

        val legacy = d.conversationDao().get("legacy")
        assertNotNull("legacy conversation created", legacy)
        assertEquals(1000L, legacy!!.createdAtMs)
        assertEquals(3000L, legacy.updatedAtMs)

        val messages = d.messageDao().getRecentIn("legacy", 10)
        assertEquals(listOf("m3", "m2", "m1"), messages.map { it.id })
        assertTrue(messages.all { it.conversationId == "legacy" && it.promptTokens == null && it.model == null })
        assertEquals(3, d.messageDao().getRecent(100).size)
    }

    @Test fun `memories keep their content and get sane defaults`() = runBlocking {
        LegacyV1.create(ctx, name)
        val memories = open().memoryDao().listAll().associateBy { it.id }

        val semantic = memories.getValue("s1")
        assertEquals("J'aime le café", semantic.content)
        assertEquals("USER", semantic.source)
        assertEquals("goal1", semantic.linkedGoalId)
        assertFalse(semantic.needsReview)
        assertEquals("stub", semantic.embeddingKind)
        assertEquals(10L, semantic.updatedAtMs)

        // Episodic exchanges were auto-captured in v0.1: they are now labelled as automatic.
        assertEquals("AUTO", memories.getValue("e1").source)
    }

    @Test fun `other tables are untouched`() = runBlocking {
        LegacyV1.create(ctx, name)
        val d = open()
        assertEquals("Courir", d.goalDao().getById("g1")!!.title)
        assertEquals("Paris", d.knowledgeDao().getNode("n1")!!.label)
    }

    @Test fun `an empty v0_1 database migrates without inventing a conversation`() = runBlocking {
        LegacyV1.create(ctx, name, withHistory = false)
        val d = open()
        assertEquals(0, d.conversationDao().count())
        assertEquals("Courir", d.goalDao().getById("g1")!!.title)
    }

    @Test fun `the migrated database accepts v0_2 writes`() = runBlocking {
        LegacyV1.create(ctx, name)
        val d = open()
        d.messageDao().insert(
            com.aura.core.database.entity.MessageEntity(
                id = "new", role = "ASSISTANT", content = "ok", timestampMs = 4000, conversationId = "legacy",
                providerId = "zai", model = "glm-4.7-flash", promptTokens = 10, completionTokens = 5,
            ),
        )
        assertEquals(10L, d.messageDao().usageIn("legacy").prompt)
    }
}
