package pl.allegro.tech.servicemesh.envoycontrol.config.containers

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
                .withFileFromClasspath("index.js", "testcontainers/proxy_server.js")
                .withDockerfileFromBuilder {
                    it.from("node:10.16.3-jessie")
                            .copy("index.js", "index.js")
                            .expose(5678)
                            .cmd("node", "index.js", "5678")
                            .build()
                }
)
