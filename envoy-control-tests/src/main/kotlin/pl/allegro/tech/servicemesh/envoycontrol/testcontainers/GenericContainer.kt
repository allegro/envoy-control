package pl.allegro.tech.servicemesh.envoycontrol.testcontainers

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.containers.GenericContainer as BaseGenericContainer

open class GenericContainer<SELF : GenericContainer<SELF>> : BaseGenericContainer<SELF> {
    constructor(image: ImageFromDockerfile) : super(image)
    constructor(dockerImageName: String) : super(dockerImageName)

    private val HOST_IP_SCRIPT = "testcontainers/host_ip.sh"
    private val HOST_IP_SCRIPT_DEST = "/usr/local/bin/host_ip.sh"

    companion object {
        const val allInterfaces = "0.0.0.0"
    }

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(HOST_IP_SCRIPT, HOST_IP_SCRIPT_DEST, BindMode.READ_ONLY)
    }

    override fun withClasspathResourceMapping(
        resourcePath: String?,
        containerPath: String?,
        mode: BindMode?
    ): SELF {
        return if (notAlreadyMounted(containerPath)) {
            super.withClasspathResourceMapping(resourcePath, containerPath, mode)
        } else {
            this.self()
        }
    }

    fun notAlreadyMounted(destination: String?) = binds.none { it.volume.path == destination }

    fun hostIp(): String {
        val result = execInContainer(HOST_IP_SCRIPT_DEST)

        if (result.stderr.isNotEmpty() or result.stdout.isEmpty()) {
            throw ContainerUnableToObtainHostIpException()
        }

        return result.stdout.trim()
    }

    fun ipAddress(): String {
        return containerInfo
            .networkSettings
            .networks[(network as Network.NetworkImpl).name]!!
            .ipAddress!!
    }

    open fun sigstop() {
        sendSignal("STOP")
    }

    open fun sigcont() {
        sendSignal("CONT")
    }

    fun blockTrafficTo(ip: String) {
        runCommands(
            arrayOf(
                "iptables -A INPUT -s $ip -j DROP",
                "iptables -A OUTPUT -d $ip -j DROP"
            )
        )
    }

    fun unblockTrafficTo(ip: String) {
        runCommands(
            arrayOf(
                "iptables -D INPUT -s $ip -j DROP",
                "iptables -D OUTPUT -d $ip -j DROP"
            )
        )
    }

    fun clearAllIptablesRules() {
        runCommands(
            arrayOf(
                "iptables -t nat -F",
                "iptables -t mangle -F",
                "iptables -F",
                "iptables -X"
            )
        )
    }

    fun runCommands(commands: Array<String>) {
        commands.forEach { command ->
            execInContainer(*(command.split(" ").toTypedArray()))
        }
    }

    fun restart() = dockerClient.restartContainerCmd(containerId).exec()

    private fun sendSignal(signal: String) {
        getDockerClient()
            .killContainerCmd(getContainerId())
            .withSignal(signal)
            .exec()
    }

    /**
     * The container host name is randomly generated based on the first 12 characters of the container ID.
     * https://developer.ibm.com/articles/dm-1602-db2-docker-trs/
     */
    fun containerName() = containerId.substring(0, 12)
}

class ContainerUnableToObtainHostIpException : RuntimeException()
