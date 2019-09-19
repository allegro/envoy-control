package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ConsulServiceMapperTest {

    @Test
    fun `should map consul service to domain service`() {
        // given
        val mapper = ConsulServiceMapper()
        val consulInstance = ConsulServiceInstance("echo.12345", listOf("canary", "weight:5"), "192.168.199.1", 5000)

        // when
        val domainInstance = mapper.toDomainInstance(consulInstance)

        // then
        assertThat(domainInstance.id).isEqualTo("echo.12345")
        assertThat(domainInstance.address).isEqualTo("192.168.199.1")
        assertThat(domainInstance.port).isEqualTo(5000)
        assertThat(domainInstance.tags).isEqualTo(setOf("canary", "weight:5"))
        assertThat(domainInstance.canary).isFalse()
        assertThat(domainInstance.weight).isEqualTo(1)
    }

    @Test
    fun `should assign proper weight`() {
        // given
        val mapper = ConsulServiceMapper(weightTag = "weight", defaultWeight = 5)

        val instanceWithoutWeight = consulInstance(tags = listOf())
        val instanceWithWeight = consulInstance(tags = listOf("weight:20"))
        val instanceWithMultipleWeights = consulInstance(tags = listOf("weight:8", "weight:13"))
        val instanceWithTooLowWeight = consulInstance(tags = listOf("weight:0"))
        val instanceWithInvalidWeight = consulInstance(tags = listOf("weight:heavy"))

        instanceWithoutWeight.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.weight).isEqualTo(5)
        }

        instanceWithWeight.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.weight).isEqualTo(20)
        }

        instanceWithMultipleWeights.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.weight).isEqualTo(8)
        }

        instanceWithTooLowWeight.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.weight).isEqualTo(1)
        }

        instanceWithInvalidWeight.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.weight).isEqualTo(5)
        }
    }

    @Test
    fun `should set canary status`() {
        // given
        val mapper = ConsulServiceMapper(canaryTag = "canary")

        val canaryInstance = consulInstance(tags = listOf("canary"))
        val regularInstance = consulInstance(tags = listOf())

        canaryInstance.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.canary).isTrue()
        }

        regularInstance.let { consulInstance ->
            // when
            val domainInstance = mapper.toDomainInstance(consulInstance)

            // then
            assertThat(domainInstance.canary).isFalse()
        }
    }

    private fun consulInstance(tags: List<String> = listOf()) = ConsulServiceInstance(
        serviceId = UUID.randomUUID().toString(),
        serviceAddress = "192.168.192." + (1..200).random(),
        servicePort = (5000..6000).random(),
        serviceTags = tags
    )
}
