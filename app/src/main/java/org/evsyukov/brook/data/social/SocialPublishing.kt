package org.evsyukov.brook.data.social

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.evsyukov.brook.data.db.BrookDatabase
import java.io.IOException
import java.util.UUID

/** One coordinator per app, also shared with WorkManager. */
class SocialPublishing(
    val store: SocialStore,
    private val api: SocialPublisher,
    private val databases: suspend () -> List<BrookDatabase>,
    private val schedule: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    /**
     * Selected destinations, or none when the stored connections cannot be read (a rotated or lost
     * keystore key): favoriting must never fail because an optional destination is unavailable.
     */
    suspend fun destinations(): List<String> = try {
        store.connections.first().filter { it.enabled }.map { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
    fun retryPending() = schedule()
    fun history(): Flow<List<Delivery>> = flow {
        val dbs = databases()
        if (dbs.isEmpty()) emit(emptyList())
        else emitAll(combine(dbs.map { it.deliveryDao().observe() }) { rows -> rows.flatMap { it }.sortedByDescending { it.createdAt }.take(100) })
    }
    suspend fun connect(service: SocialService, server: String, user: String, secret: String) {
        val connected = api.connect(service, server, user, secret)
        lock.withLock {
            val old = store.connections.first().firstOrNull { it.service == service }
            val same = old?.takeIf { it.server == connected.server && it.userId == connected.userId }
            if (old != null && same == null) databases().forEach { it.deliveryDao().cancel(old.id) }
            val saved = connected.withId(same?.id ?: "${connected.id}#${UUID.randomUUID()}").withEnabled(same?.enabled ?: false)
            store.save(saved)
            databases().forEach { it.deliveryDao().retryAuth(saved.id) }
        }
        schedule()
    }

    suspend fun setEnabled(service: SocialService, enabled: Boolean) {
        lock.withLock {
            val c = store.connections.first().firstOrNull { it.service == service } ?: return
            store.save(c.withEnabled(enabled))
        }
        if (enabled) schedule()
    }

    suspend fun disconnect(service: SocialService) = lock.withLock {
        val c = store.connections.first().firstOrNull { it.service == service } ?: return
        store.remove(service)
        databases().forEach { it.deliveryDao().cancel(c.id) }
    }
    /** True means transient failures remain. Never throw a provider failure into reader sync. */
    suspend fun send(): Boolean = lock.withLock {
        // A rotated or lost keystore key must not crash the worker or cancel what is still queued:
        // the deliveries wait until the user reconnects the service.
        val connections = try {
            store.connections.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        var retry = false
        for (db in databases()) {
            val dao = db.deliveryDao()
            for (queued in dao.all().filter { it.state == Delivery.PENDING }) {
                val connection = connections.firstOrNull { it.id == queued.destination }
                if (connection == null) {
                    dao.update(queued.copy(state = Delivery.CANCELED))
                    continue
                }
                if (!connection.enabled) continue
                var delivery = queued
                try {
                    if (delivery.remoteId.isEmpty()) {
                        // Keys live for at most one hour; leave a margin and fail closed on clock rollback.
                        if (connection.service == SocialService.MASTODON && delivery.firstAttemptAt != 0L &&
                            now() - delivery.firstAttemptAt !in 0 until 50 * 60 * 1000L) {
                            dao.update(delivery.copy(state = Delivery.UNKNOWN))
                            continue
                        }
                        delivery = delivery.copy(firstAttemptAt = delivery.firstAttemptAt.takeIf { it != 0L } ?: now())
                        dao.update(delivery)
                        delivery = delivery.copy(remoteId = authorized(connection) { api.publish(it, delivery) })
                        dao.update(delivery)
                    }
                    if (authorized(connection) { api.verify(it, delivery) }) {
                        dao.update(delivery.copy(state = Delivery.SENT))
                    } else {
                        dao.update(delivery.copy(state = Delivery.UNKNOWN))
                    }
                } catch (e: SocialFailure) {
                    if (e.reason == "retry") retry = true
                    else dao.update(delivery.copy(state = e.reason))
                } catch (e: IOException) {
                    retry = true
                }
            }
        }
        retry
    }

    private suspend fun <T> authorized(connection: SocialConnection, action: suspend (SocialConnection) -> T): T {
        val current = store.connections.first().first { it.id == connection.id }
        return try {
            action(current)
        } catch (e: SocialFailure) {
            if (e.reason != Delivery.AUTH || current.service != SocialService.BLUESKY) throw e
            val refreshed = api.refresh(current)
            store.save(refreshed)
            action(refreshed)
        }
    }
}