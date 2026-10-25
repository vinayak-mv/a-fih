package app.afih.desktop

import app.afih.desktop.index.*
import app.afih.desktop.metadata.*
import app.afih.desktop.playback.*
import app.afih.desktop.telegram.TelegramFailure
import app.afih.desktop.telegram.TelegramSession
import app.afih.model.*
import app.afih.telegram.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import app.afih.desktop.telegram.*

/** Network and database operations are called from UI coroutines on Dispatchers.IO. */
class LibraryService(private val directory: Path) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Pair<TelegramProvider, TelegramSession>>()
    private val sessionLock = Mutex()
    private val scans = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var closed = false
    private val index by lazy { MediaIndex(directory.resolve("media.sqlite")) }
    val personal by lazy { LibraryStateStore(directory.resolve("media.sqlite")) }
    private val syncStore by lazy { SyncStore(directory.resolve("media.sqlite")) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sourceLocks = ConcurrentHashMap<String,Mutex>()
    private val automaticJobs = ConcurrentHashMap<String,Job>()
    private val pausedSources = ConcurrentHashMap.newKeySet<String>()
    private val launchedSources = ConcurrentHashMap.newKeySet<String>()
    private val firstSeen = ConcurrentHashMap<String,Long>()
    private val pendingLocks = ConcurrentHashMap<String,Mutex>()
    private val slots = Semaphore(2)
    @Volatile private var configuration = AppConfiguration()
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    private val mutableSync = MutableStateFlow<Map<String,ScanUi>>(emptyMap())
    val sync = mutableSync.asStateFlow()

    init { background.launch {
        while (isActive) {
            withTimeoutOrNull(15_000) { wake.receive() }
            try { synchronize() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The source status contains operation failures; retry on the next tick. */ }
        }
    } }

    fun configure(value: AppConfiguration) {
        val previous = configuration
        configuration = value
        automaticJobs.filterKeys { id -> value.sources.find { it.id == id } != previous.sources.find { it.id == id } }.values.forEach { it.cancel() }
        wake.trySend(Unit)
    }
    private fun changed() { mutableRevision.update { it + 1 } }
    private fun status(source: TelegramSource, through: Long, running: Boolean, message: String) {
        mutableSync.update { it + (source.id to ScanUi(through,running,message)) }
    }
    fun pauseScans(sourceId: String) { pausedSources.add(sourceId); automaticJobs[sourceId]?.cancel() }
    fun setPersonal(files: List<CatalogFile>, field: String, value: Boolean) {
        personal.set(files.map { it.media.key },field,value); changed()
    }
    fun removeMedia(key: MessageKey) { personal.set(listOf(key),"hidden",true); personal.clearIssue(key); changed() }
    fun issues() = personal.issues()
    suspend fun retryIssue(config: AppConfiguration, issue: MediaIssue) {
        val media = index.get(issue.key) ?: throw IllegalArgumentException("This item is no longer indexed. Remove it from the issues list.")
        play(config,media)
    }
    private fun recordIssue(media: IndexedMessage, failure: Throwable) {
        personal.issue(media,(failure as? TelegramFailure)?.kind ?: if(failure is TimeoutCancellationException) "NETWORK" else "PLAYBACK",userFacingFailure(failure)); changed()
    }
    val metadata by lazy { MetadataService(MetadataStore(directory.resolve("media.sqlite"))) }
    private val playbackStore by lazy { PlaybackStore(directory.resolve("media.sqlite")) }
    private val playbackLock = Mutex()
    private val mutablePlayback = MutableStateFlow<PlaybackSession?>(null)
    internal val playback = mutablePlayback.asStateFlow()

    suspend fun play(configuration: AppConfiguration, media: IndexedMessage) = playbackLock.withLock {
        check(!closed)
        closePlayback()
        val provider = configuration.providers.firstOrNull { it.id == media.key.providerId }
            ?: throw IllegalArgumentException("The provider for this file has been removed.")
        val source = try { session(provider).openMedia(media) } catch (failure: Exception) {
            if (failure !is CancellationException || failure is TimeoutCancellationException) recordIssue(media,failure)
            throw failure
        }
        try {
            currentCoroutineContext().ensureActive()
            mutablePlayback.value = PlaybackSession.open(media, source, playbackStore,
                onFailure = { recordIssue(media,it) }, onHealthy = { personal.clearIssue(media.key); changed() })
        } catch (failure: Throwable) {
            withContext(NonCancellable) { source.close() }
            throw failure
        }
    }

    internal suspend fun stopPlayback() = playbackLock.withLock { closePlayback() }

    private suspend fun closePlayback() {
        mutablePlayback.value?.close()
        mutablePlayback.value = null
        changed()
    }

    fun catalog(configuration: AppConfiguration, libraryId: String? = null): List<CatalogFile> {
        val sourceIds = configuration.sources.filter { libraryId == null || libraryId in it.libraryIds }.map { it.id }.toSet()
        val files = buildList {
            var offset = 0
            do {
                val batch = index.list(sourceIds, offset = offset)
                addAll(batch)
                offset += batch.size
            } while (batch.size == 500)
        }
        val memberships = index.sourceMemberships(sourceIds)
        val states = personal.states()
        return metadata.catalog(files.filter { states[it.key]?.hidden != true }, configuration.tmdb).map { entry ->
            val libraries = configuration.sources.filter { it.id in memberships[entry.media.key].orEmpty() }.flatMap { it.libraryIds }.toSet()
            entry.copy(libraryKinds = configuration.libraries.filter { it.id in libraries }.map { it.kind }.toSet(),personal = states[entry.media.key] ?: PersonalState())
        }
    }

    private suspend fun session(provider: TelegramProvider): TelegramSession = sessionLock.withLock {
        check(!closed) { "Application is closing." }
        val remaining = syncStore.cooldown(provider.id) - System.currentTimeMillis()
        if (remaining > 0) throw TelegramFailure("Telegram requested a pause. Retry in ${(remaining / 1000).coerceAtLeast(1)} seconds.","RATE_LIMIT",true,remaining)
        val previous = sessions[provider.id]
        if (previous != null && previous.first.credentials == provider.credentials && previous.second.isOpen) return@withLock previous.second
        previous?.second?.close()
        sessions.remove(provider.id)
        val created = TelegramSession(provider, directory.resolve("telegram").resolve(provider.id),
            onUpdate = { update -> receiveUpdate(provider.id,update) },
            onThrottle = { wait -> syncStore.deferProvider(provider.id,System.currentTimeMillis()+wait) })
        try {
            created.connect()
            if (closed) throw TelegramFailure("Application is closing.")
            sessions[provider.id] = provider to created
            created
        } catch (failure: Throwable) {
            created.close()
            throw failure
        }
    }

    suspend fun testConnection(provider: TelegramProvider) { session(provider) }

    suspend fun createSource(
        provider: TelegramProvider, name: String, startLink: String, endLink: String,
        topicOnly: Boolean, libraryIds: Set<String>, parsingMode: ParsingMode?,
    ): TelegramSource {
        val range = MessageRange.fromLinks(startLink, endLink)
        require(!topicOnly || range.topicId != null) { "Use topic message links when selecting a specific topic." }
        require(libraryIds.isNotEmpty()) { "Select at least one library." }
        val (chat, chatTitle) = session(provider).resolveChat(range.chat)
        return TelegramSource(
            UUID.randomUUID().toString(), provider.id, name.trim().ifBlank { chatTitle }, chat,
            range.firstId, range.lastId, if (topicOnly) range.topicId else null,
            parsingMode, libraryIds, startLink.trim(), endLink.trim(),
        )
    }

    fun signature(source: TelegramSource, provider: TelegramProvider): String = "sync2|${source.providerId}|${source.chat}|${source.firstId}|${source.topicId}|${source.parsingMode ?: provider.parsingMode}"

    fun checkpoint(source: TelegramSource, provider: TelegramProvider): Long = index.checkpoint(source, signature(source, provider),legacySignature(source,provider))
    private fun legacySignature(source: TelegramSource, provider: TelegramProvider) = "${source.scanSignature}|${source.parsingMode ?: provider.parsingMode}"

    suspend fun scan(provider: TelegramProvider, source: TelegramSource, onProgress: suspend (Long) -> Unit) {
        pausedSources.remove(source.id)
        scanLocked(provider,source,onProgress)
    }
    private suspend fun scanLocked(provider: TelegramProvider, source: TelegramSource, onProgress: suspend (Long) -> Unit) = sourceLocks.computeIfAbsent(source.id) { Mutex() }.withLock {
        scans.add(source.id)
        var through = source.firstId - 1
        try {
            val connected = session(provider)
            // Check permissions even for an already-completed range before reporting success.
            connected.resolveChat(source.chat)
            val latest = if (source.automaticUpdates) maxOf(syncStore.latest(provider.id,source.chat),connected.latestKnown(source.chat)) else 0
            if (latest > 0) syncStore.watermark(provider.id,source.chat,latest)
            val effective = source.copy(lastId = maxOf(source.lastId,latest))
            val effectiveParsing = source.parsingMode ?: provider.parsingMode
            val scanSignature = signature(source, provider)
            val scanner = RangeScanner(
                MessageGateway { _, chat, ids -> currentCoroutineContext().ensureActive(); connected.fetch(chat, ids, effectiveParsing) },
                ScanBatchStore { batch ->
                    currentCoroutineContext().ensureActive()
                    index.commit(effective, scanSignature, batch)
                    through = batch.scannedThrough
                    status(source,through,true,"Scanning through message $through…")
                    changed()
                    onProgress(batch.scannedThrough)
                },
            )
            through = index.checkpoint(effective,scanSignature,legacySignature(source,provider))
            status(source,through,true,"Checking source…")
            scanner.scan(provider.id, MessageRange(source.chat, source.firstId, effective.lastId, source.topicId), through)
            through = effective.lastId
            // Also migrate an already-complete legacy checkpoint when no batches were needed.
            index.commit(effective,scanSignature,ScanBatch(emptyList(),through))
            syncStore.success(source.id,System.currentTimeMillis())
            status(source,through,false,"Up to date through message $through")
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) {
                status(source,through,false,"Paused. Saved scan progress is retained."); throw failure
            }
            val attempts = syncStore.run(source.id).failures
            val wait = (failure as? TelegramFailure)?.retryAfterMs?.takeIf { it > 0 } ?: (30_000L shl attempts.coerceAtMost(6)).coerceAtMost(1_800_000)
            syncStore.failure(source.id,System.currentTimeMillis()+wait)
            status(source,through,false,"${userFacingFailure(failure)} Automatic retry is scheduled.")
            throw failure
        } finally { scans.remove(source.id) }
    }

    private fun receiveUpdate(providerId: String, update: JsonObject) {
        try {
            if(journalUpdate(syncStore,providerId,configuration.sources.filter { it.providerId == providerId }.map { it.chat }.toSet(),update)) wake.trySend(Unit)
        } catch (_: Exception) {
            configuration.sources.filter { it.providerId == providerId }.forEach { status(it,it.firstId-1,false,"An update could not be saved. Check available disk space and retry the source scan.") }
        }
    }

    private suspend fun synchronize() {
        val snapshot = configuration
        for (source in snapshot.sources) {
            if (source.id in pausedSources || automaticJobs[source.id]?.isActive == true || source.id in scans) continue
            val run = syncStore.run(source.id)
            val now = System.currentTimeMillis()
            val provider = snapshot.providers.find { it.id == source.providerId } ?: continue
            val pending = source.automaticUpdates && syncStore.pending(provider.id).any { it.key.chat == source.chat }
            val shouldScan = syncDue(source,run,now,firstSeen.computeIfAbsent(source.id) { now },source.id in launchedSources,
                syncStore.latest(provider.id,source.chat),index.checkpoint(source.copy(lastId=Int.MAX_VALUE.toLong()),signature(source,provider),legacySignature(source,provider)),pending,syncStore.cooldown(provider.id))
            if (shouldScan) {
                launchedSources.add(source.id)
                val job = background.launch(start = CoroutineStart.LAZY) {
                    slots.withPermit {
                        try {
                            scanLocked(provider,source) {}
                            if (source.automaticUpdates) refreshPending(snapshot,provider)
                            if(snapshot.tmdb != null) {
                                try { metadata.enrich(snapshot,catalog(snapshot)); changed() }
                                catch (failure: Exception) { if(failure is CancellationException) throw failure; status(source,source.lastId,false,"Source updated. ${userFacingFailure(failure)}") }
                            }
                        } catch (cancelled: CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
                        catch (failure: Exception) {
                            if(syncStore.run(source.id).retryAt <= System.currentTimeMillis()) {
                                syncStore.failure(source.id,System.currentTimeMillis()+((failure as? TelegramFailure)?.retryAfterMs?.takeIf { it > 0 } ?: 60_000))
                                status(source,source.lastId,false,"${userFacingFailure(failure)} Automatic retry is scheduled.")
                            }
                        }
                    }
                }
                automaticJobs[source.id] = job; job.start()
            } else if (source.automaticUpdates && sessions[provider.id]?.second?.isOpen != true && now >= maxOf(run.retryAt,syncStore.cooldown(provider.id))) {
                // Open live subscriptions even when launch/scheduled scans are disabled.
                try { session(provider) } catch (failure: Exception) {
                    if(failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    syncStore.failure(source.id,now+60_000)
                    status(source,source.firstId-1,false,userFacingFailure(failure))
                }
            }
        }
    }

    private suspend fun refreshPending(snapshot: AppConfiguration, provider: TelegramProvider) = pendingLocks.computeIfAbsent(provider.id) { Mutex() }.withLock {
        val connected = session(provider)
        for (item in syncStore.pending(provider.id)) {
            val sources = snapshot.sources.filter { it.providerId == provider.id && it.chat == item.key.chat && it.automaticUpdates }
            if (sources.isEmpty()) continue
            val old = index.get(item.key)
            if (item.kind != "refresh") {
                if(old != null) { personal.issue(old,if(item.kind == "deleted") "MISSING" else "ACCESS",if(item.kind == "deleted") "Telegram reported that this message was deleted." else "Telegram reported that this message is inaccessible."); changed() }
            } else {
                var matched = false
                var unavailable = false
                val memberships = index.sourceMemberships(sources.map { it.id }.toSet())[item.key].orEmpty()
                for (source in sources) {
                    if(item.key.messageId < source.firstId && source.id !in memberships) continue
                    val refreshed = try { connected.refresh(source.chat,item.key.messageId,source.parsingMode ?: provider.parsingMode) }
                    catch (failure: TelegramFailure) { if(failure.kind == "MISSING" || failure.kind == "ACCESS") { unavailable=true; if(old != null) recordIssue(old,failure); null } else throw failure }
                    if(refreshed != null && (source.topicId == null || source.topicId == refreshed.topicId)) {
                        matched = true
                        val effective = source.copy(firstId=minOf(source.firstId,item.key.messageId),lastId=maxOf(source.lastId,item.key.messageId))
                        sourceLocks.computeIfAbsent(source.id) { Mutex() }.withLock { index.commit(effective,signature(source,provider),ScanBatch(listOf(refreshed),effective.lastId),advanceProgress=false) }
                        personal.clearIssue(item.key); changed()
                    }
                }
                if (!matched && !unavailable && old != null) { personal.issue(old,"MEDIA_CHANGED","This message no longer contains media for this source. Check the message and source access."); changed() }
            }
            syncStore.acknowledge(item)
        }
    }

    fun media(sources: List<TelegramSource>, libraryId: String? = null, query: String = ""): List<IndexedMessage> =
        index.list(sources.filter { libraryId == null || libraryId in it.libraryIds }.map { it.id }.toSet(), query = query)

    suspend fun disconnect(providerId: String) = playbackLock.withLock {
        closePlayback()
        sessionLock.withLock { sessions.remove(providerId)?.second?.close(); Unit }
    }

    suspend fun resetSession(providerId: String) = playbackLock.withLock {
        closePlayback()
        sessionLock.withLock {
            sessions[providerId]?.second?.close()
            sessions.remove(providerId)
            UUID.fromString(providerId)
            val root = directory.resolve("telegram").toAbsolutePath().normalize()
            val sessionPath = root.resolve(providerId).normalize()
            check(sessionPath.parent == root)
            if (Files.exists(sessionPath)) Files.walk(sessionPath).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
    fun removeSource(sourceId: String) { check(sourceId !in scans) { "Pause the scan before removing its source." }; index.removeSource(sourceId) }

    override fun close() {
        closed = true
        background.cancel()
        runBlocking { stopPlayback() }
        sessions.values.forEach { runCatching { it.second.close() } }
        sessions.clear()
    }
}

/** Do not surface database paths, native request payloads, or credentials. */
fun userFacingFailure(failure: Throwable): String = when (failure) {
    is PlaybackFailure -> failure.message ?: "Playback could not start."
    is MetadataFailure -> failure.message ?: "Metadata could not be loaded."
    is TelegramFailure -> failure.message ?: "Telegram operation failed."
    is TimeoutCancellationException -> "Telegram timed out. Check your connection and retry."
    is IllegalArgumentException -> failure.message ?: "Check the source settings."
    else -> "The operation could not finish. Check your connection, available storage, and source permissions, then retry."
}
