package eu.tiducto.osmreview.server.tiles

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val MVT = ContentType("application", "vnd.mapbox-vector-tile")

fun Route.tileRoutes(tiles: TileService) {
    get("/api/tiles/{z}/{x}/{y}.mvt") {
        val z = call.parameters["z"]?.toIntOrNull() ?: throw IllegalArgumentException("z must be an integer")
        val x = call.parameters["x"]?.toIntOrNull() ?: throw IllegalArgumentException("x must be an integer")
        val y = call.parameters["y"]?.toIntOrNull() ?: throw IllegalArgumentException("y must be an integer")
        val keys = TileContent.extraKeys(call.request.queryParameters["keys"], z)
        val etag = tiles.etag(z, x, y, extraKeys = keys)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        if (call.request.header(HttpHeaders.IfNoneMatch) == etag) {
            call.response.header(HttpHeaders.ETag, etag)
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        val result = withContext(Dispatchers.Default) { tiles.tile(z, x, y, keys) }
        call.response.header(HttpHeaders.ETag, result.etag)
        call.response.header("Server-Timing", "build;dur=${result.buildMs};desc=\"${if (result.cached) "cache" else "built"} ${result.features}\"")
        val gzip = call.request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true
        if (gzip) {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respondBytes(result.gzipped, MVT)
        } else {
            call.respondBytes(GZIPInputStream(ByteArrayInputStream(result.gzipped)).readBytes(), MVT)
        }
    }
}
