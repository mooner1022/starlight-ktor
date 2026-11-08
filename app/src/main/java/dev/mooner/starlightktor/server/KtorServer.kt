package dev.mooner.starlightktor.server

import io.ktor.server.engine.EmbeddedServer
import java.util.concurrent.atomic.AtomicBoolean

/** A server started by [KtorServers.start]. Thread-safe. */
class KtorServer internal constructor(
    private val reservation: KtorServers.Reservation,
    private val server: EmbeddedServer<*, *>,
    private val sink: KtorServers.ErrorSink,
) {

    private val stopped = AtomicBoolean(false)

    /** The plugin that started this server. */
    val owner: String
        get() = reservation.owner

    val host: String
        get() = reservation.host

    val port: Int
        get() = reservation.port

    /** False once [stop] was called. */
    val isRunning: Boolean
        get() = !stopped.get()

    /**
     * Stops the server and frees its port, waiting up to [timeoutMillis]. Calling it again does nothing.
     *
     * The port is free for [KtorServers.start] when this returns, even if stopping failed.
     */
    fun stop(gracePeriodMillis: Long = 500, timeoutMillis: Long = 3_000) {
        if (!stopped.compareAndSet(false, true))
            return
        sink.isActive = false
        try {
            server.stop(gracePeriodMillis, timeoutMillis)
        } finally {
            KtorServers.release(reservation)
        }
    }

    override fun toString(): String = "KtorServer($owner, $host:$port)"
}
