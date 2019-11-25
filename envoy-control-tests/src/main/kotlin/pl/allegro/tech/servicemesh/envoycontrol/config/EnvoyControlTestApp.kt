package pl.allegro.tech.servicemesh.envoycontrol.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.pszymczyk.consul.infrastructure.Ports
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.builder.SpringApplicationBuilder
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControl
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.time.Duration

interface EnvoyControlTestApp {
    val appPort: Int
    val grpcPort: Int
    val appName: String
    fun run()
    fun stop()
    fun isHealthy(): Boolean
    fun getState(): ServicesState
    fun getHealthStatus(): Health
    fun <T> bean(clazz: Class<T>): T
}

class EnvoyControlRunnerTestApp(
    val properties: Map<String, Any> = mapOf(),
    val consulPort: Int,
    val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
    override val grpcPort: Int = Ports.nextAvailable(),
    override val appPort: Int = Ports.nextAvailable()
) :
    EnvoyControlTestApp {

    override val appName = "envoy-control"
    private lateinit var app: SpringApplicationBuilder

    private val baseProperties = mapOf(
        "spring.profiles.active" to "test",
        "spring.jmx.enabled" to false,
        "envoy-control.source.consul.port" to consulPort,
        "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true,
        "envoy-control.sync.polling-interval" to Duration.ofSeconds(1).seconds,
        "envoy-control.server.port" to grpcPort,
        // Round robin gives much more predictable results in tests than LEAST_REQUEST
        "envoy-control.envoy.snapshot.load-balancing.policy" to "ROUND_ROBIN"
    )

    override fun run() {
        app = SpringApplicationBuilder(EnvoyControl::class.java).properties(baseProperties + properties)
        app.run("--server.port=$appPort", "-e test")
        logger.info("starting EC on port $appPort, grpc: $grpcPort, consul: $consulPort")
    }

    override fun stop() {
        app.context().close()
    }

    override fun isHealthy(): Boolean = getApplicationStatusResponse().use { it.isSuccessful }

    override fun getHealthStatus(): Health {
        val response = getApplicationStatusResponse()
        return objectMapper.readValue(response.body()?.use { it.string() }, Health::class.java)
    }

    override fun getState(): ServicesState {
        val response = httpClient
            .newCall(
                Request.Builder()
                    .get()
                    .url("http://localhost:$appPort/state")
                    .build()
            )
            .execute()
        return objectMapper.readValue(response.body()?.use { it.string() }, ServicesState::class.java)
    }

    private fun getApplicationStatusResponse(): Response =
        httpClient
            .newCall(
                Request.Builder()
                    .get()
                    .url("http://localhost:$appPort/actuator/health")
                    .build()
            )
            .execute()

    override fun <T> bean(clazz: Class<T>): T = app.context().getBean(clazz)
        ?: throw IllegalStateException("Bean of type ${clazz.simpleName} not found in the context")

    companion object {
        val logger by logger()
        private val httpClient = OkHttpClient.Builder()
            .build()
    }
}

data class Health(
    val status: Status,
    val details: Map<String, HealthDetails>
)

data class HealthDetails(
    val status: Status
)
