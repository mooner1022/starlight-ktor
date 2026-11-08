package dev.mooner.starlightktor.server

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class KtorServersTest {

    private val started = ArrayList<KtorServer>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        KtorServers.bindRetries = 2
        KtorServers.bindRetryDelayMillis = 200
        // StarLight exits on an uncaught exception; the JVM only prints it.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        started.forEach { it.stop(0, 1_000) }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun serves() {
        val server = start("A", freePort())
        assertEquals("hello", get(server.port))
        assertTrue(server.isRunning)
    }

    @Test
    fun portOfAnotherProcessFailsAfterRetries() {
        ServerSocket(0, 50, InetAddress.getByName(LOCALHOST)).use { taken ->
            val begin = System.nanoTime()
            val e = assertThrows(BindException::class.java) { start("A", taken.localPort) }
            val elapsedMillis = (System.nanoTime() - begin) / 1_000_000
            assertFalse(e is PortInUseException)
            assertTrue("retried for ${elapsedMillis}ms", elapsedMillis >= 2 * 200)
        }
        // The failed start leaves nothing behind: the port is free for the next one.
        Thread.sleep(300)
        assertNoCrash()
    }

    @Test
    fun portFreedWhileRetryingIsBound() {
        val taken = ServerSocket(0, 50, InetAddress.getByName(LOCALHOST))
        val port = taken.localPort
        Thread {
            Thread.sleep(250)
            taken.close()
        }.start()
        val server = start("A", port)
        assertEquals("hello", get(server.port))
        assertNoCrash()
    }

    @Test
    fun portOfAnotherPluginFailsAtOnce() {
        val port = freePort()
        start("Web Server", port)
        val begin = System.nanoTime()
        val e = assertThrows(PortInUseException::class.java) { start("Web IDE", port) }
        val elapsedMillis = (System.nanoTime() - begin) / 1_000_000
        assertEquals("Web Server", e.owner)
        assertEquals(port, e.port)
        assertTrue("took ${elapsedMillis}ms", elapsedMillis < 200)
        // The wildcard address overlaps the loopback one.
        assertThrows(PortInUseException::class.java) { start("MCP", port, host = "0.0.0.0") }
        assertEquals("hello", get(port))
    }

    @Test
    fun stopFreesThePort() {
        val port = freePort()
        val first = start("A", port)
        first.stop()
        assertFalse(first.isRunning)
        first.stop()
        val second = start("B", port)
        assertEquals("hello", get(second.port))
    }

    @Test
    fun clientsLeavingAreNotErrors() {
        val server = start("A", freePort()) {
            Thread.sleep(200)
        }
        repeat(5) {
            Socket(LOCALHOST, server.port).use { socket ->
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
            }
        }
        Socket(LOCALHOST, server.port).use { it.getOutputStream().write("NOT HTTP\r\n\r\n".toByteArray()) }
        Thread.sleep(800)
        assertEquals(emptyList<Throwable>(), errors)
        assertNoCrash()
    }

    @Test
    fun handlerErrorsDoNotStopTheServer() {
        val server = start("A", freePort()) { throw IllegalStateException("boom") }
        assertEquals(500, status(server.port))
        assertEquals(500, status(server.port))
        assertNoCrash()
    }

    private fun start(
        owner: String,
        port: Int,
        host: String = LOCALHOST,
        beforeResponse: () -> Unit = {},
    ): KtorServer =
        KtorServers.start(owner, host, port, onError = { errors += it }) {
            intercept(ApplicationCallPipeline.Call) {
                beforeResponse()
                call.respondText("hello")
                finish()
            }
        }.also(started::add)

    private fun assertNoCrash() {
        assertNull(uncaught.firstOrNull())
    }

    private fun get(port: Int): String {
        val connection = URL("http://$LOCALHOST:$port/").openConnection() as HttpURLConnection
        return connection.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun status(port: Int): Int {
        val connection = URL("http://$LOCALHOST:$port/").openConnection() as HttpURLConnection
        return connection.responseCode.also { connection.disconnect() }
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
