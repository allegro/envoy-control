package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import com.github.dockerjava.api.command.InspectContainerResponse
import org.springframework.core.io.ClassPathResource
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Container
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.SSLGenericContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger as loggerDelegate

class EnvoyContainer(
    private val config: EnvoyConfig,
    private val localServiceIp: () -> String,
    private val envoyControl1XdsPort: Int,
    private val envoyControl2XdsPort: Int = envoyControl1XdsPort,
    private val logLevel: String = "info",
    image: String = DEFAULT_IMAGE,
    private val wrapperServiceIp: () -> String = { "127.0.0.1" },
) : SSLGenericContainer<EnvoyContainer>(
    dockerfileBuilder = DockerfileBuilder()
        .from(image)
        .run("apt-get update && apt-get install -y curl iproute2 iptables dnsmasq")
        .run("adduser --disabled-password --gecos \"\" test")
) {

    companion object {
        val logger by loggerDelegate()

        private const val CONFIG_DEST = "/etc/envoy/envoy.yaml"
        private const val LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh"
        private const val LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh"
        private const val EXTRA_DIR = "envoy/extra"
        private const val EXTRA_DIR_DEST = "/etc/envoy/extra"

        const val ENVOY_UID_ENV_NAME = "ENVOY_UID"
        const val EGRESS_LISTENER_CONTAINER_PORT = 5000
        const val INGRESS_LISTENER_CONTAINER_PORT = 5001
        private const val ADMIN_PORT = 10000

        private const val MIN_SUPPORTED_ENVOY_VERSION = "v1.30.4"
        private const val MAX_SUPPORTED_ENVOY_VERSION = "v1.34.0"

        val DEFAULT_IMAGE = run {
            val version =
                when (val versionArg = System.getProperty("pl.allegro.tech.servicemesh.envoyVersion").orEmpty()) {
                    "max", "" -> MAX_SUPPORTED_ENVOY_VERSION
                    "min" -> MIN_SUPPORTED_ENVOY_VERSION
                    else -> versionArg
                }
            logger.info("Using envoy version: $version")
            "envoyproxy/envoy:$version"
        }
    }

    override fun configure() {
        super.configure()

        withClasspathResourceMapping(
            LAUNCH_ENVOY_SCRIPT,
            LAUNCH_ENVOY_SCRIPT_DEST,
            BindMode.READ_ONLY
        )
        withClasspathResourceMapping(config.filePath, CONFIG_DEST, BindMode.READ_ONLY)

        if (ClassPathResource(EXTRA_DIR).exists()) {
            withClasspathResourceMapping(EXTRA_DIR, EXTRA_DIR_DEST, BindMode.READ_ONLY)
        }
        withEnv(ENVOY_UID_ENV_NAME, "0")
        withExposedPorts(EGRESS_LISTENER_CONTAINER_PORT, INGRESS_LISTENER_CONTAINER_PORT, ADMIN_PORT)
        withPrivilegedMode(true)

        withCommand(
            "/bin/sh", "/usr/local/bin/launch_envoy.sh",
            envoyControl1XdsPort.toString(),
            envoyControl2XdsPort.toString(),
            CONFIG_DEST,
            localServiceIp(),
            config.trustedCa,
            config.certificateChain,
            config.privateKey,
            config.serviceName,
            wrapperServiceIp(),
            "--config-yaml", config.configOverride,
            "-l", logLevel
        )
    }

    fun addIptablesRedirect(redirectToPort: Int, destinationPort: Int) {
        execInContainer(
            "sh",
            "-c",
            "iptables -t nat -A OUTPUT -p tcp -m tcp --dport $destinationPort -m owner --uid-owner 0 -j RETURN"
        )
        execInContainer(
            "sh",
            "-c",
            "iptables -t nat -A OUTPUT -p tcp -m tcp --dport $destinationPort -m owner --gid-owner 0 -j RETURN"
        )
        execInContainer(
            "sh",
            "-c",
            "iptables -t nat -A OUTPUT -p tcp --dport $destinationPort -j REDIRECT --to-ports $redirectToPort"
        )
    }

    fun cleanIptables() {
        execInContainer("sh", "-c", "iptables -t nat -F")
    }

    fun callInContainer(hostname: String, path: String = "", isHttps: Boolean = false): Container.ExecResult? {
        if (isHttps) {
            // because we are using self signed certificate
            return execInContainer("su", "test", "-c", "curl https://$hostname/$path --insecure")
        }
        return execInContainer("su", "test", "-c", "curl http://$hostname/$path")
    }

    fun addDnsEntry(host: String, ip: String) {
        execInContainer("sh", "-c", "echo address=/$host/$ip >> /etc/dnsmasq.conf")
        execInContainer("sh", "-c", "/etc/init.d/dnsmasq restart")
    }

    fun removeDnsEntry(host: String) {
        execInContainer("sh", "-c", "sed -i 's/.*$host.*//' /etc/dnsmasq.conf")
        execInContainer("sh", "-c", "/etc/init.d/dnsmasq restart")
    }

    override fun containerIsStarting(containerInfo: InspectContainerResponse?) {
        followOutput(Slf4jLogConsumer(logger).withPrefix("ENVOY"))
        super.containerIsStarting(containerInfo)
    }

    fun egressListenerUrl() = "http://$containerIpAddress:${getMappedPort(EGRESS_LISTENER_CONTAINER_PORT)}/"

    fun ingressListenerUrl(secured: Boolean = false): String {
        val schema = if (secured) "https" else "http"
        return schema + "://$containerIpAddress:${getMappedPort(INGRESS_LISTENER_CONTAINER_PORT)}"
    }

    fun adminUrl() = "http://$containerIpAddress:${getMappedPort(ADMIN_PORT)}"

    fun admin() = EnvoyAdmin(adminUrl())

    fun ingressHost(): String = ingressListenerUrl().removePrefix("http://")
}
