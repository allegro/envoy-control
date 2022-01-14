package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import pl.allegro.tech.servicemesh.envoycontrol.config.testcontainers.GenericContainer

class RedisBasedRateLimitContainer(
    private val redis: RedisContainer
): GenericContainer<EchoContainer>("envoyproxy/ratelimit:4d2efd61"), ServiceContainer {
    override fun configure() {
        super.configure()
        withExposedPorts(HTTP_PORT, GRPC_PORT, DEBUG_PORT)
        withNetwork(Network.SHARED)
        withEnv(mapOf(
            "BACKEND_TYPE" to "redis",
            "REDIS_SOCKET_TYPE" to "tcp",
            "REDIS_URL" to redis.address(),
            "USE_STATSD" to "false",
            "LOG_LEVEL" to "trace",
            "RUNTIME_ROOT" to "/",
            "RUNTIME_SUBDIRECTORY" to "tmp",
            "DEBUG_PORT" to "$DEBUG_PORT",
            "PORT" to "$HTTP_PORT",
            "GRPC_TRACE" to "all",
            "GRPC_PORT" to "$GRPC_PORT"))
        withClasspathResourceMapping("ratelimit_config.yaml", "/tmp/config.yaml", BindMode.READ_ONLY)
        withCommand("/bin/ratelimit")
        waitingFor(HttpWaitStrategy().forPath("/healthcheck").forPort(HTTP_PORT))
    }

    fun address(): String = "${ipAddress()}:$GRPC_PORT"

    override fun port() = GRPC_PORT

    fun httpPort() = HTTP_PORT

    companion object {
        const val DEBUG_PORT = 5698
        const val HTTP_PORT = 5699
        const val GRPC_PORT = 5700
    }
}
