package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import java.io.File

sealed class ConsulConfig(
    val id: Int,
    val dc: String,
    val config: Map<String, String>,
    val jsonFiles: List<File> = listOf()
) {
    fun launchCommand(): String {
        val base = mapOf(
            "datacenter" to dc
        )

        return "consul agent " + (config + base).map { (key, value) -> format(key, value) }.joinToString(" ")
    }

    private fun format(key: String, value: String): String {
        return if (value.isEmpty()) "-$key" else "-$key=$value"
    }
}

val defaultConfig = mapOf(
    "data-dir" to "/data",
    "pid-file" to ConsulContainer.pidFile,
    "config-dir" to ConsulContainer.configDir,
    "bind" to "0.0.0.0",
    "client" to "0.0.0.0"
)

class ConsulClientConfig(id: Int, dc: String, serverAddress: String, jsonFiles: List<File> = listOf()) : ConsulConfig(
    id,
    dc,
    defaultConfig + mapOf(
        "retry-join" to serverAddress,
        "node" to "consul-client-$id"
    ),
    jsonFiles
)

class ConsulServerConfig(id: Int, dc: String, jsonFiles: List<File> = listOf()) : ConsulConfig(
    id,
    dc,
    defaultConfig + mapOf(
        "server" to "",
        "bootstrap-expect" to "3",
        "ui" to "",
        "node" to "consul-server-$dc-$id"
    ),
    jsonFiles
)
