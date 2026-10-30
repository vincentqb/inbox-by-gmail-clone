package com.baestheorem.inbox.data

import com.baestheorem.inbox.gmail.GBody
import com.baestheorem.inbox.gmail.GHeader
import com.baestheorem.inbox.gmail.GPayload
import com.baestheorem.inbox.gmail.decodeBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OfflinePolicyTest {
    @Test fun usesTwoCalendarMonthsInUtcIncludingMonthEnd() {
        val now = Instant.parse("2026-04-30T23:15:00Z").toEpochMilli()
        val expected = Instant.parse("2026-02-28T23:15:00Z").toEpochMilli()
        assertEquals(expected, OfflinePolicy.cutoff(now))
        assertTrue(OfflinePolicy.includes(email("boundary", expected), expected))
        assertFalse(OfflinePolicy.includes(email("older", expected - 1), expected))
        assertEquals(
            "after:${expected / 1000 - 1} -in:spam -in:trash -in:draft -in:chats",
            OfflinePolicy.query(expected),
        )
    }

    @Test fun excludesSpamTrashGmailDraftsAndChats() {
        for (label in listOf("SPAM", "TRASH", "DRAFT", "CHAT")) {
            assertFalse(label, OfflinePolicy.includes(email(label, labels = listOf("INBOX", label)), 0))
        }
        assertTrue(OfflinePolicy.includes(email("sent", labels = listOf("SENT")), 0))
        assertTrue(OfflinePolicy.includes(email("archived", labels = emptyList()), 0))
    }

    @Test fun downloadsNestedAttachmentsAndHydratesOnlyBodyText() {
        val text = "Résumé from a large email body"
        val payload = GPayload(mimeType = "multipart/mixed", parts = listOf(
            GPayload(mimeType = "multipart/alternative", parts = listOf(
                GPayload(mimeType = "text/plain", body = GBody(attachmentId = "text")),
                GPayload(mimeType = "text/html", body = GBody(attachmentId = "html")),
            )),
            attachment("named", "notes.txt", "text/plain"),
            GPayload(mimeType = "text/plain", headers = listOf(GHeader("Content-Disposition", "attachment")),
                body = GBody(attachmentId = "disposition")),
            attachment("image", "logo.png", "image/png"),
        ))
        assertEquals(setOf("text", "html", "named", "disposition", "image"), OfflinePolicy.attachmentIds(payload))
        val hydrated = OfflinePolicy.hydrateTextParts(payload, mapOf(
            "text" to text.toByteArray(), "html" to "<p>$text</p>".toByteArray(),
            "named" to "Not the body".toByteArray(), "disposition" to "Also not the body".toByteArray(),
        ))!!
        assertEquals(text, decodeBody(hydrated).text)
        assertEquals("<p>$text</p>", decodeBody(hydrated).html)
        assertNull(hydrated.parts[1].body?.data)
        assertNull(hydrated.parts[2].body?.data)
    }
}
