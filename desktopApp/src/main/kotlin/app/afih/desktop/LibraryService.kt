package app.afih.desktop

import app.afih.desktop.index.MediaIndex
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Network and database operations are called from UI coroutines on Dispatchers.IO. */
class LibraryService(private val directory: Path) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, Pair<TelegramProvider, TelegramSession>>()
    private val sessionLock = Mutex()
    private val scans = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var closed = false
    private val index by lazy { MediaIndex(directory.resolve("media.sqlite")) }
    val metadata by lazy { MetadataService(MetadataStore(directory.resolve("media.sqlite"))) }
    private val playbackStore by lazy { PlaybackStore(directory.resolve("media.sqlite")) }
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackLock = Mutex()
    private var player: PlayerWindow? = null

    suspend fun play(configuration: AppConfiguration, media: IndexedMessage) = playbackLock.withLock {
        check(!closed)
        player?.close(); player = null
        val provider = configuration.providers.firstOrNull { it.id == media.key.providerId }
            ?: throw IllegalArgumentException("The provider for this file has been removed.")
        val source = session(provider).openMedia(media)
        var opened: PlayerWindow? = null
        try {
            opened = withContext(NonCancellable) {
                PlayerWindow.open(media, source, playbackStore) { playbackScope.launch { stopPlayback() } }
            }
            currentCoroutineContext().ensureActive()
            player = opened
        } catch (failure: Throwable) {
            withContext(NonCancellable) { if (opened != null) opened.close() else source.close() }
            throw failure
        }
    }

    private suspend fun stopPlayback() = playbackLock.withLock { player?.close(); player = null }

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
        return metadata.catalog(files, configuration.tmdb).map { entry ->
            val libraries = configuration.sources.filter { it.id in memberships[entry.media.key].orEmpty() }.flatMap { it.libraryIds }.toSet()
            entry.copy(libraryKinds = configuration.libraries.filter { it.id in libraries }.map { it.kind }.toSet())
        }
    }

    private suspend fun session(provider: TelegramProvider): TelegramSession = sessionLock.withLock {
        check(!closed) { "Application is closing." }
        val previous = sessions[provider.id]
        if (previous != null && previous.first.credentials == provider.credentials) return@withLock previous.second
        previous?.second?.close()
        sessions.remove(provider.id)
        val created = TelegramSession(provider, directory.resolve("telegram").resolve(provider.id))
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

    fun signature(source: TelegramSource, provider: TelegramProvider): String = "${source.scanSignature}|${source.parsingMode ?: provider.parsingMode}"

    fun checkpoint(source: TelegramSource, provider: TelegramProvider): Long = index.checkpoint(source, signature(source, provider))

    suspend fun scan(provider: TelegramProvider, source: TelegramSource, onProgress: suspend (Long) -> Unit) {
        check(scans.add(source.id)) { "This source is already being scanned." }
        try {
            val connected = session(provider)
            // Check permissions even for an already-completed range before reporting success.
            connected.resolveChat(source.chat)
            val effectiveParsing = source.parsingMode ?: provider.parsingMode
            val scanSignature = signature(source, provider)
            val scanner = RangeScanner(
                MessageGateway { _, chat, ids -> currentCoroutineContext().ensureActive(); connected.fetch(chat, ids, effectiveParsing) },
                ScanBatchStore { batch ->
                    currentCoroutineContext().ensureActive()
                    index.commit(source, scanSignature, batch)
                    onProgress(batch.scannedThrough)
                },
            )
            scanner.scan(provider.id, MessageRange(source.chat, source.firstId, source.lastId, source.topicId), index.checkpoint(source, scanSignature))
        } finally { scans.remove(source.id) }
    }

    fun media(sources: List<TelegramSource>, libraryId: String? = null, query: String = ""): List<IndexedMessage> =
        index.list(sources.filter { libraryId == null || libraryId in it.libraryIds }.map { it.id }.toSet(), query = query)

    suspend fun disconnect(providerId: String) {
        stopPlayback()
        sessionLock.withLock { sessions.remove(providerId)?.second?.close(); Unit }
    }

    suspend fun resetSession(providerId: String) {
        stopPlayback()
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
        runBlocking { stopPlayback() }
        playbackScope.cancel()
        sessions.values.forEach { runCatching { it.second.close() } }
        sessions.clear()
    }
}

/** Do not surface database paths, native request payloads, or credentials. */
fun userFacingFailure(failure: Throwable): String = when (failure) {
    is MetadataFailure -> failure.message ?: "Metadata could not be loaded."
    is TelegramFailure -> failure.message ?: "Telegram operation failed."
    is TimeoutCancellationException -> "Telegram timed out. Check your connection and retry."
    is IllegalArgumentException -> failure.message ?: "Check the source settings."
    else -> "The operation could not finish. Check your connection, available storage, and source permissions, then retry."
}
