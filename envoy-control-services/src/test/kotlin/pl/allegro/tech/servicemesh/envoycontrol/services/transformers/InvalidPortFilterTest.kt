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
                "lorem",
                setOf(
                    ServiceInstance(
                        id = "lorem-1",
                        tags = setOf("version:v1.0", "secure"),
                        address = "127.0.0.2",
                        port = 0,
                        canary = false,
                        regular = true,
                        weight = 10
                    ),
                    ServiceInstance(
                        id = "lorem-2",
                        tags = setOf("version:v1.1", "hardware:c32"),
                        address = "127.0.0.5",
                        port = 8888,
                        canary = false,
                        regular = true,
                        weight = 10
                    ),
                    ServiceInstance(
                        id = "lorem-3",
                        tags = setOf("version:v1.1", "canary"),
                        address = "127.0.0.9",
                        port = 0,
                        canary = true,
                        regular = true,
                        weight = 10
                    ),
                    ServiceInstance(
                        id = "lorem-4",
                        tags = setOf("version:v1.1", "machine:1337"),
                        address = "127.0.0.11",
                        port = 8080,
                        canary = false,
                        regular = true,
                        weight = 10
                    )
                )
            ),
            ServiceInstances(
                "ipsum",
                setOf(
                    ServiceInstance(
                        id = "ipsum-1",
                        tags = setOf("hardware:c32", "index:items"),
                        address = "127.0.0.2",
                        port = 8888,
                        canary = true,
                        regular = false,
                        weight = 15
                    ),
                    ServiceInstance(
                        id = "ipsum-2",
                        tags = setOf("hardware:c64", "index:transactions"),
                        address = "127.0.0.7",
                        port = 0,
                        canary = true,
                        regular = false,
                        weight = 1
                    ),
                    ServiceInstance(
                        id = "ipsum-3",
                        tags = setOf("hardware:c32", "index:users"),
                        address = "127.0.0.2",
                        port = 0,
                        canary = false,
                        regular = true,
                        weight = 20
                    )
                )
            )
        )

        val expectedMergedInstances = sequenceOf(
            ServiceInstances(
                "lorem",
                setOf(
                    ServiceInstance(
                        id = "lorem-2",
                        tags = setOf("version:v1.1", "hardware:c32"),
                        address = "127.0.0.5",
                        port = 8888,
                        canary = false,
                        regular = true,
                        weight = 10
                    ),
                    ServiceInstance(
                        id = "lorem-4",
                        tags = setOf("version:v1.1", "machine:1337"),
                        address = "127.0.0.11",
                        port = 8080,
                        canary = false,
                        regular = true,
                        weight = 10
                    )
                )
            ),
            ServiceInstances(
                "ipsum",
                setOf(
                    ServiceInstance(
                        id = "ipsum-1",
                        tags = setOf("hardware:c32", "index:items"),
                        address = "127.0.0.2",
                        port = 8888,
                        canary = true,
                        regular = false,
                        weight = 15
                    )
                )
            )
        )

        // when
        val filteredInstances = filter.transform(instances)

        // then
        assertThat(filteredInstances.toList()).isEqualTo(expectedMergedInstances.toList())
    }
}
