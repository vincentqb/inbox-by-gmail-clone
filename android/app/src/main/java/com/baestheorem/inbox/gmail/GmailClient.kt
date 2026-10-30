package com.baestheorem.inbox.gmail

import android.content.Context
import android.util.Log
import com.baestheorem.inbox.auth.AuthStore
import com.baestheorem.inbox.auth.OAuthFlow
import com.baestheorem.inbox.data.AccountChangedException
import com.baestheorem.inbox.data.Connectivity
import com.baestheorem.inbox.data.LocalDraft
import com.baestheorem.inbox.data.MailDatabase
import com.baestheorem.inbox.data.MailSyncGateway
import com.baestheorem.inbox.data.OfflineMail
import com.baestheorem.inbox.data.OutboxGateway
import com.baestheorem.inbox.data.PreparedSender
import com.baestheorem.inbox.data.SendRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.FormBody
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val TAG = "GmailClient"

class GmailException(val code: Int, val bodyText: String) :
    IOException("Gmail $code: ${bodyText.take(160)}")

/** The refresh token is dead (revoked, expired, or the client changed). */
class AuthExpiredException(message: String) : IOException(message)

// format=full with a fields mask that returns headers + the attachment part
// tree WITHOUT body bytes, so list rows stay cheap (mirrors app.py's _LIST_FIELDS).
private const val LIST_PART = "partId,mimeType,filename,headers,body/attachmentId,body/size"
private const val LIST_FIELDS = "id,threadId,internalDate,labelIds,snippet," +
    "payload(mimeType,headers,parts($LIST_PART,parts($LIST_PART,parts($LIST_PART))))"

/**
 * Raw-HTTPS Gmail client, same shape as app.py's AuthorizedSession layer: no
 * SDK, just the REST endpoints plus retry/backoff, a per-message metadata cache
 * and the history cursor that keeps a refresh from re-downloading the page.
 */
object GmailClient : MailSyncGateway, OutboxGateway {
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var tokenExpiry = 0L
    private var refreshing: Deferred<String>? = null
    private var tokenVersion = -1L

    private val cacheLock = Mutex()
    private val labelIdCache = HashMap<String, String>()
    private var cachedEmail: String? = null
    private var cachedDisplayName: String? = null

    /** Flips when Google stops honoring the refresh token; the UI re-runs the wizard. */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired

    // Per-message metadata cache, same design as app.py's _meta_cache. Headers,
    // snippet, date and part tree never change after delivery; only labelIds
    // move, and history.list says exactly which messages those were.
    private const val META_CAP = 3000
    private val metaCache = LinkedHashMap<String, GMessage>()
    private val metaTids = HashMap<String, MutableSet<String>>()
    private var historyId: String? = null
    private var persistJob: kotlinx.coroutines.Job? = null

    // A thread open outranks list work. While one is in flight, batchMeta and
    // the body scanner park between chunks so the per-second quota is the
    // reader's, not the list's.
    private val interactive = MutableStateFlow(0)

    // Attachment bytes by messageId/attachmentId, so reopening a thread does not
    // re-download its inline images. Bounded by total size.
    private const val ATT_CAP = 24 * 1024 * 1024
    private val attCache = LinkedHashMap<String, ByteArray>()
    private var attBytes = 0

    @Serializable
    private data class PersistedMeta(
        val historyId: String? = null,
        val messages: List<GMessage> = emptyList(),
        val account: String = "",
    )

    private val metaFile: File get() = File(appContext.filesDir, "meta-cache.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val f = metaFile
        if (!f.exists()) return
        try {
            val p = Net.json.decodeFromString(PersistedMeta.serializer(), f.readText())
            if (p.account.isBlank() || MailDatabase.normalize(p.account) != MailDatabase.normalize(AuthStore.account)) {
                f.delete()
                return
            }
            historyId = p.historyId
            for (m in p.messages) {
                metaCache[m.id] = m
                m.threadId?.let { metaTids.getOrPut(it) { HashSet() }.add(m.id) }
            }
            Log.d(TAG, "loaded ${p.messages.size} cached rows, history ${p.historyId}")
        } catch (e: Exception) {
            f.delete()
        }
    }

    // MARK: Auth

    private suspend fun token(expectedVersion: Long = AuthStore.sessionVersion): String {
        checkSession(expectedVersion)
        val now = System.currentTimeMillis()
        accessToken?.let { if (tokenVersion == expectedVersion && tokenExpiry > now + 60_000) return it }
        val job = tokenLock.withLock {
            checkSession(expectedVersion)
            accessToken?.let {
                if (tokenVersion == expectedVersion && tokenExpiry > System.currentTimeMillis() + 60_000) return it
            }
            if (tokenVersion != expectedVersion) {
                refreshing?.cancel()
                refreshing = null
            }
            tokenVersion = expectedVersion
            refreshing ?: scope.async { fetchToken(expectedVersion) }.also { refreshing = it }
        }
        try {
            return job.await().also { checkSession(expectedVersion) }
        } finally {
            tokenLock.withLock { if (refreshing === job) refreshing = null }
        }
    }

    private fun checkSession(expectedVersion: Long) {
        if (expectedVersion != AuthStore.sessionVersion) throw AccountChangedException()
    }

    private fun fetchToken(expectedVersion: Long): String {
        checkSession(expectedVersion)
        val refresh = AuthStore.refreshToken
        if (refresh.isEmpty()) throw AuthExpiredException("Not signed in.")
        val form = FormBody.Builder()
            .add("client_id", AuthStore.clientId)
            .add("client_secret", AuthStore.clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(OAuthFlow.TOKEN_ENDPOINT).post(form).build()
        checkSession(expectedVersion)
        val (code, body) = Net.client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        checkSession(expectedVersion)
        if (code != 200) {
            // invalid_grant means the grant is gone for good: revoked by the user,
            // or expired because the Cloud project is still in "Testing".
            if (body.contains("invalid_grant")) {
                _authExpired.value = true
                throw AuthExpiredException("Google signed this app out. Sign in again.")
            }
            throw GmailException(code, body)
        }
        val obj = Net.json.parseToJsonElement(body) as JsonObject
        val tok = (obj["access_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw GmailException(code, body)
        val expires = (obj["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toLongOrNull() ?: 3600L
        accessToken = tok
        tokenExpiry = System.currentTimeMillis() + expires * 1000
        _authExpired.value = false
        return tok
    }

    /** Called after the wizard re-authorizes, so cached state does not leak across accounts. */
    suspend fun resetForNewAccount() {
        tokenLock.withLock {
            refreshing?.cancel()
            refreshing = null
            accessToken = null
            tokenExpiry = 0
            tokenVersion = -1
        }
        persistJob?.cancel()
        _authExpired.value = false
        cacheLock.withLock {
            cachedEmail = null
            cachedDisplayName = null
            labelIdCache.clear()
            metaCache.clear()
            metaTids.clear()
            attCache.clear()
            attBytes = 0
            historyId = null
        }
        withContext(Dispatchers.IO) { metaFile.delete() }
    }

    // MARK: Transport with retry (mirrors app.py's _request)

    // tries/maxBackoff: background list work can afford the full 6-try, 32s
    // ceiling; anything the user is waiting on (thread open, inline image)
    // passes a short budget so a rate limit surfaces in seconds, not a minute.
    private suspend fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        jsonBody: String? = null,
        tries: Int = 6,
        maxBackoff: Double = 32.0,
    ): String = withContext(Dispatchers.IO) {
        val session = AuthStore.sessionVersion
        var attempt = 0
        while (attempt < tries) {
            checkSession(session)
            val tok = token(session)
            val url: HttpUrl = (BASE + path).toHttpUrl().newBuilder().apply {
                for ((k, v) in query) addQueryParameter(k, v)
            }.build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $tok")
            if (jsonBody != null) {
                builder.method(method, jsonBody.toRequestBody("application/json".toMediaType()))
            } else if (method == "POST") {
                builder.post("".toRequestBody("application/json".toMediaType()))
            } else {
                builder.method(method, null)
            }
            try {
                val (code, body) = Net.client.newCall(builder.build()).execute()
                    .use { it.code to (it.body?.string() ?: "") }
                checkSession(session)
                if (code == 401) {
                    // stale access token; drop it and retry with a fresh one
                    accessToken = null
                    attempt++
                    if (attempt >= tries) throw GmailException(code, body)
                    continue
                }
                val rateLimited = code == 403 && (
                    body.contains("rateLimitExceeded") || body.contains("Quota exceeded") ||
                        body.contains("userRateLimitExceeded") || body.contains("backendError")
                    )
                if (code in listOf(429, 500, 502, 503, 504) || rateLimited) {
                    Log.w(TAG, "$code on $path attempt ${attempt + 1}/$tries")
                    attempt++
                    if (attempt >= tries) throw GmailException(code, body)
                    backoff(attempt - 1, maxBackoff)
                    continue
                }
                if (code >= 400) {
                    Log.e(TAG, "$code on $path: ${body.take(200)}")
                    throw GmailException(code, body)
                }
                return@withContext body
            } catch (e: IOException) {
                if (e is GmailException || e is AuthExpiredException || e is AccountChangedException) throw e
                // network blip; back off and retry
                attempt++
                if (attempt >= tries) throw e
                backoff(attempt - 1, maxBackoff)
            }
        }
        throw GmailException(0, "gave up on $path")
    }

    private suspend fun backoff(attempt: Int, cap: Double) {
        val secs = min(cap, 2.0.pow(attempt)) + Random.nextDouble(0.0, 0.5)
        delay((secs * 1000).toLong())
    }

    private suspend fun <T> get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        tries: Int = 6,
        maxBackoff: Double = 32.0,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = Net.json.decodeFromString(serializer, request("GET", path, query, null, tries, maxBackoff))

    // MARK: Interactive priority

    private fun beginInteractive() {
        interactive.value = interactive.value + 1
    }

    private fun endInteractive() {
        interactive.value = maxOf(0, interactive.value - 1)
    }

    private suspend fun waitForIdle() {
        if (interactive.value > 0) interactive.first { it == 0 }
    }

    // MARK: Metadata cache + history cursor

    private fun persistSoon() {
        persistJob?.cancel()
        val session = AuthStore.sessionVersion
        val account = AuthStore.account
        persistJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            if (session != AuthStore.sessionVersion) return@launch
            val keep = cacheLock.withLock { metaCache.values.toList().takeLast(800) }
            val p = PersistedMeta(historyId, keep, account)
            try {
                metaFile.writeText(Net.json.encodeToString(PersistedMeta.serializer(), p))
            } catch (e: IOException) {
                Log.w(TAG, "could not persist metadata cache", e)
            }
        }
    }

    private fun metaPutLocked(m: GMessage) {
        metaCache.remove(m.id)
        metaCache[m.id] = m
        m.threadId?.let { metaTids.getOrPut(it) { HashSet() }.add(m.id) }
        while (metaCache.size > META_CAP) {
            val oldest = metaCache.keys.firstOrNull() ?: break
            val gone = metaCache.remove(oldest)
            gone?.threadId?.let { tid ->
                metaTids[tid]?.remove(oldest)
                if (metaTids[tid]?.isEmpty() == true) metaTids.remove(tid)
            }
        }
    }

    private fun metaEvictLocked(mids: Collection<String>) {
        for (mid in mids) {
            val gone = metaCache.remove(mid) ?: continue
            gone.threadId?.let { tid ->
                metaTids[tid]?.remove(mid)
                if (metaTids[tid]?.isEmpty() == true) metaTids.remove(tid)
            }
        }
    }

    /** Every local label change funnels through modifyThread, so it evicts here. */
    suspend fun evictThread(tid: String) = cacheLock.withLock {
        metaTids.remove(tid)?.let { metaEvictLocked(it.toList()) }
    }

    data class HistoryResult(val threads: Set<String>, val all: Boolean)

    /**
     * What changed since the last refresh, per Gmail's history log: evicts the
     * touched messages and reports their thread ids so the store can drop stale
     * reader caches too. Call once per refresh pass.
     */
    suspend fun syncHistory(): HistoryResult {
        try {
            val start = historyId
            if (start == null) {
                historyId = get("/profile", serializer = GProfile.serializer()).historyId
                return HistoryResult(emptySet(), false)
            }
            val mids = HashSet<String>()
            val tids = HashSet<String>()
            var newest = start
            var page: String? = null
            do {
                val q = mutableListOf("startHistoryId" to start, "maxResults" to "500")
                for (t in listOf("messageAdded", "messageDeleted", "labelAdded", "labelRemoved")) {
                    q.add("historyTypes" to t)
                }
                page?.let { q.add("pageToken" to it) }
                val resp = get("/history", q, tries = 2, maxBackoff = 2.0, serializer = GHistoryList.serializer())
                for (h in resp.history) {
                    for (m in h.messages) {
                        mids.add(m.id)
                        m.threadId?.let { tids.add(it) }
                    }
                }
                resp.historyId?.let { newest = it }
                page = resp.nextPageToken
            } while (page != null)
            if (mids.isNotEmpty()) {
                Log.i(TAG, "history: ${mids.size} messages moved across ${tids.size} threads")
                cacheLock.withLock { metaEvictLocked(mids) }
            }
            historyId = newest
            persistSoon()
            return HistoryResult(tids, false)
        } catch (e: GmailException) {
            if (e.code == 404) {
                // startHistoryId too old: reset the cursor and refetch everything once
                Log.i(TAG, "history cursor expired; clearing metadata cache")
                cacheLock.withLock {
                    metaCache.clear()
                    metaTids.clear()
                }
                historyId = try {
                    get("/profile", serializer = GProfile.serializer()).historyId
                } catch (e2: IOException) {
                    null
                }
                persistSoon()
                return HistoryResult(emptySet(), true)
            }
            Log.w(TAG, "history sync failed: ${e.message}")
            return HistoryResult(emptySet(), false)
        } catch (e: IOException) {
            Log.w(TAG, "history sync failed: ${e.message}")
            return HistoryResult(emptySet(), false)
        }
    }

    // MARK: Gmail operations

    suspend fun listMessages(
        labelIds: List<String> = emptyList(),
        q: String? = null,
        maxResults: Int = 50,
        pageToken: String? = null,
    ): GMessageList {
        val query = mutableListOf("maxResults" to maxResults.toString())
        for (l in labelIds) query.add("labelIds" to l)
        q?.let { query.add("q" to it) }
        pageToken?.let { query.add("pageToken" to it) }
        return get("/messages", query, serializer = GMessageList.serializer())
    }

    /** null on any failure: a message that vanished mid-fetch just drops from the list. */
    suspend fun getMessageMeta(id: String): GMessage? = try {
        get(
            "/messages/$id",
            listOf("format" to "full", "fields" to LIST_FIELDS),
            serializer = GMessage.serializer(),
        )
    } catch (e: IOException) {
        null
    }

    /** Concurrent metadata fetch, capped at 8 in flight to stay under the per-second quota. */
    suspend fun batchMeta(ids: List<String>): List<GMessage> {
        val out = mutableListOf<GMessage>()
        val misses = mutableListOf<String>()
        cacheLock.withLock {
            for (id in ids) {
                val hit = metaCache[id]
                if (hit != null) out.add(hit) else misses.add(id)
            }
        }
        var i = 0
        while (i < misses.size) {
            waitForIdle()
            val chunk = misses.subList(i, min(i + 8, misses.size)).toList()
            val fetched = withContext(Dispatchers.IO) {
                chunk.map { id -> async { getMessageMeta(id) } }.awaitAll()
            }
            cacheLock.withLock {
                for (m in fetched) {
                    if (m != null) {
                        out.add(m)
                        metaPutLocked(m)
                    }
                }
            }
            i += 8
        }
        if (misses.isNotEmpty()) persistSoon()
        return out
    }

    /** The user is staring at a spinner for this one: short budget, real error. */
    suspend fun getThread(id: String): GThread {
        beginInteractive()
        try {
            return get(
                "/threads/$id",
                listOf("format" to "full"),
                tries = 3,
                maxBackoff = 4.0,
                serializer = GThread.serializer(),
            )
        } finally {
            endInteractive()
        }
    }

    suspend fun modifyThread(id: String, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        val body = buildJsonObject {
            if (add.isNotEmpty()) put("addLabelIds", buildJsonArray { add.forEach { add(it) } })
            if (remove.isNotEmpty()) put("removeLabelIds", buildJsonArray { remove.forEach { add(it) } })
        }
        evictThread(id)
        val account = AuthStore.account
        val response = request("POST", "/threads/$id/modify", jsonBody = body.toString())
        withContext(Dispatchers.IO) {
            if (account.isNotBlank() && OfflineMail.db.owner() == MailDatabase.normalize(account)) {
                val thread = Net.json.decodeFromString(GThread.serializer(), response)
                thread.messages.forEach { OfflineMail.db.updateMetadata(account, it) }
                OfflineMail.publish()
            }
        }
    }

    suspend fun listLabels(): List<GLabel> =
        get("/labels", serializer = GLabelList.serializer()).labels

    suspend fun ensureLabel(name: String): String {
        cacheLock.withLock { labelIdCache[name] }?.let { return it }
        for (l in listLabels()) {
            if (l.name == name) {
                cacheLock.withLock { labelIdCache[name] = l.id }
                return l.id
            }
        }
        val body = buildJsonObject {
            put("name", name)
            put("labelListVisibility", "labelShow")
            put("messageListVisibility", "show")
        }
        val created = Net.json.decodeFromString(
            GLabel.serializer(),
            request("POST", "/labels", jsonBody = body.toString()),
        )
        cacheLock.withLock { labelIdCache[name] = created.id }
        return created.id
    }

    suspend fun labelInfo(id: String): GLabel =
        get("/labels/$id", serializer = GLabel.serializer())

    suspend fun profileEmail(): String {
        cacheLock.withLock { cachedEmail }?.let { return it }
        val email = get("/profile", serializer = GProfile.serializer()).emailAddress ?: ""
        cacheLock.withLock { cachedEmail = email }
        return email
    }

    /**
     * "Send mail as" displayName so outgoing mail carries a real name, not a bare
     * address (same two-source lookup as app.py's _lookup_display_name: Gmail only
     * fills sendAs displayName when it overrides the account name, and the scope
     * for it may not be granted, so fall back to the From header Gmail itself
     * wrote on already-sent mail).
     */
    suspend fun displayName(): String {
        cacheLock.withLock { cachedDisplayName }?.let { return it }
        var name = try {
            get("/settings/sendAs", serializer = GSendAsList.serializer())
                .sendAs.firstOrNull { it.isPrimary == true }?.displayName ?: ""
        } catch (e: IOException) {
            ""
        }
        if (name.isEmpty()) {
            val sent = try {
                listMessages(labelIds = listOf("SENT"), maxResults = 5)
            } catch (e: IOException) {
                null
            }
            for (ref in sent?.messages ?: emptyList()) {
                val m = getMessageMeta(ref.id) ?: continue
                val a = parseAddr(headerValue(m.payload?.headers ?: emptyList(), "From"))
                if (a.name.isNotEmpty() && !a.name.contains("@")) {
                    name = a.name
                    break
                }
            }
        }
        cacheLock.withLock { cachedDisplayName = name }
        return name
    }

    suspend fun send(raw: String, threadId: String?) {
        val account = profileEmail()
        val prepared = prepare(account)
        prepared.send(LocalDraft(account = account, raw = raw, threadId = threadId))
    }

    override suspend fun prepare(account: String): PreparedSender {
        val session = AuthStore.sessionVersion
        val tok = token(session)
        val profile = profile()
        checkSession(session)
        if (MailDatabase.normalize(profile.emailAddress.orEmpty()) != MailDatabase.normalize(account)) {
            throw AccountChangedException()
        }
        return PreparedSender { draft ->
            withContext(Dispatchers.IO) {
                checkSession(session)
                if (MailDatabase.normalize(draft.account) != MailDatabase.normalize(account)) throw AccountChangedException()
                val raw = draft.raw ?: throw SendRejectedException(false, "The queued message has no saved content.")
                sendOnce(tok, raw, draft.threadId)
            }
        }
    }

    private fun sendOnce(tok: String, raw: String, threadId: String?) {
        val body = buildJsonObject {
            put("raw", raw)
            threadId?.let { put("threadId", it) }
        }
        val req = Request.Builder().url("$BASE/messages/send")
            .header("Authorization", "Bearer $tok")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Net.singleSendClient.newCall(req).execute().use { response ->
            if (response.isSuccessful) return
            if (response.code == 401) accessToken = null
            if (response.code in 400..499 && response.code != 408) {
                val retryable = response.code in setOf(401, 429) ||
                    response.code == 403 && response.body?.string().orEmpty().contains("rateLimit", true)
                throw SendRejectedException(retryable,
                    if (response.code == 401) "Google rejected the token. Will retry after signing in."
                    else if (retryable) "Gmail rate limit. Will retry automatically."
                    else "Gmail rejected this message (${response.code}). Edit it or retry after fixing access.")
            }
            // 5xx/408/redirects may come after delivery: require Sent reconciliation.
            throw IOException("Gmail did not confirm delivery (${response.code}).")
        }
    }

    override suspend fun isInSent(account: String, rfcMessageId: String): Boolean {
        val session = AuthStore.sessionVersion
        if (MailDatabase.normalize(profile().emailAddress.orEmpty()) != MailDatabase.normalize(account)) {
            throw AccountChangedException()
        }
        val result = listMessages(q = "in:sent rfc822msgid:${rfcMessageId.trim('<', '>')}", maxResults = 1)
        checkSession(session)
        return result.messages.isNotEmpty()
    }

    override suspend fun profile(): GProfile = get("/profile", serializer = GProfile.serializer())
    override suspend fun labels(): List<GLabel> = listLabels()
    override suspend fun list(query: String, page: String?): GMessageList =
        listMessages(q = query, maxResults = 500, pageToken = page)
    override suspend fun metadata(id: String): GMessage = get(
        "/messages/$id", listOf("format" to "full", "fields" to LIST_FIELDS), serializer = GMessage.serializer(),
    )
    override suspend fun message(id: String): GMessage = get(
        "/messages/$id", listOf("format" to "full"), serializer = GMessage.serializer(),
    )
    override suspend fun history(start: String, page: String?): GHistoryList {
        val query = mutableListOf("startHistoryId" to start, "maxResults" to "500")
        page?.let { query.add("pageToken" to it) }
        return get("/history", query, serializer = GHistoryList.serializer())
    }

    /** Pin the whole download pass to the credentials with which it started. */
    fun boundSyncGateway(): MailSyncGateway {
        val version = AuthStore.sessionVersion
        suspend fun <T> bound(operation: suspend () -> T): T {
            checkSession(version)
            return operation().also { checkSession(version) }
        }
        return object : MailSyncGateway {
            override suspend fun profile() = bound { GmailClient.profile() }
            override suspend fun labels() = bound { GmailClient.labels() }
            override suspend fun list(query: String, page: String?) = bound { GmailClient.list(query, page) }
            override suspend fun metadata(id: String) = bound { GmailClient.metadata(id) }
            override suspend fun message(id: String) = bound { GmailClient.message(id) }
            override suspend fun attachment(id: String, attachmentId: String) = bound {
                GmailClient.attachment(id, attachmentId)
            }
            override suspend fun history(start: String, page: String?) = bound { GmailClient.history(start, page) }
        }
    }

    /**
     * Body-only fetch for the background unsubscribe scan (mirrors _BODY_FIELDS:
     * text parts at the same nesting depth as the list mask, no headers).
     */
    suspend fun messageBodyHtml(id: String): String? {
        waitForIdle()
        val account = AuthStore.account
        if (account.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { OfflineMail.db.message(account, id) }.getOrNull() }
                ?.let { return decodeBody(it.payload).html }
        }
        if (!Connectivity.online.value) return null
        val part = "mimeType,body/data"
        val fields = "id,payload($part,parts($part,parts($part,parts($part))))"
        val m = try {
            get(
                "/messages/$id",
                listOf("format" to "full", "fields" to fields),
                serializer = GMessage.serializer(),
            )
        } catch (e: IOException) {
            return null
        }
        return decodeBody(m.payload).html
    }

    // Gmail's "Show original": the full RFC 822 source, every header included
    suspend fun rawMessage(id: String): String {
        val r = get(
            "/messages/$id",
            listOf("format" to "raw"),
            tries = 3,
            maxBackoff = 4.0,
            serializer = GRawMessage.serializer(),
        )
        val bytes = r.raw?.let { base64UrlDecode(it) } ?: throw GmailException(0, "message had no raw source")
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun attachmentData(messageId: String, attachmentId: String): ByteArray {
        val account = AuthStore.account
        if (account.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { OfflineMail.db.attachment(account, messageId, attachmentId) }.getOrNull()
            }?.let { return it }
        }
        if (!Connectivity.online.value) throw IOException("This attachment has not finished downloading. Connect to download it.")
        val key = "$messageId/$attachmentId"
        cacheLock.withLock { attCache[key] }?.let { return it }
        val bytes = attachment(messageId, attachmentId)
        if (account.isNotBlank()) {
            withContext(Dispatchers.IO) {
                if (OfflineMail.db.hasMessage(account, messageId)) OfflineMail.db.putAttachment(account, messageId, attachmentId, bytes)
            }
        }
        if (bytes.size < ATT_CAP / 4) {
            cacheLock.withLock {
                attBytes -= attCache.remove(key)?.size ?: 0
                attCache[key] = bytes
                attBytes += bytes.size
                while (attBytes > ATT_CAP) {
                    val oldest = attCache.keys.firstOrNull() ?: break
                    attBytes -= attCache.remove(oldest)?.size ?: 0
                }
            }
        }
        return bytes
    }

    override suspend fun attachment(id: String, attachmentId: String): ByteArray {
        val a = get(
            "/messages/$id/attachments/$attachmentId",
            tries = 3,
            maxBackoff = 4.0,
            serializer = GAttachmentBody.serializer(),
        )
        val bytes = a.data?.let { base64UrlDecode(it) }
            ?: throw GmailException(0, "attachment had no data")
        return bytes
    }
}
