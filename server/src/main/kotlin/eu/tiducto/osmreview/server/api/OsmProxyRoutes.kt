package eu.tiducto.osmreview.server.api

import eu.tiducto.osmreview.server.osm.MapTooLargeException
import eu.tiducto.osmreview.server.osm.OsmApi
import eu.tiducto.osmreview.server.osm.OsmApiException
import eu.tiducto.osmreview.shared.ApiError
import eu.tiducto.osmreview.shared.ApiErrorCodes
import eu.tiducto.osmreview.shared.BBox
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("eu.tiducto.osmreview.server.api.OsmProxy")

const val MAX_MAP_AREA = 0.25

fun Route.osmProxyRoutes(maxBytes: Long = OsmApi.MAP_MAX_BYTES, api: () -> OsmApi) {
    get("/api/osm/map") {
        val bbox = parseMapBBox(call.request.queryParameters["bbox"])
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    HttpStatusCode.BadRequest.description,
                    "bbox must be west,south,east,north: finite numbers, west < east, south < north, longitudes within ±180, latitudes within ±90",
                ),
            )
        val area = (bbox.east - bbox.west) * (bbox.north - bbox.south)
        if (area > MAX_MAP_AREA) {
            return@get call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(
                    HttpStatusCode.PayloadTooLarge.description,
                    "Area too large, zoom in (${"%.4f".format(area)} square degrees, at most $MAX_MAP_AREA)",
                    ApiErrorCodes.AREA_TOO_LARGE,
                ),
            )
        }
        val xml = try {
            api().map(bbox, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MapTooLargeException) {
            return@get call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(HttpStatusCode.PayloadTooLarge.description, "Area too large, zoom in (${e.message})", ApiErrorCodes.AREA_TOO_LARGE),
            )
        } catch (e: OsmApiException) {
            return@get call.osmError(e)
        } catch (e: Exception) {
            log.warn("OSM map request for {} failed", bbox, e)
            return@get call.respond(
                HttpStatusCode.BadGateway,
                ApiError(HttpStatusCode.BadGateway.description, "Could not reach the OSM API: ${e.message ?: e::class.simpleName}"),
            )
        }
        call.respondText(xml, ContentType.Application.Xml)
    }
}

private suspend fun ApplicationCall.osmError(e: OsmApiException) {
    val message = e.body.trim().take(500).ifBlank { e.status.description }
    val status = when (e.status.value) {
        400 -> HttpStatusCode.BadRequest
        429 -> HttpStatusCode.TooManyRequests
        509 -> HttpStatusCode.ServiceUnavailable
        else -> HttpStatusCode.BadGateway
    }
    respond(status, ApiError(status.description, "OSM API ${e.status.value}: $message", ApiErrorCodes.OSM_API_ERROR))
}

fun parseMapBBox(raw: String?): BBox? {
    val parts = raw?.split(",")?.map { it.trim().toDoubleOrNull() } ?: return null
    if (parts.size != 4 || parts.any { it == null || !it.isFinite() }) return null
    val (west, south, east, north) = parts.map { it!! }
    if (west >= east || south >= north) return null
    if (west < -180 || east > 180 || south < -90 || north > 90) return null
    return BBox(west, south, east, north)
}
