package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GHistoryList
import com.baestheorem.inbox.gmail.GHistoryRecord
import com.baestheorem.inbox.gmail.GMessageList
import com.baestheorem.inbox.gmail.GMessageRef
import com.baestheorem.inbox.gmail.GProfile
import com.baestheorem.inbox.gmail.GmailException
import com.baestheorem.inbox.gmail.decodeBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OfflineSyncTest : MailDatabaseTestBase() {
    @Test fun resumesFailedAttachmentsAndPaginationAfterProcessRestart() = runBlocking {
        val gateway = FakeSyncGateway()
        gateway.messages.putAll(listOf(
            email("first"), email("second", parts = listOf(
                attachment("body", "", "text/plain"), attachment("pdf"),
            )), email("third"),
        ).associateBy { it.id })
        gateway.pages[null] = GMessageList(listOf(GMessageRef("first"), GMessageRef("second")), "page-2")
        gateway.pages["page-2"] = GMessageList(listOf(GMessageRef("third")))
        gateway.attachmentBytes["second" to "body"] = "Large text body".toByteArray()
        gateway.attachmentBytes["second" to "pdf"] = byteArrayOf(10, 20)
        gateway.beforeAttachment = { _, aid -> if (aid == "pdf") throw IOException("Connection lost") }

        assertThrows(IOException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertEquals(2 to 1, db.counts(ACCOUNT))
        assertEquals(listOf("second"), db.syncState(ACCOUNT).checkpoint!!.remaining)
        assertNull(db.syncState(ACCOUNT).historyId)

        reopenDatabase()
        gateway.beforeAttachment = { _, _ -> }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))

        assertEquals(3 to 3, db.counts(ACCOUNT))
        assertEquals(listOf(null, "page-2"), gateway.listRequests.map { it.second })
        assertEquals(1, gateway.fullRequests.count { it == "first" })
        assertEquals(1, gateway.fullRequests.count { it == "second" })
        assertEquals(1, gateway.attachmentRequests.count { it.second == "body" })
        assertEquals("Large text body", decodeBody(db.message(ACCOUNT, "second")!!.payload).text)
        assertArrayEquals(byteArrayOf(10, 20), db.attachment(ACCOUNT, "second", "pdf"))
        assertNull(db.syncState(ACCOUNT).checkpoint)
        assertEquals("200", db.syncState(ACCOUNT).historyId)
    }

    @Test fun historyCursorAdvancesOnlyAfterEveryPageHasSucceeded() = runBlocking {
        val gateway = FakeSyncGateway()
        db.putMessage(ACCOUNT, email("deleted"))
        db.putMessage(ACCOUNT, email("spam"))
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100", lastFullSync = TEST_NOW))
        gateway.messages["new"] = email("new")
        gateway.messages["spam"] = email("spam", labels = listOf("SPAM"))
        gateway.messages["later"] = email("later")
        gateway.historyPages[null] = GHistoryList(
            listOf(GHistoryRecord(messages = listOf(GMessageRef("new"), GMessageRef("deleted"), GMessageRef("spam")))),
            nextPageToken = "next", historyId = "300",
        )
        gateway.historyPages["next"] = GHistoryList(
            listOf(GHistoryRecord(messages = listOf(GMessageRef("later")))), historyId = "300",
        )
        gateway.beforeHistory = { if (it == "next") throw IOException("Offline") }

        assertThrows(IOException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertEquals("100", db.syncState(ACCOUNT).historyId)
        assertEquals(listOf("new"), db.messages(ACCOUNT, 0).map { it.id })

        gateway.beforeHistory = {}
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals("300", db.syncState(ACCOUNT).historyId)
        assertEquals(setOf("new", "later"), db.messages(ACCOUNT, 0).map { it.id }.toSet())
        assertTrue(gateway.historyRequests.all { it.first == "100" })
        assertEquals(1, gateway.fullRequests.count { it == "new" })
    }

    @Test fun expiredHistoryRebuildKeepsExistingMailUntilInventoryCompletes() = runBlocking {
        val gateway = FakeSyncGateway()
        db.putMessage(ACCOUNT, email("old-cache"))
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "expired", lastFullSync = TEST_NOW))
        gateway.messages["current"] = email("current", parts = listOf(attachment("pdf")))
        gateway.pages[null] = GMessageList(listOf(GMessageRef("current")))
        gateway.attachmentBytes["current" to "pdf"] = byteArrayOf(42)
        gateway.beforeHistory = { throw GmailException(404, "History expired") }
        gateway.beforeAttachment = { _, _ -> throw IOException("Download interrupted") }

        assertThrows(IOException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertNotNull(db.message(ACCOUNT, "old-cache"))
        assertEquals("100", db.syncState(ACCOUNT).checkpoint!!.startHistoryId)

        gateway.beforeHistory = {}
        gateway.beforeAttachment = { _, _ -> }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertNull(db.message(ACCOUNT, "old-cache"))
        assertEquals(listOf("current"), db.messages(ACCOUNT, 0).map { it.id })
    }

    @Test fun expiredPageTokenRestartsInventoryWithoutDroppingBodyFilesEarly() = runBlocking {
        val gateway = FakeSyncGateway()
        db.putMessage(ACCOUNT, email("stale"), "previous")
        db.saveSyncState(ACCOUNT, OfflineSyncState(checkpoint = FullSyncCheckpoint(
            cutoff = OfflinePolicy.cutoff(TEST_NOW), startHistoryId = "80", nextPage = "expired-page",
        )))
        gateway.beforeList = {
            assertNotNull(db.message(ACCOUNT, "stale"))
            if (it == "expired-page") throw GmailException(400, "Page token expired")
        }
        gateway.messages["fresh"] = email("fresh")
        gateway.pages[null] = GMessageList(listOf(GMessageRef("fresh")))

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(listOf("expired-page", null), gateway.listRequests.map { it.second })
        assertEquals(listOf("fresh"), db.messages(ACCOUNT, 0).map { it.id })
        assertNull(db.syncState(ACCOUNT).checkpoint)
    }

    @Test fun timeBudgetCheckpointsTheNextMessageForLaterWork() = runBlocking {
        var clock = TEST_NOW
        val gateway = FakeSyncGateway()
        gateway.messages.putAll(listOf(email("one"), email("two")).associateBy { it.id })
        gateway.pages[null] = GMessageList(listOf(GMessageRef("one"), GMessageRef("two")))
        val sync = OfflineSync(db, gateway, now = { clock }, changed = { clock += 1_000 })

        assertFalse(sync.run(ACCOUNT, TEST_NOW + 1_000))
        assertEquals(listOf("two"), db.syncState(ACCOUNT).checkpoint!!.remaining)
        assertEquals(listOf("one"), gateway.fullRequests)
        assertTrue(sync.run(ACCOUNT))
        assertEquals(2 to 2, db.counts(ACCOUNT))
        assertEquals(1, gateway.listRequests.size)
    }

    @Test fun ignoresOldAndExcludedMessagesEvenWhenSearchReturnsThem() = runBlocking {
        val gateway = FakeSyncGateway()
        val cutoff = OfflinePolicy.cutoff(TEST_NOW)
        gateway.messages.putAll(listOf(
            email("boundary", cutoff), email("older", cutoff - 1),
            email("trash", labels = listOf("TRASH")), email("draft", labels = listOf("DRAFT")),
        ).associateBy { it.id })
        gateway.pages[null] = GMessageList(gateway.messages.keys.map(::GMessageRef))

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(listOf("boundary"), db.messages(ACCOUNT, 0).map { it.id })
        assertTrue(gateway.listRequests.single().first.contains("after:${cutoff / 1000 - 1}"))
    }

    @Test fun validatesAccountBeforeReadingOrRemovingItsCache() = runBlocking {
        val gateway = FakeSyncGateway()
        gateway.identity = GProfile("other@example.com", "100")
        db.putMessage(ACCOUNT, email("private"))
        assertThrows(AccountChangedException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertNotNull(db.message(ACCOUNT, "private"))
        assertTrue(gateway.listRequests.isEmpty())
    }
}
