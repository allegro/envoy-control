package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

internal class InvalidPortFilterTest {

    @Test
    fun `should filter out instances with port setup as zero`() {
        // given
        val filter = InvalidPortFilter()
        val instances = sequenceOf(
            ServiceInstances(
                "ipsum",
                setOf(
                    createServiceInstanceWithPort(8888),
                    createServiceInstanceWithPort(null),
                    createServiceInstanceWithPort(8080),
                    createServiceInstanceWithPort(null)
                )
            ),
            ServiceInstances(
                "ipsum",
                setOf(
                    createServiceInstanceWithPort(8888),
                    createServiceInstanceWithPort(null),
                    createServiceInstanceWithPort(null)
                )
            )
        )

        val expectedMergedInstances = sequenceOf(
            ServiceInstances(
                "ipsum",
                setOf(
                    createServiceInstanceWithPort(8888),
                    createServiceInstanceWithPort(8080)
                )
            ),
            ServiceInstances(
                "ipsum",
                setOf(
                    createServiceInstanceWithPort(8888)
                )
            )
        )

        // when
        val filteredInstances = filter.transform(instances)

        // then
        assertThat(filteredInstances.toList()).isEqualTo(expectedMergedInstances.toList())
    }

    private fun createServiceInstanceWithPort(port: Int?): ServiceInstance{
        return ServiceInstance(
            id = "ipsum-$port",
            tags = setOf("hardware:c32", "index:items", port.toString()),
            address = "127.0.0.$port",
            port = port
        )
    }
}
