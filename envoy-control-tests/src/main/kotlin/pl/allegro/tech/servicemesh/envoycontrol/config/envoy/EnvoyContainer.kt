package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import com.github.dockerjava.api.command.InspectContainerResponse
import org.springframework.core.io.ClassPathResource
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger as loggerDelegate

class EnvoyContainer(
    private val configPath: String,
    private val localServiceIp: String,
    private val envoyControl1XdsPort: Int,
    private val envoyControl2XdsPort: Int = envoyControl1XdsPort,
    private val image: String
) : SSLGenericContainer<EnvoyContainer>(DockerfileBuilder()
        .from(image)
        .run("apk --no-cache add curl iproute2")
) {

    companion object {
        val logger by loggerDelegate()

        private const val CONFIG_DEST = "/etc/envoy/envoy.yaml"
        private const val LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh"
        private const val LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh"
        private const val EXTRA_DIR = "envoy/extra"
        private const val EXTRA_DIR_DEST = "/etc/envoy/extra"

        const val EGRESS_LISTENER_CONTAINER_PORT = 5000
        const val INGRESS_LISTENER_CONTAINER_PORT = 5001
        private const val ADMIN_PORT = 10000
    }

    override fun configure() {
        super.configure()

        withClasspathResourceMapping(
            LAUNCH_ENVOY_SCRIPT,
            LAUNCH_ENVOY_SCRIPT_DEST,
            BindMode.READ_ONLY
        )
        withClasspathResourceMapping(configPath, CONFIG_DEST, BindMode.READ_ONLY)

        if (ClassPathResource(EXTRA_DIR).exists()) {
            withClasspathResourceMapping(EXTRA_DIR, EXTRA_DIR_DEST, BindMode.READ_ONLY)
        }

        withExposedPorts(EGRESS_LISTENER_CONTAINER_PORT, INGRESS_LISTENER_CONTAINER_PORT, ADMIN_PORT)
        withPrivilegedMode(true)

        withCommand(
            "/bin/sh", "/usr/local/bin/launch_envoy.sh",
            Integer.toString(envoyControl1XdsPort),
            Integer.toString(envoyControl2XdsPort),
            CONFIG_DEST,
            localServiceIp,
            "-l", "debug"
        )
    }

    override fun containerIsStarting(containerInfo: InspectContainerResponse?) {
        followOutput(Slf4jLogConsumer(logger).withPrefix("ENVOY"))
        super.containerIsStarting(containerInfo)
    }

    fun egressListenerUrl() = "http://$containerIpAddress:${getMappedPort(EGRESS_LISTENER_CONTAINER_PORT)}/"

    fun ingressListenerUrl() = "http://$containerIpAddress:${getMappedPort(INGRESS_LISTENER_CONTAINER_PORT)}"

    fun adminUrl() = "http://$containerIpAddress:${getMappedPort(ADMIN_PORT)}"

    fun admin() = EnvoyAdmin(adminUrl())
}
