package com.personalos.app.core.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one owner of the notifications currently in flight.
 *
 * Anything that wants to tell the user something posts here; one host renders the
 * result. That indirection is the point: a screen never draws its own banner, so
 * there is no per-screen styling to drift ([SyncStatus] used to be its own state
 * holder, which is why the bar only existed where a tile scaffold did).
 *
 * [post] with an id already present **replaces** it, so a producer (a sync
 * advancing its step) posts once and [update]s the rest.
 */
interface NotificationCenter {
    val active: StateFlow<List<Notification>>

    fun post(notification: Notification)

    fun update(
        id: String,
        body: String,
    )

    fun dismiss(id: String)
}

/** The in-memory owner; the app has one. */
class InMemoryNotificationCenter : NotificationCenter {
    private val _active = MutableStateFlow<List<Notification>>(emptyList())
    override val active: StateFlow<List<Notification>> = _active.asStateFlow()

    override fun post(notification: Notification) {
        _active.update { list -> list.filterNot { it.id == notification.id } + notification }
    }

    override fun update(
        id: String,
        body: String,
    ) {
        _active.update { list -> list.map { if (it.id == id) it.copy(body = body) else it } }
    }

    override fun dismiss(id: String) {
        _active.update { list -> list.filterNot { it.id == id } }
    }
}

/**
 * Which notification the host shows when several are active: the **loudest**,
 * then the **newest**. Pure, so precedence is a tested rule and not a branch
 * buried in a composable. An error, then, always wins over a sync line.
 */
fun visibleNotification(active: List<Notification>): Notification? = active.maxWithOrNull(compareBy<Notification> { it.severity.ordinal }.thenBy { it.at })

/**
 * The process-wide center — one per process, not one per [AppContainer].
 *
 * A background producer (a sync worker, the debug broadcast) builds its own
 * container, so an in-memory center inside the container would post into a list
 * nobody observes and its notification would be invisible. This is the one list
 * the root host renders, whoever posted.
 */
object AppNotifications : NotificationCenter {
    private val delegate = InMemoryNotificationCenter()

    override val active: StateFlow<List<Notification>> get() = delegate.active

    override fun post(notification: Notification) = delegate.post(notification)

    override fun update(
        id: String,
        body: String,
    ) = delegate.update(id, body)

    override fun dismiss(id: String) = delegate.dismiss(id)
}
