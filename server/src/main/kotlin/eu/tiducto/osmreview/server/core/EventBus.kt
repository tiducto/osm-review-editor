package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.shared.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class EventBus {
    private val flow = MutableSharedFlow<UiEvent>(extraBufferCapacity = 256)
    private val queued = ConcurrentLinkedQueue<UiEvent>()
    private val subscribers = AtomicInteger(0)

    val events: SharedFlow<UiEvent> get() = flow

    val hasSubscribers: Boolean get() = subscribers.get() > 0

    fun emit(event: UiEvent): Boolean {
        val queue = (event is UiEvent.RemoteCommand || event is UiEvent.PushView) && !hasSubscribers
        if (queue) {
            queued += event
            while (queued.size > MAX_QUEUED) queued.poll()
        } else {
            flow.tryEmit(event)
        }
        return !queue
    }

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
