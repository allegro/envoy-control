package pl.allegro.tech.servicemesh.envoycontrol.config.consul

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class ConsulContainer(
    private val dc: String,
    private val externalPort: Int,
    private val id: Int,
    private val consulConfig: ConsulConfig = ConsulServerConfig(id, dc),
    val internalPort: Int = 8500
) : GenericContainer<ConsulContainer>(
    ImageFromDockerfile().withDockerfileFromBuilder {
        it.from("consul:1.11.11")
            .run("apk", "add", "iproute2")
            .cmd(consulConfig.launchCommand())
            .expose(internalPort)
            .build()
    }) {

    companion object {
        const val pidFile = "/tmp/consul.pid"
        const val configDir = "/consul/config"
    }

    override fun configure() {
        super.configure()
        portBindings.add("$externalPort:$internalPort")
        awaitConsulReady()
    }

    private fun awaitConsulReady(): ConsulContainer {
        consulConfig.jsonFiles.forEach { jsonFile ->
            withClasspathResourceMapping(jsonFile.path, "$configDir/${jsonFile.name}", BindMode.READ_ONLY)
        }
        withPrivilegedMode(true)
        return when (consulConfig) {
            is ConsulServerConfig -> waitingFor(Wait.forHttp("/ui").forStatusCode(200))
            is ConsulClientConfig -> waitingFor(Wait.forHttp("/v1/status/leader").forStatusCode(200))
        }
    }

    fun blockExternalTraffic() {
        val commands = arrayOf(
            "iptables -F",
            "iptables -P INPUT DROP",
            "iptables -P OUTPUT DROP",
            "iptables -P FORWARD DROP",
            "iptables -A INPUT -i lo -j ACCEPT",
            "iptables -A OUTPUT -o lo -j ACCEPT"
        )

        runCommands(commands)
    }

    fun unblockExternalTraffic() {
        val commands = arrayOf(
            "iptables -F",
            "iptables -P INPUT ACCEPT",
            "iptables -P FORWARD ACCEPT",
            "iptables -P OUTPUT ACCEPT"
        )

        runCommands(commands)
    }

    private fun sendSignal(signal: String) {
        val pid = this.execInContainer("cat", pidFile).stdout
        this.execInContainer("kill", "-$signal", pid)
    }

    override fun sigstop() {
        sendSignal("STOP")
    }

    override fun sigcont() {
        sendSignal("CONT")
    }
}
