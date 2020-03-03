package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer

open class SSLGenericContainer<SELF : SSLGenericContainer<SELF>> : GenericContainer<SELF> {
    constructor(image: ImageFromDockerfile) : super(image)
    constructor(dockerImageName: String) : super(dockerImageName)

    companion object {
        private const val PRIVATE_KEY = "testcontainers/ssl/privkey.pem"
        private const val PRIVATE_KEY_DEST = "/app/privkey.pem"

        private const val CERTIFICATE = "testcontainers/ssl/fullchain.pem"
        private const val CERTIFICATE_DEST = "/app/fullchain.pem"

        private const val ROOT_CERTIFICATE = "testcontainers/ssl/root-ca.crt"
        private const val ROOT_CERTIFICATE_DEST = "/usr/local/share/ca-certificates/root-ca.crt"
    }

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(PRIVATE_KEY, PRIVATE_KEY_DEST, BindMode.READ_ONLY)
        withClasspathResourceMapping(CERTIFICATE, CERTIFICATE_DEST, BindMode.READ_ONLY)
        withClasspathResourceMapping(ROOT_CERTIFICATE, ROOT_CERTIFICATE_DEST, BindMode.READ_ONLY)
    }

    override fun containerIsStarted(containerInfo: InspectContainerResponse?) {
        super.containerIsStarted(containerInfo)
        execInContainer("apk --no-cache add ca-certificates")
        execInContainer("update-ca-certificates")
    }
}
