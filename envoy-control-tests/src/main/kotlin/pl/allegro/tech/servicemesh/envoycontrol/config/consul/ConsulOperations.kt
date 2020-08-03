package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import com.ecwid.consul.v1.ConsulClient
import com.ecwid.consul.v1.agent.model.NewService
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import java.util.UUID

class ConsulOperations(port: Int) {

    private val client = ConsulClient("localhost", port)

    fun registerService(
        id: String = UUID.randomUUID().toString(),
        name: String,
        address: String,
        port: Int,
        registerDefaultCheck: Boolean = false,
        tags: List<String> = listOf("a")
    ): String {
        val service = NewService().also {
            it.id = id
            it.name = name
            it.address = address
            it.port = port
            it.tags = tags
            it.check = if (registerDefaultCheck) NewService.Check().also { check ->
                check.http = "http://$address:$port"
                check.interval = "3s"
            } else NewService.Check()
        }
        client.agentServiceRegister(service)
        return service.id
    }

    fun registerService(
        extension: ServiceExtension<*>,
        id: String = UUID.randomUUID().toString(),
        name: String,
        registerDefaultCheck: Boolean = false,
        tags: List<String> = listOf("a")
    ) = registerService(id, name, extension.container().ipAddress(), extension.container().port(), registerDefaultCheck, tags)

    fun deregisterService(id: String) {
        client.agentServiceDeregister(id)
    }

    fun deregisterAll() {
        registeredServices().forEach { deregisterService(it) }
    }

    fun anyRpcOperation(): String {
        return leader()
    }

    fun leader(): String {
        return client.statusLeader.value
    }

    fun peers(): List<String> {
        return client.statusPeers.value
    }

    private fun registeredServices() =
        client.agentServices
            .value
            .values
            .filter { !it.service.contains("envoy-control") }
            .map { it.id }
}
