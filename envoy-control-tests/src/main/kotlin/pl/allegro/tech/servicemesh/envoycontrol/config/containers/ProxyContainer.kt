package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer

/**
 * This class will run a simple proxy server inside a docker container.
 * It will proxy requests to a destination specified by ?call= query parameter
 *
 * Exapmle: curl -v http://localhost:12345/?call=http://172.24.0.2:9000
 */
class ProxyContainer : GenericContainer<ProxyContainer>(
    ImageFromDockerfile()
        .withDockerfileFromBuilder {
            it.from("node:10.16.3-jessie")
                .build()
        }
) {
    override fun configure() {
        super.configure()
        withExposedPorts(5678)
        withClasspathResourceMapping("testcontainers/proxy_server.js", "/index.js", BindMode.READ_ONLY)
        withCommand("node", "/index.js", "5678")
    }
}
