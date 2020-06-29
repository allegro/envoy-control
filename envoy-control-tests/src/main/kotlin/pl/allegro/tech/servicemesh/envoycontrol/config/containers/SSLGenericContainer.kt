package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

open class SSLGenericContainer<SELF : SSLGenericContainer<SELF>>(
    dockerfileBuilder: DockerfileBuilder,
    private val sslDir: String = "testcontainers/ssl/",
    private val sslDirDestination: String = "/app/"
) : GenericContainer<SELF>(dockerfileBuilder.statements) {
    constructor(dockerImageName: String) : this(DockerfileBuilder().from(dockerImageName))

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(sslDir, sslDirDestination, BindMode.READ_ONLY)
    }
}
