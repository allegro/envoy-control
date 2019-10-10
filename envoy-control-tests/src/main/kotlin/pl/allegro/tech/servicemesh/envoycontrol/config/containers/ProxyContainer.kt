package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.time.Duration

class ProxyContainer(private val proxyUrl: String) : GenericContainer<ProxyContainer>(
    ImageFromDockerfile()
        .withDockerfileFromBuilder {
            it.from("node:10.16.3-jessie")
                .build()
        }
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(5))
        .build()

    override fun configure() {
        super.configure()
        withStartupTimeout(Duration.ofHours(2))
        withExposedPorts(5678)
        withClasspathResourceMapping("testcontainers/proxy_server.js", "/index.js", BindMode.READ_ONLY)
        withCommand("node", "/index.js", "5678")
    }

    fun call(destination: String): Response {
        val url = "$proxyUrl/?call=http://$destination"
        return client.newCall(
            Request.Builder()
                .get()
                .url(url)
                .header("Host", "proxy1")
                .build()
        ).execute()
    }
}
