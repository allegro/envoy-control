@file:Suppress("DANGEROUS_CHARACTERS")

package pl.allegro.tech.servicemesh.envoycontrol.routing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.StringAssert
import org.awaitility.Awaitility
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.BindMode
import org.yaml.snakeyaml.Yaml
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.chaos.api.NetworkDelay
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtensionBase
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.Health
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.SnapshotDebugResponse
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.HttpsEchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.Optional
import java.util.function.Function
import kotlin.io.path.createTempFile

open class DebugTest {

    companion object {
        @JvmField
        @RegisterExtension
        val echoService = GenericServiceExtension(HttpsEchoContainer())
    }
}

class DefaultServiceTagPreferenceTest : DebugTest() {

    companion object {
        // // language=yaml
        // private val config = """
        // {}
        // """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = staticEnvoyExtension(
            config = ConfigYaml("/envoy/debug/config_static.yaml"),
            localService = echoService
        ).apply { container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte2|vte3|global") }
    }

    @Test
    fun debug() {

        // envoy.waitForAvailableEndpoints()  // TODO: check it
        // envoy.waitForReadyServices() // TODO: check it
        // envoy.waitForClusterEndpointHealthy() // TODO: check it

        val adminUrl = envoy.container.adminUrl()
        val egressUrl = envoy.container.egressListenerUrl()


        assertThat(1).isEqualTo(2)
    }
}

class CELTest {
    @Nested
    inner class AccessLog : AccessLogTest()
    open class AccessLogTest {

        fun EnvoyExtension.start() = try {
            beforeAll(contextMock)
        } finally {
            afterAll(contextMock)
        }

        private val `config with CEL in accessLog` = ConfigYaml("/envoy/debug/config_base.yaml").modify {
            listeners {
                egress {
                    //language=yaml
                    http += """
                        access_log:
                          name: envoy.access_loggers.stdout
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog
                            log_format:
                              text_format: "CEL = '%CEL(xds.node.metadata.default_service_tag_preference)%'\n"
                    """.trimIndent()
                }
            }
            //language=yaml
            this += """
              node:
                metadata:
                  default_service_tag_preference: lvte1|vte2|global
            """.trimIndent()
        }

        @Test
        fun `use %CEL()% in access log without any other config fails`() {
            // given
            val envoy = staticEnvoyExtension(config = `config with CEL in accessLog`).asClosable()

            val expectedFailureLog =
                "[critical].*error initializing config .* Not supported field in StreamInfo: CEL".toRegex()
            envoy.extension.container.logRecorder.recordLogs {
                expectedFailureLog.containsMatchIn(it)
                // [2025-02-14 19:27:20.111][134][critical][main] [source/server/server.cc:414] error initializing config '  /tmp/tmp.ZXJQoHVwcp/envoy.yaml': Not supported field in StreamInfo: CEL
            }

            // expects
            assertThatThrownBy { envoy.use { it.start() } }

            val failureLogs = envoy.extension.container.logRecorder.getRecordedLogs()

            assertThat(failureLogs, StringAssert::class.java)
                .first().containsPattern(expectedFailureLog.pattern)
        }

        @Test
        fun `use %CEL()% in access log with explicitly enabled CEL formatter works`() {
            // given
            val config = `config with CEL in accessLog`.modify {
                listeners {
                    egress {
                        //language=yaml
                        http.at("/access_log/typed_config/log_format") += """
                          formatters:
                          - name: envoy.formatter.cel
                            typed_config: {"@type": "type.googleapis.com/envoy.extensions.formatter.cel.v3.Cel"}                            
                        """.trimIndent()
                    }
                }
            }

            val envoy = staticEnvoyExtension(config = config).asClosable()
            val expectedAccessLog = "CEL = 'lvte1|vte2|global'"

            envoy.use {
                it.start()
                envoy.extension.container.logRecorder.recordLogs { it.contains(expectedAccessLog) }

                // when
                envoy.extension.egressOperations.callService("backend")

                // then
                untilAsserted {
                    val accessLogs = envoy.extension.container.logRecorder.getRecordedLogs()
                    assertThat(accessLogs, StringAssert::class.java)
                        .first().contains(expectedAccessLog)
                }
            }
        }
    }

    // @Nested
    // inner class Zupa : DDTest()
    //
    // @Test
    // fun buga() {
    //     assertThat("").isEqualTo("fwef")
    // }
    //
    // open class DDTest {
    //
    //     companion object {
    //         @JvmField
    //         @RegisterExtension
    //         val echoService = GenericServiceExtension(HttpsEchoContainer())
    //     }
    //
    //     @Test
    //     fun ogaBoga() {
    //         assertThat(echoService.container().ipAddress()).isEqualTo("122")
    //         assertThat(3).isEqualTo(5)
    //     }
    // }
}

private class ConfigYaml private constructor(private val config: ObjectNode) {

    private object S {
        val yaml = Yaml()
        val json = ObjectMapper()
    }

    constructor(path: String) : this(
        config = fun(): ObjectNode {
            val baseConfigYaml: Map<Any, Any> = ConfigYaml::class.java.getResourceAsStream(path)
                .let { requireNotNull(it) { "file '$path' not found" } }
                .use { S.yaml.load(it) }
            return S.json.valueToTree(baseConfigYaml)
        }()
    )

    override fun toString(): String = toMap().let { S.yaml.dump(it) }

    private fun toMap(): Map<*, *> = S.json.convertValue(config, Map::class.java)

    fun toTempFile(): Path {
        val path = createTempFile("envoy-debug-config", ".yaml")
        val file = path.toFile()
        file.deleteOnExit()
        file.bufferedWriter().use {
            S.yaml.dump(toMap(), it)
        }
        return path
    }

    inner class Modification : YamlNode(config) {
        inner class Listeners {
            inner class Listener(val key: String) {
                private val node by lazy {
                    config.requiredAt("/static_resources/listeners")
                        .let { it as ArrayNode }
                        .single { it["name"].asText() == key }
                }

                val http by lazy {
                    node["filter_chains"].single()["filters"]
                        .single { it["name"].asText() == "envoy.filters.network.http_connection_manager" }
                        .get("typed_config")
                        .let { YamlNode(it as ObjectNode) }
                }
            }

            fun egress(block: Listener.() -> Unit) {
                val listener = Listener(key = "egress")
                block(listener)
            }
        }

        fun listeners(block: Listeners.() -> Unit) {
            block(Listeners())
        }
    }

    open inner class YamlNode(private val jsonNode: ObjectNode) {
        operator fun plusAssign(mergeYaml: String) {
            val jsonToMerge = S.json.valueToTree(S.yaml.load(mergeYaml)) as ObjectNode
            jsonNode.setAll<ObjectNode>(jsonToMerge)
        }

        fun at(path: String): YamlNode = YamlNode(jsonNode = jsonNode.withObject(path))
    }

    fun modify(block: Modification.() -> Unit): ConfigYaml {
        val modified = ConfigYaml(config = config.deepCopy())
        block(modified.Modification())
        return modified
    }
}

class TempYamlLoader {

    val yaml = Yaml()
    val json = ObjectMapper()

    @Test
    fun debug() {

        val baseConfigYaml: Map<Any, Any>? = javaClass.getResourceAsStream("/envoy/debug/config_base.yaml")
            .let { requireNotNull(it) { "file not found" } }
            .use { yaml.load(it) }

        val baseConfigJson: ObjectNode = json.valueToTree(baseConfigYaml)

        val someList = baseConfigJson.requiredAt("/some/list").let { it as ArrayNode }

        val newFirstElement = json.createObjectNode().apply {
            put("name", "inserted at index 0, yeah")
            put("val", 333)
        }
        someList.insert(0, newFirstElement)

        val out = json.convertValue(baseConfigJson, Map::class.java)
        val outYaml = yaml.dump(out)

        val outJson = json.writeValueAsString(baseConfigJson)
        assertThat(out).isEqualTo("dupa")
    }
}

private fun staticEnvoyExtension(
    config: ConfigYaml,
    localService: GenericServiceExtension<HttpsEchoContainer>? = null
) = EnvoyExtension(
    envoyControl = FakeEnvoyControl(),
    config = EnvoyConfig("envoy/debug/config_invalid.yaml"),
    localService = localService
).also {
    it.container.withFileSystemBind(config.toTempFile().toString(), "/etc/envoy/envoy.yaml", BindMode.READ_ONLY)
}

private fun EnvoyExtension.asClosable() = ClosableEnvoyExtension(this)

private class ClosableEnvoyExtension(val extension: EnvoyExtension) : AutoCloseable {
    override fun close() {
        extension.afterAll(contextMock)
    }

    fun start() {
        extension.beforeAll(contextMock)
    }
}

private class FakeEnvoyControl : EnvoyControlExtensionBase {
    override val app: EnvoyControlTestApp = object : EnvoyControlTestApp {
        override val appPort: Int
            get() {
                throw UnsupportedOperationException()
            }
        override val grpcPort: Int = 0
        override val appName: String
            get() {
                throw UnsupportedOperationException()
            }

        override fun run() {
            throw UnsupportedOperationException()
        }

        override fun stop() {
            throw UnsupportedOperationException()
        }

        override fun isHealthy(): Boolean {
            throw UnsupportedOperationException()
        }

        override fun getState(): ServicesState {
            throw UnsupportedOperationException()
        }

        override fun getSnapshot(nodeJson: String): SnapshotDebugResponse {
            throw UnsupportedOperationException()
        }

        override fun getGlobalSnapshot(xds: Boolean?): SnapshotDebugResponse {
            throw UnsupportedOperationException()
        }

        override fun getHealthStatus(): Health {
            throw UnsupportedOperationException()
        }

        override fun postChaosFaultRequest(
            username: String, password: String, networkDelay: NetworkDelay
        ): Response {
            throw UnsupportedOperationException()
        }

        override fun getExperimentsListRequest(username: String, password: String): Response {
            throw UnsupportedOperationException()
        }

        override fun deleteChaosFaultRequest(
            username: String, password: String, faultId: String
        ): Response {
            throw UnsupportedOperationException()
        }

        override fun meterRegistry(): MeterRegistry {
            throw UnsupportedOperationException()
        }
    }

    override fun beforeAll(context: ExtensionContext?) {}

    override fun afterAll(context: ExtensionContext?) {}
}

val contextMock = object : ExtensionContext {
    override fun getParent(): Optional<ExtensionContext> {
        TODO("Not yet implemented")
    }

    override fun getRoot(): ExtensionContext {
        TODO("Not yet implemented")
    }

    override fun getUniqueId(): String {
        TODO("Not yet implemented")
    }

    override fun getDisplayName(): String {
        TODO("Not yet implemented")
    }

    override fun getTags(): MutableSet<String> {
        TODO("Not yet implemented")
    }

    override fun getElement(): Optional<AnnotatedElement> {
        TODO("Not yet implemented")
    }

    override fun getTestClass(): Optional<Class<*>> {
        TODO("Not yet implemented")
    }

    override fun getTestInstanceLifecycle(): Optional<TestInstance.Lifecycle> {
        TODO("Not yet implemented")
    }

    override fun getTestInstance(): Optional<Any> {
        TODO("Not yet implemented")
    }

    override fun getTestInstances(): Optional<TestInstances> {
        TODO("Not yet implemented")
    }

    override fun getTestMethod(): Optional<Method> {
        TODO("Not yet implemented")
    }

    override fun getExecutionException(): Optional<Throwable> {
        TODO("Not yet implemented")
    }

    override fun getConfigurationParameter(key: String?): Optional<String> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> getConfigurationParameter(key: String?, transformer: Function<String, T>?): Optional<T> {
        TODO("Not yet implemented")
    }

    override fun publishReportEntry(map: MutableMap<String, String>?) {
        TODO("Not yet implemented")
    }

    override fun getStore(namespace: ExtensionContext.Namespace?): ExtensionContext.Store {
        TODO("Not yet implemented")
    }

    override fun getExecutionMode(): ExecutionMode {
        TODO("Not yet implemented")
    }

    override fun getExecutableInvoker(): ExecutableInvoker {
        TODO("Not yet implemented")
    }
}
