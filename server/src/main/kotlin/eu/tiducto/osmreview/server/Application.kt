package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.rules.MirrorUnavailableException
import eu.tiducto.osmreview.rules.UnknownRuleException
import eu.tiducto.osmreview.server.api.apiRoutes
import eu.tiducto.osmreview.server.core.NotAuthorizedException
import eu.tiducto.osmreview.server.core.NotFoundException
import eu.tiducto.osmreview.server.core.StateException
import eu.tiducto.osmreview.server.mcp.mcpRoutes
import eu.tiducto.osmreview.server.osm.OsmApiException
import eu.tiducto.osmreview.server.remote.remoteControlRoutes
import eu.tiducto.osmreview.shared.ApiError
import eu.tiducto.osmreview.shared.UiEvent
import eu.tiducto.osmreview.shared.WireJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.localPort
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("eu.tiducto.osmreview.server")

fun main() {
    val config = Config.fromEnv()
    val app = App(config)
    app.start()
    val host = System.getenv("BIND_HOST") ?: "127.0.0.1"
    log.info("OSM Review Editor {} on http://{}:{} (remote control :{})", config.version, host, config.port, config.remoteControlPort)
    embeddedServer(
        Netty,
        configure = {
            connector {
                this.host = host
                port = config.port
            }
            connector {
                this.host = host
                port = config.remoteControlPort
            }
        },
    ) { module(app) }.apply {
        Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    }.start(wait = true)
}

fun Application.module(app: App) {
    install(ContentNegotiation) { json(WireJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        contentConverter = KotlinxWebsocketSerializationConverter(WireJson)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") || it.request.path().startsWith("/mcp") }
    }
    install(StatusPages) {
        exception<Throwable> { call, e ->
            val (status, msg) = when (e) {
                is NotFoundException -> HttpStatusCode.NotFound to e.message
                is UnknownRuleException -> HttpStatusCode.NotFound to e.message
                is StateException -> HttpStatusCode.Conflict to e.message
                is NotAuthorizedException -> HttpStatusCode.Unauthorized to e.message
                is MirrorUnavailableException -> HttpStatusCode.ServiceUnavailable to e.message
                is IllegalArgumentException, is BadRequestException, is SerializationException ->
                    HttpStatusCode.BadRequest to (e.cause?.message?.takeIf { e is BadRequestException } ?: e.message)
                is OsmApiException -> HttpStatusCode.BadGateway to e.message
                else -> {
                    log.error("Unhandled error on {}", call.request.path(), e)
                    HttpStatusCode.InternalServerError to e.message
                }
            }
            call.respond(status, ApiError(status.description, msg))
        }
    }

    routing {
        localPort(app.config.remoteControlPort) { remoteControlRoutes(app) }

        localPort(app.config.port) {
            install(eu.tiducto.osmreview.server.api.LocalOnly) {
                allowedHosts = allowedHosts + io.ktor.http.Url(app.config.publicUrl).host
            }
            apiRoutes(app)
            mcpRoutes(app)
            webSocket("/api/events") {
                val queued = app.events.connect()
                try {
                    queued.forEach { sendSerialized<UiEvent>(it) }
                    val job = launch { app.events.events.collect { sendSerialized<UiEvent>(it) } }
                    for (frame in incoming) {  }
                    job.cancel()
                } finally {
                    app.events.disconnect()
                }
            }
            route("/api/{path...}") { handle { call.respond(HttpStatusCode.NotFound, ApiError("Not Found", call.request.path())) } }
            staticResources("/", "static") { default("index.html") }
        }
    }
}
