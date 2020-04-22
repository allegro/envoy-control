package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer

open class SSLGenericContainer<SELF : SSLGenericContainer<SELF>>(
    dockerfileBuilder: DockerfileBuilder,
    private val privateKey: String = "testcontainers/ssl/privkey.pem",
    private val privateKeyDest: String = "/app/privkey.pem",
    private val certificate: String = "testcontainers/ssl/fullchain_echo.pem",
    private val certificateDest: String = "/app/fullchain.pem"
) : GenericContainer<SELF>(dockerfileBuilder.statements) {
    constructor(dockerImageName: String) : this(DockerfileBuilder().from(dockerImageName))

    companion object {
        private const val ROOT_CERTIFICATE = "testcontainers/ssl/root-ca.crt"
        private const val ROOT_CERTIFICATE_DEST = "/usr/local/share/ca-certificates/root-ca.crt"
    }

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(privateKey, privateKeyDest, BindMode.READ_ONLY)
        withClasspathResourceMapping(certificate, certificateDest, BindMode.READ_ONLY)
        withClasspathResourceMapping(ROOT_CERTIFICATE, ROOT_CERTIFICATE_DEST, BindMode.READ_ONLY)
    }
}
