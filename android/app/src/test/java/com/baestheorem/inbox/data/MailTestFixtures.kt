package com.baestheorem.inbox.data

import android.app.Application
import com.baestheorem.inbox.gmail.GBody
import com.baestheorem.inbox.gmail.GHeader
import com.baestheorem.inbox.gmail.GHistoryList
import com.baestheorem.inbox.gmail.GLabel
import com.baestheorem.inbox.gmail.GMessage
import com.baestheorem.inbox.gmail.GMessageList
import com.baestheorem.inbox.gmail.GPayload
import com.baestheorem.inbox.gmail.GProfile
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal const val ACCOUNT = "reader@example.com"
internal val TEST_NOW = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

internal fun email(
    id: String,
    date: Long = TEST_NOW,
    labels: List<String> = listOf("INBOX"),
    body: String = "Saved body for $id",
    parts: List<GPayload> = emptyList(),
): GMessage = GMessage(
    id = id, threadId = "thread-$id", internalDate = date.toString(), labelIds = labels,
    snippet = "Preview for $id",
    payload = GPayload(
        mimeType = if (parts.isEmpty()) "text/plain" else "multipart/mixed",
        headers = listOf(GHeader("Subject", "Subject $id"), GHeader("From", "Sender <sender@example.com>")),
        body = if (parts.isEmpty()) GBody(data = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toByteArray())) else null,
        parts = parts,
    ),
)

internal fun attachment(aid: String, name: String = "file.pdf", mime: String = "application/pdf") =
    GPayload(mimeType = mime, filename = name, body = GBody(attachmentId = aid))

@Config(sdk = [34], application = Application::class)
abstract class MailDatabaseTestBase {
    protected lateinit var db: MailDatabase
    private lateinit var context: Application
    private lateinit var name: String

    @Before fun openDatabase() {
        context = RuntimeEnvironment.getApplication()
        name = "test-${UUID.randomUUID()}.db"
        db = MailDatabase(context, name)
        db.activate(ACCOUNT)
    }

    protected fun reopenDatabase() {
        db.close()
        db = MailDatabase(context, name)
    }

    @After fun closeDatabase() {
        db.clear()
        db.close()
        context.deleteDatabase(name)
    }
}

internal class FakeSyncGateway : MailSyncGateway {
    var identity = GProfile(ACCOUNT, "100")
    val messages = mutableMapOf<String, GMessage>()
    val pages = mutableMapOf<String?, GMessageList>(null to GMessageList())
    val historyPages = mutableMapOf<String?, GHistoryList>(null to GHistoryList(historyId = "200"))
    val attachmentBytes = mutableMapOf<Pair<String, String>, ByteArray>()
    val fullRequests = mutableListOf<String>()
    val metadataRequests = mutableListOf<String>()
    val listRequests = mutableListOf<Pair<String, String?>>()
    val historyRequests = mutableListOf<Pair<String, String?>>()
    val attachmentRequests = mutableListOf<Pair<String, String>>()
    var beforeList: (String?) -> Unit = {}
    var beforeHistory: (String?) -> Unit = {}
    var beforeAttachment: (String, String) -> Unit = { _, _ -> }

    override suspend fun profile() = identity
    override suspend fun labels() = listOf(GLabel("INBOX", "INBOX"))
    override suspend fun list(query: String, page: String?): GMessageList {
        listRequests.add(query to page)
        beforeList(page)
        return pages.getValue(page)
    }
    override suspend fun metadata(id: String): GMessage {
        metadataRequests.add(id)
        return messageValue(id).copy(payload = null)
    }
    override suspend fun message(id: String): GMessage {
        fullRequests.add(id)
        return messageValue(id)
    }
    private fun messageValue(id: String): GMessage =
        messages[id] ?: throw com.baestheorem.inbox.gmail.GmailException(404, "Message deleted")
    override suspend fun attachment(id: String, attachmentId: String): ByteArray {
        attachmentRequests.add(id to attachmentId)
        beforeAttachment(id, attachmentId)
        return attachmentBytes.getValue(id to attachmentId)
    }
    override suspend fun history(start: String, page: String?): GHistoryList {
        historyRequests.add(start to page)
        beforeHistory(page)
        return historyPages.getValue(page)
    }
}
