package dev.mooner.starlightktor.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.serverConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.BindException

/**
 * Starts the Ktor (CIO) servers of StarLight plugins and keeps track of the ports they hold.
 *
 * Every plugin that serves HTTP starts its server here instead of calling `embeddedServer` itself, which
 * - keeps a failing server from taking the app down: without a [CoroutineExceptionHandler], Ktor's server coroutine
 *   passes a bind failure to the thread's uncaught exception handler, and StarLight exits;
 * - tells a port taken by another plugin apart from one taken by another app, at once and naming the plugin
 *   ([PortInUseException]), since all plugins run in one process;
 * - waits for a port the previous StarLight process still holds: a restart (ProcessPhoenix, or after a crash) starts
 *   the new process while the old one may still be exiting, so a busy port is tried again a few times.
 *
 * The servers run in this plugin's class loader, which every plugin depending on `ktor@<version>` shares.
 */
object KtorServers {

    /**
     * Raised with every addition to the API. StarLight checks only the major and minor version of a dependency, and
     * this plugin's major.minor follows Ktor, so a plugin built against a newer patch release checks [apiLevel].
     *
     * Reading [apiLevel] fails with [NoClassDefFoundError] on Ktor plugin releases before this class existed
     * (3.6.1 and older): catch [LinkageError] around it and treat that as level 0.
     */
    const val API_LEVEL = 1

    /** The API level of the installed Ktor plugin; compare with [API_LEVEL] as compiled into the caller. */
    val apiLevel: Int
        get() = API_LEVEL

    private val LOG: Logger = LoggerFactory.getLogger(KtorServers::class.java)

    /** How many more times a port another process holds is tried, and how long apart. */
    internal var bindRetries = 3
    internal var bindRetryDelayMillis = 500L

    /** Ports reserved by starting or running servers. */
    private val reservations = ArrayList<Reservation>()

    /**
     * Binds [host]:[port] and serves [module] there.
     *
     * Blocks until the server accepts connections, or for about [bindRetries] × [bindRetryDelayMillis] longer when
     * the port is busy, so call it off the main thread.
     *
     * @param owner the plugin starting the server, as shown to the user in [PortInUseException].
     * @param onError errors of the running server, on one of Ktor's threads. Clients leaving (I/O errors) aren't
     *   errors. Logged by default.
     * @throws PortInUseException when another server started here holds the port; not retried.
     * @throws BindException when another process holds the port, after the retries.
     * @throws Throwable whatever else kept the server from starting, never a [CancellationException] that wraps it.
     */
    fun start(
        owner: String,
        host: String,
        port: Int,
        onError: (Throwable) -> Unit = { e -> LOG.error("The server of $owner on $host:$port failed", e) },
        module: suspend Application.() -> Unit,
    ): KtorServer {
        require(port in 1..65535) { "Invalid port: $port" }
        val reservation = reserve(owner, host, port)
        try {
            return bind(reservation, onError, module)
        } catch (e: Throwable) {
            release(reservation)
            throw e
        }
    }

    private fun bind(
        reservation: Reservation,
        onError: (Throwable) -> Unit,
        module: suspend Application.() -> Unit,
    ): KtorServer {
        var retries = 0
        while (true) {
            val sink = ErrorSink(onError)
            val config = serverConfig(applicationEnvironment()) {
                // Without a handler, an error in the server coroutine (such as a failed bind) ends the process.
                parentCoroutineContext = CoroutineExceptionHandler { _, e -> sink.handle(e) }
                module(module)
            }
            val server = embeddedServer(CIO, config) {
                connector {
                    host = reservation.host
                    port = reservation.port
                }
                // Binds even while connections of the previous process are in TIME_WAIT, whatever the platform's
                // default. It doesn't share the port with a server that is listening on it.
                reuseAddress = true
            }
            try {
                server.start(wait = false)
            } catch (e: Throwable) {
                runCatching { server.stop(0, 0) }
                // Ktor throws the cancellation of its server coroutine; the cause tells what happened.
                val cause = generateSequence(e) { it.cause }.firstOrNull { it !is CancellationException } ?: e
                if (cause !is BindException || retries >= bindRetries)
                    throw cause
                retries++
                LOG.info("${reservation.host}:${reservation.port} is busy, trying again ($retries/$bindRetries): ${cause.message}")
                Thread.sleep(bindRetryDelayMillis)
                continue
            }
            sink.isActive = true
            return KtorServer(reservation, server, sink)
        }
    }

    private fun reserve(owner: String, host: String, port: Int): Reservation =
        synchronized(reservations) {
            val holder = reservations.firstOrNull { it.port == port && it.overlaps(host) }
            if (holder != null)
                throw PortInUseException(port, holder.owner)
            Reservation(owner, host, port).also(reservations::add)
        }

    internal fun release(reservation: Reservation) {
        synchronized(reservations) {
            reservations.remove(reservation)
        }
    }

    /** One server's claim on a port. Compared by identity. */
    internal class Reservation(val owner: String, val host: String, val port: Int) {

        /** Whether a server on [other] and the same port would collide with this one. */
        fun overlaps(other: String): Boolean =
            host == other || host.isWildcard() || other.isWildcard()

        private fun String.isWildcard(): Boolean =
            this == "0.0.0.0" || this == "::" || this == "0:0:0:0:0:0:0:0" || isEmpty()
    }

    /** Passes errors of a running server on; drops those of a server that failed to start, which [start] throws. */
    internal class ErrorSink(private val onError: (Throwable) -> Unit) {

        @Volatile
        var isActive = false

        fun handle(e: Throwable) {
            // Ktor's own handler, which this one replaces for connections too, ignores these.
            if (!isActive || e is CancellationException || e is IOException)
                return
            try {
                onError(e)
            } catch (t: Throwable) {
                LOG.error("onError failed", t)
            }
        }
    }
}
