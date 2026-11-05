package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.shared.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server → UI events over the `/api/events` WebSocket. Remote-control commands that arrive while no
 * tab is connected are queued and replayed to the next tab that connects (the container cannot
 * launch a browser).
 */
class EventBus {
    private val flow = MutableSharedFlow<UiEvent>(extraBufferCapacity = 256)
    private val queued = ConcurrentLinkedQueue<UiEvent.RemoteCommand>()
    private val subscribers = AtomicInteger(0)

    val events: SharedFlow<UiEvent> get() = flow

    val hasSubscribers: Boolean get() = subscribers.get() > 0

    fun emit(event: UiEvent) {
        if (event is UiEvent.RemoteCommand && !hasSubscribers) {
            queued += event
            while (queued.size > MAX_QUEUED) queued.poll()
        } else {
            flow.tryEmit(event)
        }
    }

    /** Registers a tab; returns the remote commands queued while none was connected. */
    fun connect(): List<UiEvent> {
        subscribers.incrementAndGet()
        return generateSequence { queued.poll() }.toList()
    }

    fun disconnect() {
        subscribers.decrementAndGet()
    }

    private companion object {
        const val MAX_QUEUED = 20
    }
}
