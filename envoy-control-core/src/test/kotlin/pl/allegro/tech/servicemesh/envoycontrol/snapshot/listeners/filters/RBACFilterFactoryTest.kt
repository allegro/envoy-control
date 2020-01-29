package pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters

import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.config.rbac.v2.Permission
import io.envoyproxy.envoy.config.rbac.v2.Policy
import io.envoyproxy.envoy.config.rbac.v2.Principal
import io.envoyproxy.envoy.config.rbac.v2.RBAC
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.Incoming
import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingEndpoint
import pl.allegro.tech.servicemesh.envoycontrol.groups.PathMatchingType
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties
import org.assertj.core.api.Assertions.assertThat

internal class RBACFilterFactoryTest {
    val rbacFilterFactory = RBACFilterFactory(IncomingPermissionsProperties())

    val expected = RBAC.newBuilder().putAllPolicies(mapOf(
            "client1,client2" to Policy.newBuilder().addAllPrincipals(listOf(
                    Principal.newBuilder().setHeader(
                            HeaderMatcher.newBuilder().setName("x-service-name").setExactMatch("client1").build()
                    ).build(),
                    Principal.newBuilder().setHeader(
                            HeaderMatcher.newBuilder().setName("x-service-name").setExactMatch("client2").build()
                    ).build()
            )).addAllPermissions(listOf(
                Permission.newBuilder().setAndRules(
                    Permission.Set.newBuilder().addAllRules(
                            listOf(
                                    Permission.newBuilder().setHeader(
                                            HeaderMatcher.newBuilder()
                                                    .setName(":path").setExactMatch("/example").build()
                                    ).build(),
                                Permission.newBuilder().setOrRules(
                                        Permission.Set.newBuilder().addAllRules(
                                                listOf(
                                                    Permission.newBuilder().setHeader(
                                                            HeaderMatcher.newBuilder()
                                                                    .setName(":method").setExactMatch("GET").build()
                                                    ).build(), Permission.newBuilder().setHeader(
                                                            HeaderMatcher.newBuilder()
                                                                    .setName(":method").setExactMatch("POST").build()
                                                    ).build()
                                                )
                                        ).build()
                                ).build()
                            )
                    )
                ).build()
            )).build()
    )).build()

    @Test
    fun `should generate RBAC rules for simple incoming permissions`() {
        // given
        val incomingPermission = Incoming(
                endpoints = listOf(IncomingEndpoint(
                        "/example",
                        PathMatchingType.PATH,
                        setOf("GET", "POST"),
                        setOf("client1", "client2")
                ))
        )

        // when
        val generated = rbacFilterFactory.getRules(incomingPermission)

        // then
        assertThat(generated).isEqualTo(expected)
    }
}
