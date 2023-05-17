package pl.allegro.tech.servicemesh.envoycontrol.utils

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceName
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.util.concurrent.ConcurrentHashMap

class GzipUtilsTest {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val gzipUtils: GzipUtils = GzipUtils(objectMapper)

    @Test
    fun `should compress object`() {
        val servicesState = ServicesState(
            serviceNameToInstances = ConcurrentHashMap<ServiceName, ServiceInstances>(
                mapOf(
                    "service2" to ServiceInstances("service2", setOf()),
                    "service" to ServiceInstances("service", setOf())
                )
            )
        )
        val zipped = gzipUtils.gzip(servicesState)
        val unzipped = gzipUtils.unGzip(zipped, ServicesState::class.java)
        Assertions.assertThat(servicesState)
            .isEqualTo(unzipped)
    }

    @Test
    fun `should compress map`() {
        val map = mapOf("someKey" to "someValue")
        val zipped = gzipUtils.gzip(map)
        val unGzipped = gzipUtils.unGzip(zipped, Map::class.java)
        Assertions.assertThat(unGzipped)
            .isEqualTo(map)
    }

    @Test
    fun `should fail with IllegalStateException on empty bytearray`() {
        Assertions.assertThatThrownBy { gzipUtils.unGzip(byteArrayOf(), Any::class.java) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
