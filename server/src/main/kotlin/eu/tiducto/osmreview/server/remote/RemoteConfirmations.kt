package eu.tiducto.osmreview.server.remote

import eu.tiducto.osmreview.server.core.EventBus
import eu.tiducto.osmreview.shared.RemoteRequest
import eu.tiducto.osmreview.shared.UiEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class RemoteVerdict { ACCEPTED, REJECTED, TIMED_OUT }

class TooManyRemoteRequestsException(max: Int) : IllegalStateException("$max remote-control requests are already waiting for confirmation in the editor")

class RemoteConfirmations(private val events: EventBus, var timeout: Duration = 2.minutes, private val maxPending: Int = 5) {
    private class Entry(val request: RemoteRequest, val decision: CompletableDeferred<Boolean>)

    private val pending = LinkedHashMap<String, Entry>()

    fun pending(): List<RemoteRequest> = synchronized(pending) { pending.values.map { it.request } }

    suspend fun ask(build: (id: String, createdAt: String, expiresAt: String) -> RemoteRequest): RemoteVerdict {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val request = build(id, now.toString(), now.plusMillis(timeout.inWholeMilliseconds).toString())
        val entry = Entry(request, CompletableDeferred())
        synchronized(pending) {
            if (pending.size >= maxPending) throw TooManyRemoteRequestsException(maxPending)
            pending[id] = entry
        }
        var verdict = RemoteVerdict.TIMED_OUT
        try {
            events.emit(UiEvent.RemoteConfirm(request))
            val accepted = withTimeoutOrNull(timeout) { entry.decision.await() }
            verdict = when (accepted) {
                true -> RemoteVerdict.ACCEPTED
                false -> RemoteVerdict.REJECTED
                null -> RemoteVerdict.TIMED_OUT
            }
            return verdict
        } finally {
            synchronized(pending) { pending.remove(id) }
            events.emit(UiEvent.RemoteConfirmResolved(id, verdict == RemoteVerdict.ACCEPTED))
        }
    }

    fun decide(id: String, accept: Boolean): Boolean {
        val entry = synchronized(pending) { pending[id] } ?: return false
        return entry.decision.complete(accept)
    }
}
