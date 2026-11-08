package dev.mooner.starlightktor

import dev.mooner.starlight.plugincore.plugin.StarlightPlugin

/**
 * Carries Ktor for other plugins, and [dev.mooner.starlightktor.server.KtorServers] to start their servers.
 *
 * A plugin that lists `ktor@<version>` in its `dependency` and declares Ktor as `compileOnly` gets Ktor's classes from
 * this plugin: StarLight looks up classes missing from a plugin in the other plugins' class loaders.
 */
@Suppress("unused")
class StarlightKtorPlugin : StarlightPlugin()
