package dev.mooner.starlightktor.server

import java.net.BindException

/** [KtorServers.start] was asked for a port that a server of [owner], started in this app, holds. */
class PortInUseException(
    val port: Int,
    /** The plugin holding the port, as it named itself to [KtorServers.start]. */
    val owner: String,
) : BindException("Port $port is already used by $owner")
