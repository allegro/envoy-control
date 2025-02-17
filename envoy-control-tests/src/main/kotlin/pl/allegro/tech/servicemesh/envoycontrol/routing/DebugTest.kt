@file:Suppress("DANGEROUS_CHARACTERS", "PrivatePropertyName", "ObjectPropertyName", "ObjectPrivatePropertyName")

package pl.allegro.tech.servicemesh.envoycontrol.routing

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ObjectAssert
import org.assertj.core.api.StringAssert
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.BindMode
import org.testcontainers.shaded.org.bouncycastle.math.raw.Mod
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
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.routing.Assertions.failsAtStartupWithError
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`baseConfig with node metadata default_service_tag_preference`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`config that correctly logs %CEL()% in access log`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`config with CEL in accessLog`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Modifications.`access log stdout config`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Modifications.`add CELL formatter to access log`
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

private val baseConfig = ConfigYaml("/envoy/debug/config_base.yaml")

class CELTest {
    companion object {
        private object Configs {
            val `baseConfig with node metadata default_service_tag_preference` = baseConfig.modify {
                //language=yaml
                this += """
              node:
                metadata:
                  default_service_tag_preference: lvte1|vte2|global
            """.trimIndent()
            }

            val `config with CEL in accessLog` = `baseConfig with node metadata default_service_tag_preference`.modify {
                `access log stdout config`(this)
                listeners {
                    egress {
                        //language=yaml
                        http.at("/access_log/typed_config/log_format") += """
                          text_format: "CEL = '%CEL(xds.node.metadata.default_service_tag_preference)%'\n"
                        """.trimIndent()
                    }
                }
            }

            val `config that correctly logs %CEL()% in access log` = `config with CEL in accessLog`
                .modify(`add CELL formatter to access log`)
        }

        private val `CEL failure log` =
            "[critical].*error initializing config .* Not supported field in StreamInfo: CEL".toRegex()

        private object Modifications {
            val `access log stdout config`: ConfigYaml.Modification.() -> Unit = {
                listeners {
                    egress {
                        //language=yaml
                        http += """
                            access_log:
                              name: envoy.access_loggers.stdout
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog
                        """.trimIndent()
                    }
                }
            }
            val `add CELL formatter to access log`: ConfigYaml.Modification.() -> Unit = {
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

        }
    }

    @Nested
    inner class AccessLog : AccessLogTest()
    open class AccessLogTest {

        fun EnvoyExtension.start() = try {
            beforeAll(contextMock)
        } finally {
            afterAll(contextMock)
        }


        @Test
        fun `use %CEL()% in access log without any other config fails`() {
            // given
            val envoy = staticEnvoyExtension(config = `config with CEL in accessLog`).asClosable()

            // expects
            assertThat(envoy).failsAtStartupWithError(`CEL failure log`)

        }


        @Test
        fun `use %CEL()% in access log with explicitly enabled CEL formatter works`() {
            // given
            val envoy = staticEnvoyExtension(config = `config that correctly logs %CEL()% in access log`).asClosable()
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

    @Nested
    inner class Header : HeaderTest()
    open class HeaderTest {

        private val `request_headers_to_add with %CEL()%`: ConfigYaml.Modification.() -> Unit = {
            listeners {
                egress {
                    //language=yaml
                    http.at("/route_config/request_headers_to_add") += """
                          - header:
                              key: x-service-tag-preference-from-node-metadata
                              value: "%CEL(xds.node.metadata.default_service_tag_preference)%"
                        """.trimIndent()
                }
            }
        }

        private val `config with request_headers_to_add with %CEL()%` = `baseConfig with node metadata default_service_tag_preference`
            .modify(`request_headers_to_add with %CEL()%`)

        @Test
        fun `%CEL()% not working without additional config`() {
            // given
            val envoy = staticEnvoyExtension(config = `config with request_headers_to_add with %CEL()%`)
                .asClosable()

            // expects
            assertThat(envoy).failsAtStartupWithError(`CEL failure log`)
        }

        @Test
        fun `%CEL%() not working with CEL formatter added in access log`() {
            // given
            val config = `config with request_headers_to_add with %CEL()%`
                .modify {
                    `access log stdout config`(this)
                    `add CELL formatter to access log`(this)
                }

            val envoy = staticEnvoyExtension(config = config)
                .asClosable()

            // expects
            assertThat(envoy).failsAtStartupWithError(`CEL failure log`)
        }

        @Test
        fun `%CEL%() not working even when %CEL()% in access log is configured and working`() {
            // given
            val config = `config that correctly logs %CEL()% in access log`
                .modify(`request_headers_to_add with %CEL()%`)

            val envoy = staticEnvoyExtension(config = config)
                .asClosable()

            // expects
            assertThat(envoy).failsAtStartupWithError(`CEL failure log`)
        }
    }
}

private object Assertions {
    fun ObjectAssert<ClosableEnvoyExtension>.failsAtStartupWithError(expectedFailureLog: Regex) = satisfies({
        it.extension.container.logRecorder.recordLogs { log ->
            expectedFailureLog.containsMatchIn(log)
        }

        assertThatThrownBy { it.use(ClosableEnvoyExtension::start) }

        val failureLogs = it.extension.container.logRecorder.getRecordedLogs()

        assertThat(failureLogs, StringAssert::class.java)
            .first().containsPattern(expectedFailureLog.pattern)
    })
}

private class ConfigYaml private constructor(private val config: ObjectNode) {

    private object S {
        val yaml = Yaml()
        val json = ObjectMapper()

        fun parse(yaml: String): JsonNode = json.valueToTree(S.yaml.load(yaml))
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

    @DslMarker
    annotation class Dsl

    sealed interface Modification : YamlNode {
        fun listeners(block: Listeners.() -> Unit)
    }
    interface Listeners : YamlNode {
        fun egress(block: Listener.() -> Unit)
    }

    interface Listener : YamlNode{
        val http: YamlNode
    }

    private inner class ModificationImpl private constructor(
        private val node: YamlObjectNode
    ) : Modification, YamlNode by node {

        constructor() : this(node = YamlObjectNode(config))

        fun (YamlNode).materializeArray(): YamlArrayNode = when(val n = this) {
            is YamlArrayNode -> n
            is MissingNode -> n.materializeArray()
            else -> throw IllegalArgumentException("Cannot create array here")
        }

        fun (YamlNode).materializeObject(): YamlObjectNode = when(val n = this) {
            is YamlObjectNode -> n
            is MissingNode -> n.materializeObject()
            else -> throw IllegalArgumentException("Cannot create object here")
        }

        override fun listeners(block: ConfigYaml.Listeners.() -> Unit) {
            Listeners(node.at("/static_resources/listeners").materializeArray()).block()
        }

        private inner class Listeners(private val node: YamlArrayNode) : ConfigYaml.Listeners, YamlNode by node {
            override fun egress(block: ConfigYaml.Listener.() -> Unit) {
                Listener(parentNode = node, key = "egress").block()
            }

            private inner class Listener(node: YamlObjectNode) : ConfigYaml.Listener, YamlNode by node {

                constructor(key: String, parentNode: YamlArrayNode) : this(
                    node = YamlObjectNode(
                        node = parentNode.node.single { it["name"].asText() == key } as ObjectNode
                    )
                )

                override val http: YamlNode by lazy {
                    node.node["filter_chains"].single()["filters"]
                        .single { it["name"].asText() == "envoy.filters.network.http_connection_manager" }
                        .get("typed_config")
                        .let { YamlObjectNode(it as ObjectNode) }
                }
            }
        }

    }

    @Dsl
    sealed interface YamlNode {
        operator fun plusAssign(mergeYaml: String)
        fun at(path: String): YamlNode
    }

    private abstract class ExistingNode(open val node: JsonNode) : YamlNode {
        override fun at(path: String): YamlNode {
            val pointer = JsonPointer.compile(path)
            return node.at(pointer).let {
                when {
                    it.isMissingNode -> MissingNode(parentNode = this.node, pointer = pointer)
                    it is ObjectNode -> YamlObjectNode(node = it)
                    it is ArrayNode -> YamlArrayNode(node = it)
                    else -> throw IllegalArgumentException("Unknown node type $it")
                }
            }
        }

        override fun plusAssign(mergeYaml: String) = plus(S.parse(mergeYaml))
        abstract fun plus(mergeYaml: JsonNode)
    }

    private class YamlObjectNode(override val node: ObjectNode) : ExistingNode(node) {
        override fun plus(mergeYaml: JsonNode) {
            node.setAll<ObjectNode>(mergeYaml as ObjectNode)
        }
    }
    private class YamlArrayNode(override val node: ArrayNode) : ExistingNode(node) {
        override fun plus(mergeYaml: JsonNode) {
            node.addAll(mergeYaml as ArrayNode)
        }
    }

    private class MissingNode(private val parentNode: JsonNode, private val pointer: JsonPointer) : YamlNode {
        private var existingNode: ExistingNode? = null

        fun materializeArray(): YamlArrayNode {
            existingNode?.let {
                return it as YamlArrayNode
            }
            return YamlArrayNode(node = parentNode.withArray(pointer))
                .also { existingNode = it }
        }

        fun materializeObject(): YamlObjectNode {
            existingNode?.let {
                return it as YamlObjectNode
            }
            return YamlObjectNode(node = parentNode.withObject(pointer))
                .also { existingNode = it }
        }

        override fun plusAssign(mergeYaml: String) {
            val parsed = S.parse(mergeYaml)
            existingNode = when (parsed) {
                is ArrayNode -> materializeArray()
                is ObjectNode -> materializeObject()
                else -> throw IllegalArgumentException("Unknown node type '$parsed'")
            }.also {
                it.plus(parsed)
            }
        }

        override fun at(path: String): MissingNode =
            MissingNode(parentNode = parentNode, pointer = pointer.append(JsonPointer.compile(path)))
    }


    fun modify(block: Modification.() -> Unit): ConfigYaml {
        val modified = ConfigYaml(config = config.deepCopy())
        block(modified.ModificationImpl())
        return modified
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
