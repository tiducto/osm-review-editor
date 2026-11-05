package eu.tiducto.osmreview.rules

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

data class GolHeader(val revision: Long, val timestamp: Instant?, val url: String?) {
    companion object {
        private const val SIZE = 512
        private const val ACTIVE_SNAPSHOT = 17
        private const val SNAPSHOTS = 128
        private const val SNAPSHOT_SIZE = 64
        private const val URL_LENGTH = 256
        private const val URL = 257

        fun read(file: Path): GolHeader? = runCatching {
            FileChannel.open(file, StandardOpenOption.READ).use { ch ->
                val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
                while (buf.hasRemaining()) if (ch.read(buf, buf.position().toLong()) < 0) break
                if (buf.position() < SIZE) return null
                parse(buf)
            }
        }.getOrNull()

        fun parse(buf: ByteBuffer): GolHeader {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val active = b.get(ACTIVE_SNAPSHOT).toInt() and 1
            val base = SNAPSHOTS + active * SNAPSHOT_SIZE
            val revision = b.getInt(base).toLong() and 0xffffffffL
            val millis = b.getLong(base + 8)
            val len = b.get(URL_LENGTH).toInt() and 0xff
            val url = if (len == 0) null else ByteArray(len).also { arr -> for (i in 0 until len) arr[i] = b.get(URL + i) }.decodeToString()
            val ts = if (millis in MIN_MILLIS..MAX_MILLIS) Instant.ofEpochMilli(millis) else null
            return GolHeader(revision, ts, url)
        }

        private const val MIN_MILLIS = 946684800000L
        private const val MAX_MILLIS = 32503680000000L
    }
}
