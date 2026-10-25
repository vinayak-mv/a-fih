package app.afih.desktop.playback

internal class PlaybackFailure(message: String) : Exception(message)

/** Bounded reads; offsets and lengths stay Long for files larger than 2 GiB. */
internal interface MediaByteSource {
    val size: Long
    suspend fun read(offset: Long, count: Int): ByteArray
    suspend fun close()
}

internal data class ByteRange(val first: Long, val last: Long) {
    val length: Long get() = last - first + 1
}

/** Only a single byte range is supported. Invalid/unsatisfiable ranges return HTTP 416. */
internal fun parseRange(header: String?, size: Long): ByteRange {
    require(size > 0)
    if (header == null) return ByteRange(0, size - 1)
    require(header.startsWith("bytes=") && ',' !in header)
    val parts = header.removePrefix("bytes=").split('-', limit = 2)
    require(parts.size == 2)
    if (parts[0].isEmpty()) {
        val suffix = parts[1].toLongOrNull()
        require(suffix != null && suffix > 0)
        return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
    }
    val first = parts[0].toLongOrNull()
    val requestedLast = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull()
    require(first != null && first in 0 until size && requestedLast != null && requestedLast >= first)
    return ByteRange(first, requestedLast.coerceAtMost(size - 1))
}
