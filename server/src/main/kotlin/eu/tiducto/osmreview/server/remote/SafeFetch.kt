package eu.tiducto.osmreview.server.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RefusedTargetException(message: String) : IllegalArgumentException(message)

class RemoteFetchException(message: String) : IllegalStateException(message)

object AddressPolicy {
    private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback")

    fun reason(a: InetAddress): String? = when (a) {
        is Inet4Address -> v4Reason(a.address)
        is Inet6Address -> v6Reason(a.address)
        else -> "unknown"
    }

    fun isPublic(a: InetAddress): Boolean = reason(a) == null

    private fun v4Reason(b: ByteArray): String? {
        val o = b.map { it.toInt() and 0xff }
        return when {
            o[0] == 0 -> "unspecified"
            o[0] == 127 -> "loopback"
            o[0] == 10 -> "private"
            o[0] == 172 && o[1] in 16..31 -> "private"
            o[0] == 192 && o[1] == 168 -> "private"
            o[0] == 100 && o[1] in 64..127 -> "shared (carrier-grade NAT)"
            o[0] == 169 && o[1] == 254 -> "link-local"
            o[0] == 192 && o[1] == 0 && o[2] == 0 -> "reserved"
            o[0] == 198 && o[1] in 18..19 -> "reserved"
            o[0] in 224..239 -> "multicast"
            o[0] >= 240 -> "reserved"
            else -> null
        }
    }

    private fun v6Reason(b: ByteArray): String? {
        val o = b.map { it.toInt() and 0xff }
        val v4 = { from: Int -> v4Reason(b.copyOfRange(from, from + 4)) }
        val zeroTo = { n: Int -> (0 until n).all { o[it] == 0 } }
        return when {
            zeroTo(16) -> "unspecified"
            zeroTo(15) && o[15] == 1 -> "loopback"
            zeroTo(10) && o[10] == 0xff && o[11] == 0xff -> v4(12)?.let { "IPv4-mapped $it" } ?: "IPv4-mapped"
            zeroTo(12) -> "IPv4-compatible"
            o[0] == 0x00 && o[1] == 0x64 && o[2] == 0xff && o[3] == 0x9b -> v4(12)?.let { "NAT64 $it" }
            o[0] == 0x20 && o[1] == 0x02 -> v4(2)?.let { "6to4 $it" }
            o[0] == 0x20 && o[1] == 0x01 && o[2] == 0x00 && o[3] == 0x00 -> "Teredo"
            (o[0] and 0xfe) == 0xfc -> "unique-local"
            o[0] == 0xfe && (o[1] and 0xc0) == 0x80 -> "link-local"
            o[0] == 0xfe && (o[1] and 0xc0) == 0xc0 -> "site-local"
            o[0] == 0xff -> "multicast"
            else -> null
        }
    }

    fun ipv4Literal(host: String): ByteArray? {
        val parts = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { parseNumber(it) ?: return null }
        val last = nums.last()
        if (nums.dropLast(1).any { it > 255 }) return null
        if (last >= 1L shl (8 * (5 - nums.size))) return null
        var value = last
        nums.dropLast(1).forEachIndexed { i, n -> value += n shl (8 * (3 - i)) }
        return byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }

    fun looksNumeric(host: String): Boolean {
        val last = host.trimEnd('.').substringAfterLast('.')
        return last.isNotEmpty() && (last.all { it.isDigit() } || Regex("0[xX][0-9a-fA-F]*").matches(last))
    }

    private fun parseNumber(s: String): Long? {
        val (digits, radix) = when {
            s.startsWith("0x") || s.startsWith("0X") -> s.substring(2) to 16
            s.length > 1 && s.startsWith("0") -> s.substring(1) to 8
            else -> s to 10
        }
        if (digits.isEmpty()) return 0
        if (digits.length > 12) return null
        return digits.toLongOrNull(radix)?.takeIf { it >= 0 }
    }

    fun resolve(host: String, resolver: (String) -> List<InetAddress>, allowed: (InetAddress) -> Boolean = ::isPublic): InetAddress {
        if (host.isEmpty()) throw RefusedTargetException("The URL has no host")
        val addresses = when {
            host.startsWith("[") -> {
                val inner = host.removePrefix("[").removeSuffix("]")
                if ('%' in inner) throw RefusedTargetException("IPv6 zone identifiers are not allowed")
                listOf(runCatching { InetAddress.getByName("[$inner]") }.getOrElse { throw RefusedTargetException("Invalid IPv6 address $host") })
            }
            looksNumeric(host) -> {
                val bytes = ipv4Literal(host) ?: throw RefusedTargetException("Invalid IPv4 address $host")
                listOf(InetAddress.getByAddress(bytes))
            }
            else -> {
                val name = runCatching { IDN.toASCII(host.trimEnd('.')) }.getOrElse { throw RefusedTargetException("Invalid host $host") }.lowercase()
                if (name in LOCAL_NAMES || name.endsWith(".localhost")) throw RefusedTargetException("Refusing to fetch from $host: it names this computer")
                runCatching { resolver(name) }.getOrElse { throw RefusedTargetException("Cannot resolve $host: ${it.message}") }
            }
        }
        if (addresses.isEmpty()) throw RefusedTargetException("Cannot resolve $host")
        val bad = addresses.firstOrNull { !allowed(it) }
        if (bad != null) {
            throw RefusedTargetException("Refusing to fetch from $host: ${bad.hostAddress} is a ${reason(bad) ?: "disallowed"} address")
        }
        return addresses.first()
    }
}

data class FetchTarget(val uri: URI, val host: String, val address: InetAddress, val port: Int, val https: Boolean)

class SafeFetcher(
    private val userAgent: String,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val allowed: (InetAddress) -> Boolean = AddressPolicy::isPublic,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val timeout: Duration = 60.seconds,
    private val maxRedirects: Int = 5,
    private val ssl: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
) {
    data class Parsed(val uri: URI, val host: String, val port: Int, val https: Boolean)

    fun parse(url: String): Parsed {
        val uri = runCatching { URI(url.trim()) }.getOrElse { throw RefusedTargetException("Invalid URL: ${it.message}") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw RefusedTargetException("Only http and https URLs can be imported, not ${scheme ?: "a relative URL"}")
        val authority = uri.rawAuthority ?: throw RefusedTargetException("The URL has no host")
        if (uri.rawUserInfo != null || '@' in authority) throw RefusedTargetException("URLs with a user name or password are not allowed")
        if ('%' in authority) throw RefusedTargetException("Percent-encoded hosts are not allowed")
        val https = scheme == "https"
        val (host, port) = if (uri.host != null) {
            uri.host to uri.port
        } else {
            val m = Regex("""^(\[[^\]]*]|[^:\[\]]*)(?::(\d{1,5}))?$""").matchEntire(authority) ?: throw RefusedTargetException("The URL has no valid host")
            m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: -1)
        }
        if (host.isEmpty()) throw RefusedTargetException("The URL has no host")
        val effective = if (port == -1) (if (https) 443 else 80) else port
        if (effective !in 1..65535) throw RefusedTargetException("Invalid port $port")
        return Parsed(uri, host, effective, https)
    }

    fun target(url: String): FetchTarget {
        val p = parse(url)
        val address = AddressPolicy.resolve(p.host, resolver, allowed)
        return FetchTarget(p.uri, p.host, address, p.port, p.https)
    }

    suspend fun fetch(url: String): String = runInterruptible(Dispatchers.IO) { fetchBlocking(url) }

    private fun fetchBlocking(url: String): String {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var current = url
        for (hop in 0..maxRedirects) {
            val t = target(current)
            val r = exchange(t, deadline)
            if (r.status !in REDIRECTS) {
                if (r.status !in 200..299) throw RemoteFetchException("Could not fetch ${t.uri}: HTTP ${r.status}")
                return String(r.body, r.charset)
            }
            val location = r.headers["location"] ?: throw RemoteFetchException("Redirect from ${t.uri} without a Location")
            current = runCatching { t.uri.resolve(location.trim()).toString() }.getOrElse { throw RefusedTargetException("Invalid redirect to $location") }
        }
        throw RefusedTargetException("Too many redirects (more than $maxRedirects)")
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray, val charset: Charset)

    private fun remaining(deadline: Long): Int {
        val ms = (deadline - System.nanoTime()) / 1_000_000
        if (ms <= 0) throw RemoteFetchException("Fetching took longer than ${timeout.inWholeSeconds} s")
        return ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun exchange(t: FetchTarget, deadline: Long): Response {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(t.address, t.port), remaining(deadline).coerceAtMost(15_000))
            val socket: Socket = if (t.https) {
                val name = t.host.removePrefix("[").removeSuffix("]").trimEnd('.')
                val s = ssl().createSocket(raw, name, t.port, true) as SSLSocket
                val params = s.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                if (!t.host.startsWith("[") && !AddressPolicy.looksNumeric(t.host)) params.serverNames = listOf(SNIHostName(name))
                s.sslParameters = params
                s.soTimeout = remaining(deadline)
                s.startHandshake()
                s
            } else {
                raw
            }
            socket.use { s ->
                val path = (t.uri.rawPath?.ifEmpty { null } ?: "/") + (t.uri.rawQuery?.let { "?$it" } ?: "")
                val defaultPort = if (t.https) 443 else 80
                val hostHeader = if (t.port == defaultPort) t.host else "${t.host}:${t.port}"
                val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: $userAgent\r\n" +
                    "Accept: application/xml, text/xml, */*;q=0.5\r\nAccept-Encoding: gzip, identity\r\nConnection: close\r\n\r\n"
                s.soTimeout = remaining(deadline)
                s.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
                return read(s, s.getInputStream().buffered(), deadline)
            }
        } catch (e: SocketTimeoutException) {
            throw RemoteFetchException("Fetching ${t.uri} timed out")
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun read(s: Socket, input: InputStream, deadline: Long): Response {
        val statusLine = line(s, input, deadline) ?: throw RemoteFetchException("Empty response")
        val status = Regex("""^HTTP/1\.[01] (\d{3})""").find(statusLine)?.groupValues?.get(1)?.toInt()
            ?: throw RemoteFetchException("Not an HTTP/1.x response")
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val l = line(s, input, deadline) ?: throw RemoteFetchException("Truncated headers")
            if (l.isEmpty()) break
            if (++count > MAX_HEADERS) throw RemoteFetchException("Too many response headers")
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        if (status in REDIRECTS || status == 204 || status == 304) return Response(status, headers, ByteArray(0), Charsets.UTF_8)
        val raw = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> chunked(s, input, deadline)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.toLongOrNull() ?: throw RemoteFetchException("Invalid Content-Length")
                if (n > maxBytes) throw RefusedTargetException("The response is larger than ${maxBytes / 1024 / 1024} MiB")
                exactly(s, input, n.toInt(), deadline)
            }
            else -> untilEof(s, input, deadline)
        }
        val encoding = headers["content-encoding"]?.lowercase()?.trim()
        val body = when (encoding) {
            null, "", "identity" -> raw
            "gzip", "x-gzip" -> capped(GZIPInputStream(ByteArrayInputStream(raw)))
            else -> throw RemoteFetchException("Unsupported Content-Encoding $encoding")
        }
        val charset = headers["content-type"]?.let { Regex("""charset=["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return Response(status, headers, body, charset)
    }

    private fun tooLarge(): Nothing = throw RefusedTargetException("The response is larger than ${maxBytes / 1024 / 1024} MiB")

    private fun capped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) tooLarge()
            }
        }
        return out.toByteArray()
    }

    private fun line(s: Socket, input: InputStream, deadline: Long): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            s.soTimeout = remaining(deadline)
            val c = input.read()
            if (c < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
            if (c == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
            out.write(c)
            if (out.size() > MAX_LINE) throw RemoteFetchException("Response header line too long")
        }
    }

    private fun exactly(s: Socket, input: InputStream, n: Int, deadline: Long): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            s.soTimeout = remaining(deadline)
            val r = input.read(out, off, minOf(BUFFER, n - off))
            if (r < 0) throw RemoteFetchException("Truncated response body")
            off += r
        }
        return out
    }

    private fun untilEof(s: Socket, input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            s.soTimeout = remaining(deadline)
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > maxBytes) tooLarge()
        }
        return out.toByteArray()
    }

    private fun chunked(s: Socket, input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = line(s, input, deadline) ?: throw RemoteFetchException("Truncated chunked body")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: throw RemoteFetchException("Invalid chunk size")
            if (size == 0L) {
                while (!line(s, input, deadline).isNullOrEmpty()) Unit
                return out.toByteArray()
            }
            if (out.size() + size > maxBytes) tooLarge()
            out.write(exactly(s, input, size.toInt(), deadline))
            line(s, input, deadline)
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_HEADERS = 100
        const val MAX_LINE = 16 * 1024
        const val BUFFER = 64 * 1024
    }
}
