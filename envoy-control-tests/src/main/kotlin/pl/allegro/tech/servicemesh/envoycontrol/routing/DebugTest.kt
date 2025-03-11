@file:Suppress(
    "DANGEROUS_CHARACTERS", "PrivatePropertyName", "ObjectPropertyName", "ObjectPrivatePropertyName",
    "ClassName"
)

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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
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
import pl.allegro.tech.servicemesh.envoycontrol.config.service.asHttpsEchoResponse
import pl.allegro.tech.servicemesh.envoycontrol.routing.Assertions.failsAtStartupWithError
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`baseConfig with node metadata default_service_tag_preference`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`config that correctly logs %CEL()% in access log`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Configs.`config with CEL in accessLog`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Modifications.`access log stdout config`
import pl.allegro.tech.servicemesh.envoycontrol.routing.CELTest.Companion.Modifications.`add CELL formatter to access log`
import pl.allegro.tech.servicemesh.envoycontrol.routing.EchoExtension.echoService
import pl.allegro.tech.servicemesh.envoycontrol.routing.HeaderFromEnvironmentTest.`environment variable present test`
import pl.allegro.tech.servicemesh.envoycontrol.routing.HeaderFromEnvironmentTest.`environment variable present test`.Companion
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.Optional
import java.util.function.Function
import kotlin.io.path.createTempFile

object EchoExtension {
    val echoService = GenericServiceExtension(HttpsEchoContainer())
}

class DefaultServiceTagPreferenceTest {

    companion object {
        @JvmField
        @RegisterExtension
        val envoy = staticEnvoyExtension(
            config = baseConfig,
            localService = echoService
        ).apply { container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte2|vte3|global") }
    }

    @Test
    fun debug() {
        val adminUrl = envoy.container.adminUrl()
        val egressUrl = envoy.container.egressListenerUrl()

        assertThat(1).isEqualTo(1)
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
            val `access log stdout config` = ConfigYaml.modification {
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

            val `add CELL formatter to access log` = ConfigYaml.modification {
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
                it.extension.container.logRecorder.recordLogs { it.contains(expectedAccessLog) }

                // when
                it.extension.egressOperations.callService("backend")

                // then
                untilAsserted {
                    val accessLogs = it.extension.container.logRecorder.getRecordedLogs()
                    assertThat(accessLogs, StringAssert::class.java)
                        .first().contains(expectedAccessLog)
                }
            }
        }
    }

    @Nested
    inner class Header : HeaderTest()
    open class HeaderTest {

        private val `request_headers_to_add with %CEL()%` = ConfigYaml.modification {
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

        private val `config with request_headers_to_add with %CEL()%` =
            `baseConfig with node metadata default_service_tag_preference`
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

class HeaderFromEnvironmentTest {

    companion object {
        private val config = baseConfig.modify {
            listeners {
                egress {
                    //language=yaml
                    http.at("/route_config/request_headers_to_add") setYaml """
                          - header:
                              key: x-service-tag-preference-from-env
                              value: "%ENVIRONMENT(DEFAULT_SERVICE_TAG_PREFERENCE)%"
                            append_action: ADD_IF_ABSENT   
                        """.trimIndent()
                }
            }
        }
    }

    @Nested
    inner class `environment variable present` : `environment variable present test`()
    open class `environment variable present test` {

        companion object {
            @JvmField
            @RegisterExtension
            val envoy = staticEnvoyExtension(config = config, localService = echoService)
                .apply { container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte2|vte3|global") }
        }

        @Test
        fun `%ENVIRONMENT()% in header works`() {

            // when
            val response = envoy.egressOperations.callService("echo").asHttpsEchoResponse()

            // then
            assertThat(response.requestHeaders).containsEntry("x-service-tag-preference-from-env", "lvte2|vte3|global")
        }

        @Test
        fun `should not override header from request`() {
            // when
            val response = envoy.egressOperations.callService(
                service = "echo",
                headers = mapOf("x-service-tag-preference-from-env" to "override-in-request")
            ).asHttpsEchoResponse()

            // then
            assertThat(response.requestHeaders).containsEntry(
                "x-service-tag-preference-from-env", "override-in-request"
            )
        }
    }

    @Nested
    inner class `environment variable not set` : `environment variable not set test`()
    open class `environment variable not set test` {

        companion object {
            @JvmField
            @RegisterExtension
            val envoy = staticEnvoyExtension(config = config, localService = echoService)
        }

        @Test
        fun `it sets the header to '-' value`() {
            // when
            val response = envoy.egressOperations.callService("echo").asHttpsEchoResponse()

            // then
            assertThat(response.requestHeaders).containsEntry("x-service-tag-preference-from-env", "-")
        }
    }

    @Nested
    inner class `keep_empty_value=false and env var not set` : `keep_empty_value=false test`()
    open class `keep_empty_value=false test` {

        companion object {
            private val `config with keep_empty_value=false` = config.modify {
                listeners {
                    egress {
                        //language=yaml
                        http.at("/route_config/request_headers_to_add/0") += """
                            keep_empty_value: false
                        """.trimIndent()
                    }
                }
            }

            @JvmField
            @RegisterExtension
            val envoy = staticEnvoyExtension(config = `config with keep_empty_value=false`, localService = echoService)
        }

        @Test
        fun `still sets the header to '-' value`() {
            // when
            val response = envoy.egressOperations.callService("echo").asHttpsEchoResponse()

            // then
            assertThat(response.requestHeaders).containsEntry("x-service-tag-preference-from-env", "-")
        }
    }

    @Nested
    inner class `keep_empty_value=true and env var not set` : `keep_empty_value=true test`()
    open class `keep_empty_value=true test` {

        companion object {
            private val `config with keep_empty_value=true` = config.modify {
                listeners {
                    egress {
                        //language=yaml
                        http.at("/route_config/request_headers_to_add/0") += """
                            keep_empty_value: true
                        """.trimIndent()
                    }
                }
            }

            @JvmField
            @RegisterExtension
            val envoy = staticEnvoyExtension(config = `config with keep_empty_value=true`, localService = echoService)
        }

        @Test
        fun `still sets the header to '-' value`() {
            // when
            val response = envoy.egressOperations.callService("echo").asHttpsEchoResponse()

            // then
            assertThat(response.requestHeaders).containsEntry("x-service-tag-preference-from-env", "-")
        }
    }
}

class HeaderFromEnvironmentLuaTest {

    private companion object {

        //language=lua
        private val luaScript = """
          function envoy_on_request(handle)
            local defaultServiceTagPreference = os.getenv("DEFAULT_SERVICE_TAG_PREFERENCE")
            handle:logInfo("DEFAULT_SERVICE_TAG_PREFERENCE = "..defaultServiceTagPreference)
            
            if not handle:headers():get("x-service-tag-preference-from-lua") then
              handle:headers():add("x-service-tag-preference-from-lua", defaultServiceTagPreference)
            end
          end
          
          function envoy_on_response(handle)
          end
        """.trimIndent()

        private val config = baseConfig.modify {
            listeners {
                egress {
                    //language=yaml
                    http.at("/http_filters/0").before setYaml """
                        name: lua
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
                    """.trimIndent()

                    http.at("/http_filters/0/typed_config/inline_code") set luaScript
                }
            }
        }

        @JvmField
        @RegisterExtension
        val envoy = staticEnvoyExtension(config = config, localService = echoService)
            .apply { container.withEnv("DEFAULT_SERVICE_TAG_PREFERENCE", "lvte7|vte8|global") }
    }

    @Test
    fun `set header from environment in lua script`() {

        // when
        val response = envoy.egressOperations.callService("echo").asHttpsEchoResponse()

        // then
        assertThat(response.requestHeaders).containsEntry("x-service-tag-preference-from-lua", "lvte7|vte8|global")
    }

    @Test
    fun `should not override header from request`() {
        // when
        val response = envoy.egressOperations.callService(
            service = "echo",
            headers = mapOf("x-service-tag-preference-from-lua" to "override-in-request")
        ).asHttpsEchoResponse()

        // then
        assertThat(response.requestHeaders).containsEntry(
            "x-service-tag-preference-from-lua", "override-in-request"
        )
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

    companion object {
        fun modification(action: Modification.() -> Unit) = action
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

    interface Listener : YamlNode {
        val http: YamlNode
    }

    private inner class ModificationImpl private constructor(
        private val node: YamlObjectNode
    ) : Modification, YamlNode by node {

        constructor() : this(node = YamlObjectNode(node = config, abs = Abs.root))

        fun (YamlNode).materializeArray(): YamlArrayNode = when (val n = this) {
            is YamlArrayNode -> n
            is MissingNode -> n.materializeArray()
            else -> throw IllegalArgumentException("Cannot create array here")
        }

        fun (YamlNode).materializeObject(): YamlObjectNode = when (val n = this) {
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
                    node = kotlin.run {
                        val indexedNode = parentNode.node.withIndex().single { it.value["name"].asText() == key }
                        parentNode.at("/${indexedNode.index}") as YamlObjectNode
                    }
                )

                override val http: YamlNode by lazy {
                    node.at("/filter_chains")
                        .let { it as YamlArrayNode }
                        .single().at("/filters")
                        .let { it as YamlArrayNode }
                        .single { it.node["name"].asText() == "envoy.filters.network.http_connection_manager" }
                        .at("/typed_config")
                }
            }
        }
    }

    @Dsl
    sealed interface YamlNode {
        operator fun plusAssign(mergeYaml: String)
        infix fun set(scalar: String)
        infix fun setYaml(yaml: String)
        fun at(path: String): YamlNode
        val before: YamlNode
    }

    private class Abs private constructor(private val root: YamlNodeBase?, val path: JsonPointer) {
        companion object {
            val root = Abs(root = null, path = JsonPointer.empty())
        }
        fun root(node: YamlNodeBase) = root ?: node
        fun append(node: YamlNodeBase, tail: JsonPointer) = Abs(root = root(node), path = path.append(tail))

    }

    private abstract class YamlNodeBase : YamlNode {
        abstract val abs: Abs

        override fun setYaml(yaml: String) = setYaml(S.parse(yaml))
        abstract fun setYaml(yaml: JsonNode)

        override fun plusAssign(mergeYaml: String) = plusAssign(S.parse(mergeYaml))
        abstract fun plusAssign(mergeYaml: JsonNode)
        override fun at(path: String): YamlNodeBase = at(JsonPointer.compile(path))
        abstract fun at(path: JsonPointer): YamlNodeBase
        abstract fun insertAt(index: Int): YamlNode

        override val before: YamlNode
            get() {
                val index = abs.path.last()?.matchingIndex?.takeIf { it >= 0 }
                if (index == null) {
                    throw IllegalArgumentException("cannot insert anything before here")
                }
                return abs.root(this).at(abs.path.head()).insertAt(index)
            }
    }

    private abstract class ExistingNode : YamlNodeBase() {
        abstract val node: JsonNode

        override fun setYaml(yaml: JsonNode) {
            node.removeAll { true }
            plusAssign(yaml)
        }

        override fun at(path: JsonPointer): YamlNodeBase {
            return node.at(path).let {
                val absChild = this.abs.append(this, path)
                when {
                    it.isMissingNode -> MissingNode(parentNode = this, relativePointer = path, abs = absChild)
                    it is ObjectNode -> YamlObjectNode(node = it, abs = absChild)
                    it is ArrayNode -> YamlArrayNode(node = it, abs = absChild)
                    else -> throw IllegalArgumentException("Unknown node type $it")
                }
            }
        }

        override fun set(scalar: String) {
            TODO("Not yet implemented")
        }
    }

    private class YamlObjectNode(override val node: ObjectNode, override val abs: Abs) : ExistingNode() {

        override fun plusAssign(mergeYaml: JsonNode) {
            node.setAll<ObjectNode>(mergeYaml as ObjectNode)
        }

        override fun insertAt(index: Int): YamlNode {
            throw UnsupportedOperationException("Cannot insert at - this is an object, not array")
        }
    }

    private class YamlArrayNode(override val node: ArrayNode, override val abs: Abs) :
        ExistingNode(), Iterable<ExistingNode> {

        override fun plusAssign(mergeYaml: JsonNode) {
            node.addAll(mergeYaml as ArrayNode)
        }

        override fun insertAt(index: Int): YamlNode {
            node.insert(index, node.nullNode())
            val indexSegment = JsonPointer.compile("/${index}")
            return MissingNode(
                parentNode = this,
                relativePointer = indexSegment,
                abs = abs.append(this, indexSegment)
            )
        }

        override fun iterator(): Iterator<ExistingNode> {
            return object : Iterator<ExistingNode> {
                private val iter = node.iterator().withIndex()
                override fun hasNext(): Boolean = iter.hasNext()
                override fun next(): ExistingNode =
                    at(JsonPointer.empty().appendIndex(iter.next().index)) as ExistingNode
            }
        }
    }

    private class MissingNode(
        private val parentNode: ExistingNode,
        private val relativePointer: JsonPointer,
        override val abs: Abs
    ) : YamlNodeBase() { // (path = parentNode.path.append(relativePointer)) {

        private var existingNode: ExistingNode? = null

        override fun setYaml(yaml: JsonNode) = materialize(yaml).setYaml(yaml)
        override fun plusAssign(mergeYaml: JsonNode) = materialize(mergeYaml).plusAssign(mergeYaml)
        override fun at(path: JsonPointer): YamlNodeBase {
            val absChild = this.abs.append(this, path)
            return MissingNode(parentNode = parentNode, relativePointer = relativePointer.append(path), abs = absChild)
        }

        override fun insertAt(index: Int): YamlNode = materializeArray().insertAt(index)

        override fun set(scalar: String) {
            if (existingNode != null) {
                throw IllegalArgumentException("cannot set it to string, it's already a node")
            }

            val head = relativePointer.head()
                ?: throw IllegalStateException("something wong - it shouldn't have happen")
            val tail = relativePointer.last()

            parentNode.node.withObject(relativePointer.head())
                .put(tail.matchingProperty, scalar)
        }

        fun materializeArray(): YamlArrayNode {
            existingNode?.let {
                return it as YamlArrayNode
            }
            return YamlArrayNode(node = parentNode.node.withArray(relativePointer), abs = abs)
                .also { existingNode = it }
        }

        fun materializeObject(): YamlObjectNode {
            existingNode?.let {
                return it as YamlObjectNode
            }
            return YamlObjectNode(node = parentNode.node.withObject(relativePointer), abs = abs)
                .also { existingNode = it }
        }

        private fun materialize(yaml: JsonNode): ExistingNode = when (yaml) {
            is ArrayNode -> materializeArray()
            is ObjectNode -> materializeObject()
            else -> throw IllegalArgumentException("Unknown node type '$yaml'")
        }
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







class DebugExtensionsTest() {
    companion object {
        @JvmField
        @RegisterExtension
        val extension = DebugExtension("main")

        @JvmStatic
        @BeforeAll
        fun parentBeforeAll() {
            println("PARENT BEFORE ALL")
        }
    }

    @Nested
    inner class NestedTest : NestedTestB()
    open class NestedTestB {

        companion object {
            @JvmField
            @RegisterExtension
            val subextension = DebugExtension("sub")

            @JvmStatic
            @BeforeAll
            fun nestedBeforeAll() {
                println("NESTED BEFORE ALL")
            }
        }

        @Test
        fun `unfortunately extension's beforeAll is called twice instead of once`(ctx: TestReporter) {
            ctx.publishEntry("Tylko")
            println(extension.report())
            assertThat(extension.beforeAllCalled).isEqualTo(2)
            assertThat(extension.beforeAllCalledGuarded).isEqualTo(1)

            println(subextension.report())
            assertThat(subextension.beforeAllCalled).isEqualTo(1)
            assertThat(subextension.beforeAllCalledGuarded).isEqualTo(1)
        }
    }

    @Test
    fun `when running only this test extension's beforeAll is called once`() {
        println(extension.report())
        assertThat(extension.beforeAllCalled).isEqualTo(1)
        assertThat(extension.beforeAllCalledGuarded).isEqualTo(1)
    }
}

class DebugExtension(val name: String) : BeforeAllCallback, AfterAllCallback {
    var beforeAllCalled = 0
    var beforeAllCalledGuarded = 0
    var afterAllCalled = 0

    var contextId: String? = null
    var terminated = false

    override fun beforeAll(p0: ExtensionContext?) {
        beforeAllCalled++
        if (contextId != null) {
            return
        }
        contextId = p0?.uniqueId
        beforeAllCalledGuarded++
    }

    override fun afterAll(p0: ExtensionContext?) {
        afterAllCalled++
        if (terminated) {
            throw IllegalStateException("CALLED AFTER TERMINATED!")
        }
        if (p0?.uniqueId == contextId) {
            terminated = true
        }
    }

    fun report(): String = "DebugExtension[$name] called: [beforeAll: $beforeAllCalled, afterAll: $afterAllCalled]"
}
