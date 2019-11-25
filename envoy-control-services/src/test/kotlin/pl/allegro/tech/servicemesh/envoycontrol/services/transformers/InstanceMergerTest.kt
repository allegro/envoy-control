package pl.allegro.tech.servicemesh.envoycontrol.services.transformers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

internal class InstanceMergerTest {

    @Test
    fun `should merge instances with the same ip and port`() {
        // given
        val merger = InstanceMerger()
        val instances = sequenceOf(
            ServiceInstances(
                "lorem",
                setOf(
                    ServiceInstance(
                        id = "lorem-1",
                        tags = setOf("version:v1.0", "hardware:c32"),
                        address = "127.0.0.2",
                        port = 8888,
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
                        port = 6010,
                        canary = true,
                        regular = false,
                        weight = 1
                    ),
                    ServiceInstance(
                        id = "ipsum-3",
                        tags = setOf("hardware:c32", "index:users"),
                        address = "127.0.0.2",
                        port = 8888,
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
                        id = "lorem-1",
                        tags = setOf("version:v1.0", "hardware:c32"),
                        address = "127.0.0.2",
                        port = 8888,
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
                    )
                )
            ),
            ServiceInstances(
                "ipsum",
                setOf(
                    ServiceInstance(
                        id = "ipsum-1,ipsum-3",
                        tags = setOf("hardware:c32", "index:items", "index:users"),
                        address = "127.0.0.2",
                        port = 8888,
                        canary = true,
                        regular = true,
                        weight = 35
                    ),
                    ServiceInstance(
                        id = "ipsum-2",
                        tags = setOf("hardware:c64", "index:transactions"),
                        address = "127.0.0.7",
                        port = 6010,
                        canary = true,
                        regular = false,
                        weight = 1
                    )
                )
            )
        )

        // when
        val mergedInstances = merger.transform(instances)

        // then
        assertThat(mergedInstances.toList()).isEqualTo(expectedMergedInstances.toList())
    }
}
