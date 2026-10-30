package com.baestheorem.inbox.gmail

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64

class QueuedMimeTest {
    @Test fun encodesUtf8AndFreezesDateMessageIdAndReplyAncestry() {
        val body = "A draft saved offline.\nRésumé ✓".repeat(20)
        val encoded = buildRawMessage(
            "receiver@example.com", "cc@example.com", "Résumé ✓", body,
            "Reader", "reader@example.com", "<parent@example.com>", "<ancestor@example.com> <parent@example.com>",
            "<stable@inboxclone.invalid>", Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(),
        )
        val raw = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(raw.contains("Message-ID: <stable@inboxclone.invalid>\r\n"))
        assertTrue(raw.contains("Date: Fri, 30 Oct 2026 12:00:00 GMT\r\n"))
        assertTrue(raw.contains("In-Reply-To: <parent@example.com>\r\n"))
        assertTrue(raw.contains("References: <ancestor@example.com> <parent@example.com>\r\n"))
        assertTrue(raw.contains("Cc: cc@example.com\r\n"))
        assertTrue(raw.contains("Subject: =?UTF-8?B?"))
        val bodyPart = raw.substringAfter("\r\n\r\n")
        assertTrue(bodyPart.trimEnd().split("\r\n").all { it.length <= 76 })
        assertEquals(body, String(Base64.getMimeDecoder().decode(bodyPart), Charsets.UTF_8))
    }

    @Test fun rejectsHeaderInjectionBeforeARequestCanBeQueued() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRawMessage("to@example.com\r\nBcc: other@example.com", null, "Subject", "Body",
                "", "reader@example.com", null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildRawMessage("to@example.com", null, "Subject\nBcc: other@example.com", "Body",
                "", "reader@example.com", null, null)
        }
    }
}
