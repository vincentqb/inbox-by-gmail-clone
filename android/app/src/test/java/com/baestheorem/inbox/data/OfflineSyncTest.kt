package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GHistoryLabels
import com.baestheorem.inbox.gmail.GHistoryList
import com.baestheorem.inbox.gmail.GHistoryMessage
import com.baestheorem.inbox.gmail.GHistoryRecord
import com.baestheorem.inbox.gmail.GMessage
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
        // The connection drops during the PDF, so the next request fails too.
        var offline = false
        gateway.beforeAttachment = { _, aid -> if (aid == "pdf") { offline = true; throw IOException("Connection lost") } }
        gateway.beforeList = { if (offline) throw IOException("Offline") }

        assertThrows(IOException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertEquals(2 to 1, db.counts(ACCOUNT))
        assertEquals("page-2", db.syncState(ACCOUNT).checkpoint!!.nextPage)
        assertNull(db.syncState(ACCOUNT).historyId)

        reopenDatabase()
        offline = false
        gateway.beforeAttachment = { _, _ -> }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))

        assertEquals(3 to 3, db.counts(ACCOUNT))
        assertEquals(1, gateway.listRequests.count { it.second == null }) // page 1 was not listed again
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
        assertEquals(listOf("new"), db.messages(ACCOUNT).map { it.id })

        gateway.beforeHistory = {}
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals("300", db.syncState(ACCOUNT).historyId)
        assertEquals(setOf("new", "later"), db.messages(ACCOUNT).map { it.id }.toSet())
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
        gateway.beforeMessage = { throw IOException("Download interrupted") }

        assertThrows(IOException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertNotNull(db.message(ACCOUNT, "old-cache"))
        assertEquals("100", db.syncState(ACCOUNT).checkpoint!!.startHistoryId)

        gateway.beforeHistory = {}
        gateway.beforeMessage = {}
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertNull(db.message(ACCOUNT, "old-cache"))
        assertEquals(listOf("current"), db.messages(ACCOUNT).map { it.id })
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
        assertEquals(listOf("fresh"), db.messages(ACCOUNT).map { it.id })
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
        assertEquals(listOf("boundary"), db.messages(ACCOUNT).map { it.id })
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

    // Regression: a change set bigger than one run used to replay from the same cursor forever.
    @Test fun largeHistoryMakesProgressAcrossBudgetedRuns() = runBlocking {
        val gateway = FakeSyncGateway()
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        val ids = (1..30).map { "m$it" }
        ids.forEach { gateway.messages[it] = email(it) }
        gateway.historyAt["100" to null] = GHistoryList(listOf(GHistoryRecord(
            messagesAdded = ids.map { GHistoryMessage(GMessage(it, "thread-$it", listOf("INBOX"))) },
        )), historyId = "300")
        gateway.historyPages[null] = GHistoryList(historyId = "300")
        var clock = TEST_NOW
        var runs = 0
        while (true) {
            runs++
            assertTrue("no progress after $runs runs", runs < 10)
            val start = clock
            // Simulated clock: every now() call costs 1 ms; each run gets a 30 ms budget.
            if (OfflineSync(db, gateway, now = { clock++ }).run(ACCOUNT, deadline = start + 30)) break
            assertEquals("300", db.syncState(ACCOUNT).historyId) // the cursor moved after the first page
        }
        assertTrue(runs > 1)
        assertEquals(ids.toSet(), db.messages(ACCOUNT).map { it.id }.toSet())
        assertEquals(ids.size, gateway.fullRequests.size) // each downloaded exactly once
        assertEquals(1, gateway.historyRequests.count { it.first == "100" })
    }

    @Test fun historyAppliesLabelChangesLocallyAndDownloadsOnlyWhatIsNew() = runBlocking {
        val gateway = FakeSyncGateway()
        db.putMessage(ACCOUNT, email("read-me", labels = listOf("INBOX", "UNREAD")))
        db.putMessage(ACCOUNT, email("trash-me"))
        db.putMessage(ACCOUNT, email("gone"))
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.messages["back"] = email("back")
        gateway.historyPages[null] = GHistoryList(listOf(GHistoryRecord(
            messagesAdded = listOf(GHistoryMessage(GMessage("a-draft", labelIds = listOf("DRAFT")))),
            messagesDeleted = listOf(GHistoryMessage(GMessage("gone"))),
            labelsAdded = listOf(
                GHistoryLabels(GMessage("trash-me"), listOf("TRASH")),
                GHistoryLabels(GMessage("read-me"), listOf("STARRED")),
            ),
            labelsRemoved = listOf(
                GHistoryLabels(GMessage("read-me"), listOf("UNREAD")),
                GHistoryLabels(GMessage("back"), listOf("TRASH")),
            ),
        )), historyId = "300")

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        val read = db.messages(ACCOUNT).single { it.id == "read-me" }
        assertEquals(listOf("INBOX", "STARRED"), read.labelIds)
        assertEquals(TEST_NOW.toString(), read.internalDate) // headers and date survive label changes
        assertEquals("Subject read-me", read.payload!!.headers.first().value)
        assertNull(db.message(ACCOUNT, "trash-me"))
        assertNull(db.message(ACCOUNT, "gone"))
        assertNotNull(db.message(ACCOUNT, "back"))
        assertEquals(listOf("back"), gateway.fullRequests) // not the draft, not the label changes
        assertTrue(gateway.minimalRequests.isEmpty())
        assertEquals("300", db.syncState(ACCOUNT).historyId)
    }

    // Google's sync guide: no periodic full sync while history is available.
    @Test fun noFullSyncWhileHistoryIsAvailable() = runBlocking {
        val gateway = FakeSyncGateway()
        db.putMessage(ACCOUNT, email("saved"))
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100", lastFullSync = 0))
        gateway.historyPages[null] = GHistoryList(historyId = "100")

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW + 3 * DAY }).run(ACCOUNT))
        assertTrue(gateway.listRequests.isEmpty())
        assertTrue(gateway.fullRequests.isEmpty() && gateway.minimalRequests.isEmpty())
        assertNotNull(db.message(ACCOUNT, "saved"))
    }

    @Test fun rebuildChecksSavedMailWithMinimalFetchesAndKeepsOpenedOldThreads() = runBlocking {
        val gateway = FakeSyncGateway()
        val cutoff = OfflinePolicy.cutoff(TEST_NOW)
        db.putMessage(ACCOUNT, email("in-window"))
        db.putMessage(ACCOUNT, email("old-opened", date = cutoff - DAY))
        db.markOpened(ACCOUNT, "thread-old-opened", TEST_NOW - DAY)
        db.putMessage(ACCOUNT, email("old-unopened", date = cutoff - DAY))
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "expired"))
        gateway.messages["in-window"] = email("in-window", labels = listOf("INBOX", "STARRED"))
        gateway.messages["old-opened"] = email("old-opened", date = cutoff - DAY, labels = listOf("SENT"))
        gateway.messages["old-unopened"] = email("old-unopened", date = cutoff - DAY)
        gateway.pages[null] = GMessageList(listOf(GMessageRef("in-window")))
        gateway.historyAt["expired" to null] = GHistoryList()
        gateway.historyPages[null] = GHistoryList(historyId = "100")
        gateway.beforeHistory = { if (gateway.historyRequests.size == 1) throw GmailException(404, "History expired") }

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(setOf("in-window", "old-opened"), db.messages(ACCOUNT).map { it.id }.toSet())
        assertEquals(listOf("INBOX", "STARRED"), db.message(ACCOUNT, "in-window")!!.labelIds)
        assertEquals(listOf("SENT"), db.message(ACCOUNT, "old-opened")!!.labelIds)
        assertTrue(gateway.fullRequests.isEmpty()) // nothing downloaded twice
        assertEquals(setOf("in-window", "old-opened"), gateway.minimalRequests.toSet())
        assertEquals("100", db.syncState(ACCOUNT).historyId)
    }

    @Test fun expiredHistoryPageTokenReplaysFromTheCursor() = runBlocking {
        val gateway = FakeSyncGateway()
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100", historyPage = HistoryPage("100", "stale", "250")))
        gateway.beforeHistory = { if (it == "stale") throw GmailException(400, "Invalid pageToken") }
        gateway.messages["new"] = email("new")
        gateway.historyPages[null] = GHistoryList(listOf(GHistoryRecord(
            messagesAdded = listOf(GHistoryMessage(GMessage("new", labelIds = listOf("INBOX")))),
        )), historyId = "300")

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(listOf("100" to "stale", "100" to null), gateway.historyRequests)
        assertEquals("300", db.syncState(ACCOUNT).historyId)
        assertNull(db.syncState(ACCOUNT).historyPage)
        assertNotNull(db.message(ACCOUNT, "new"))
    }

    @Test fun lowStorageKeepsTextSkipsFilesAndTrimsTheOldestFirst() = runBlocking {
        val gateway = FakeSyncGateway()
        val pdf = byteArrayOf(7, 8, 9)
        gateway.messages["new"] = email("new", parts = listOf(attachment("body", "", "text/plain"), attachment("pdf")))
        gateway.messages["older"] = email("older", date = TEST_NOW - DAY, parts = listOf(attachment("img", "a.png", "image/png")))
        gateway.attachmentBytes["new" to "body"] = "Readable text".toByteArray()
        gateway.attachmentBytes["new" to "pdf"] = pdf
        gateway.pages[null] = GMessageList(listOf(GMessageRef("new"), GMessageRef("older")))
        db.putMessage(ACCOUNT, gateway.messages.getValue("older"))
        db.putAttachment(ACCOUNT, "older", "img", byteArrayOf(1))
        db.markComplete(ACCOUNT, "older")
        // Low until the oldest message's file is gone.
        val sync = OfflineSync(db, gateway, now = { TEST_NOW }, freeSpace = {
            if (db.attachment(ACCOUNT, "older", "img") != null) OfflineSync.LOW_STORAGE - 1 else Long.MAX_VALUE
        })
        // Files download only once trimming has made room; text doesn't wait.
        gateway.beforeAttachment = { _, aid -> if (aid == "pdf") assertNull(db.attachment(ACCOUNT, "older", "img")) }

        assertTrue(sync.run(ACCOUNT))
        assertEquals("Readable text", decodeBody(db.message(ACCOUNT, "new")!!.payload).text)
        assertNull(db.attachment(ACCOUNT, "older", "img"))
        assertArrayEquals(pdf, db.attachment(ACCOUNT, "new", "pdf"))
        assertEquals(2 to 1, db.counts(ACCOUNT))

        assertTrue(sync.run(ACCOUNT))
        assertNull(db.attachment(ACCOUNT, "older", "img")) // trimmed files stay gone in the background
        assertEquals(2 to 1, db.counts(ACCOUNT))
    }

    @Test fun aFailingAttachmentNeitherStopsTheSyncNorHoldsUpOtherDownloads() = runBlocking {
        val gateway = FakeSyncGateway()
        val cutoff = OfflinePolicy.cutoff(TEST_NOW)
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.historyPages[null] = GHistoryList(historyId = "100")
        db.putMessage(ACCOUNT, email("broken", parts = listOf(attachment("huge"))))
        db.putMessage(ACCOUNT, email("fine", date = TEST_NOW - DAY, parts = listOf(attachment("pdf"))))
        db.putMessage(ACCOUNT, email("aged-out", date = cutoff - DAY))
        db.markComplete(ACCOUNT, "aged-out")
        gateway.attachmentBytes["fine" to "pdf"] = byteArrayOf(5)
        gateway.beforeAttachment = { _, aid -> if (aid == "huge") throw GmailException(403, "Forbidden") }

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertArrayEquals(byteArrayOf(5), db.attachment(ACCOUNT, "fine", "pdf"))
        assertNull(db.message(ACCOUNT, "aged-out")) // eviction still ran
        assertEquals(TEST_NOW, db.syncState(ACCOUNT).lastSync)
        assertEquals(listOf("broken"), db.incomplete(ACCOUNT, 10))

        // Retried on later runs until it has failed too often, then it waits to be opened.
        repeat(MailDatabase.MAX_DOWNLOAD_ATTEMPTS - 1) {
            assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        }
        assertTrue(db.incomplete(ACCOUNT, 10).isEmpty())
        assertEquals(MailDatabase.MAX_DOWNLOAD_ATTEMPTS, gateway.attachmentRequests.count { it.second == "huge" })
    }

    @Test fun sustainedLowStorageKeepsTextAndLetsFilesDownloadOnceThereIsRoom() = runBlocking {
        val gateway = FakeSyncGateway()
        var free = OfflineSync.LOW_STORAGE - 1 // other apps filled the phone; trimming can't fix it
        gateway.messages["new"] = email("new", parts = listOf(attachment("body", "", "text/plain"), attachment("pdf")))
        gateway.attachmentBytes["new" to "body"] = "Readable text".toByteArray()
        gateway.attachmentBytes["new" to "pdf"] = byteArrayOf(3)
        gateway.pages[null] = GMessageList(listOf(GMessageRef("new")))
        val sync = OfflineSync(db, gateway, now = { TEST_NOW }, freeSpace = { free })

        assertTrue(sync.run(ACCOUNT))
        assertTrue(sync.run(ACCOUNT))
        assertEquals("Readable text", decodeBody(db.message(ACCOUNT, "new")!!.payload).text)
        assertTrue(gateway.attachmentRequests.none { it.second == "pdf" })
        assertEquals(1, gateway.attachmentRequests.size) // the text, once

        free = Long.MAX_VALUE
        assertTrue(sync.run(ACCOUNT))
        assertArrayEquals(byteArrayOf(3), db.attachment(ACCOUNT, "new", "pdf"))
        assertEquals(1 to 1, db.counts(ACCOUNT))
    }

    @Test fun aDownloadThatCrashesTheRunSinksBehindTheRest() = runBlocking {
        val gateway = FakeSyncGateway()
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.historyPages[null] = GHistoryList(historyId = "100")
        db.putMessage(ACCOUNT, email("huge", parts = listOf(attachment("video"))))
        db.putMessage(ACCOUNT, email("fine", date = TEST_NOW - DAY, parts = listOf(attachment("pdf"))))
        gateway.attachmentBytes["fine" to "pdf"] = byteArrayOf(5)
        // Not an IOException: think OutOfMemoryError. The run dies without a chance to clean up.
        gateway.beforeAttachment = { _, aid -> if (aid == "video") throw IllegalStateException("Out of memory") }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertNull(db.attachment(ACCOUNT, "fine", "pdf"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
        }
        assertArrayEquals(byteArrayOf(5), db.attachment(ACCOUNT, "fine", "pdf")) // went first this time
        while (gateway.attachmentRequests.count { it.second == "video" } < MailDatabase.MAX_DOWNLOAD_ATTEMPTS) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
            }
        }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT)) // it waits to be opened now
    }

    @Test fun aQueuedMessageThatKeepsFailingIsDroppedAndTheRestDownload() = runBlocking {
        val gateway = FakeSyncGateway()
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.messages["ok"] = email("ok")
        gateway.messages["bad"] = email("bad")
        gateway.historyAt["100" to null] = GHistoryList(listOf(GHistoryRecord(messagesAdded = listOf(
            GHistoryMessage(GMessage("ok", labelIds = listOf("INBOX"))),
            GHistoryMessage(GMessage("bad", labelIds = listOf("INBOX"))), // newest: tried first
        ))), historyId = "300")
        gateway.historyPages[null] = GHistoryList(historyId = "300")
        gateway.beforeMessage = { if (it == "bad") throw GmailException(500, "Backend error") }

        repeat(MailDatabase.MAX_QUEUE_TRIES) {
            assertThrows(IOException::class.java) {
                runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
            }
        }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertNotNull(db.message(ACCOUNT, "ok"))
        assertNull(db.message(ACCOUNT, "bad"))
        assertTrue(db.queued(ACCOUNT, 10).isEmpty())
    }

    @Test fun aFullSyncMessageThatKeepsFailingIsSkipped() = runBlocking {
        val gateway = FakeSyncGateway()
        gateway.messages["bad"] = email("bad")
        gateway.messages["ok"] = email("ok")
        gateway.pages[null] = GMessageList(listOf(GMessageRef("bad"), GMessageRef("ok")))
        gateway.beforeMessage = { if (it == "bad") throw GmailException(500, "Backend error") }

        repeat(5) {
            assertThrows(IOException::class.java) {
                runBlocking { OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT) }
            }
        }
        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(listOf("ok"), db.messages(ACCOUNT).map { it.id })
        assertNull(db.syncState(ACCOUNT).checkpoint)
    }

    @Test fun mailMovedToSpamBeforeItsDownloadIsNeverFetched() = runBlocking {
        val gateway = FakeSyncGateway()
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.messages["spammy"] = email("spammy")
        gateway.historyPages[null] = GHistoryList(listOf(
            GHistoryRecord(messagesAdded = listOf(GHistoryMessage(GMessage("spammy", labelIds = listOf("INBOX"))))),
            GHistoryRecord(labelsAdded = listOf(GHistoryLabels(GMessage("spammy"), listOf("SPAM")))),
        ), historyId = "300")

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertTrue(gateway.fullRequests.isEmpty())
        assertTrue(db.queued(ACCOUNT, 10).isEmpty())
    }

    @Test fun dailyEvictionRemovesThreadsNobodyKept() = runBlocking {
        val gateway = FakeSyncGateway()
        val cutoff = OfflinePolicy.cutoff(TEST_NOW)
        db.putMessage(ACCOUNT, email("recent"))
        db.putMessage(ACCOUNT, email("aged-out", date = cutoff - DAY))
        db.putMessage(ACCOUNT, email("looked-at", date = cutoff - 30 * DAY))
        db.markOpened(ACCOUNT, "thread-looked-at", TEST_NOW - 10 * DAY)
        db.saveSyncState(ACCOUNT, OfflineSyncState(historyId = "100"))
        gateway.historyPages[null] = GHistoryList(historyId = "100")

        assertTrue(OfflineSync(db, gateway, now = { TEST_NOW }).run(ACCOUNT))
        assertEquals(setOf("recent", "looked-at"), db.messages(ACCOUNT).map { it.id }.toSet())
        assertEquals(TEST_NOW, db.syncState(ACCOUNT).lastEviction)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
