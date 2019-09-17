package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.api.v2.core.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetadataNodeGroupTest {
    @Test
    fun `should assign to group with all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(allServicesDependenciesValue = "*", outgoingPermissions = true)
        val node = node(serviceDependencies = setOf("*", "a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
            // we have to preserve all services even if wildcard is present,
            // because service may define different settings for different dependencies (for example endpoints, which
            // will be implemented in GITHUB-ISSUE
            proxySettings = ProxySettings().with(serviceDependencies = setOf("*", "a", "b", "c")),
            ads = false)
        )
    }

    @Test
    fun `should assign to group with no dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true)

        // when
        val group = nodeGroup.hash(Node.newBuilder().build())

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(proxySettings = ProxySettings().with(serviceDependencies = setOf()), ads = false)
        )
    }

    @Test
    fun `should assign to group with listed dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(proxySettings = ProxySettings().with(serviceDependencies = setOf("a", "b", "c")), ads = false)
        )
    }

    @Test
    fun `should assign to group with all dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(allServicesDependenciesValue = "*", outgoingPermissions = true)
        val node = node(serviceDependencies = setOf("*"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(proxySettings = ProxySettings().with(serviceDependencies = setOf("*")), ads = true)
        )
    }

    @Test
    fun `should assign to group with listed dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(proxySettings = ProxySettings().with(serviceDependencies = setOf("a", "b", "c")), ads = true)
        )
    }

    @Test
    fun `should assign to group with all dependencies when outgoing-permissions is not enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = false)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
            // we have to preserve all services even if outgoingPermissions is disabled,
            // because service may define different settings for different dependencies (for example retry config)
            proxySettings = ProxySettings().with(serviceDependencies = setOf("a", "b", "c")),
            ads = true
        ))
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true)
        val node = node(
            serviceDependencies = setOf("a", "b", "c"),
            ads = false, serviceName = "app1",
            incomingSettings = true
        )

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(proxySettings = ProxySettings().with(serviceDependencies = setOf("a", "b", "c")), ads = false)
        )
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true, incomingPermissions = false)
        val node = node(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming).isEqualTo(Incoming())
    }

    @Test
    fun `should include service settings when incoming permissions are enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true, incomingPermissions = true)
        val node = node(serviceDependencies = setOf("a", "b"), ads = true, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(ServicesGroup(
            ads = true,
            serviceName = "app1",
            proxySettings = addedProxySettings.with(serviceDependencies = setOf("a", "b"))
        ))
    }

    @Test
    fun `should include service settings when incoming permissions are enabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(outgoingPermissions = true, incomingPermissions = true)
        val node = node(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
            ads = false,
            serviceName = "app1",
            proxySettings = addedProxySettings.with(serviceDependencies = setOf("*"))
        ))
    }

    @Test
    fun `should parse proto incoming timeout policy`() {
        // when
        val nodeGroup = MetadataNodeGroup(allServicesDependenciesValue = "*", outgoingPermissions = true)
        val node = node(serviceDependencies = setOf("*"), ads = true, incomingSettings = true,
            responseTimeout = "777s", idleTimeout = "13.33s")

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming.timeoutPolicy.responseTimeout?.seconds).isEqualTo(777)
        assertThat(group.proxySettings.incoming.timeoutPolicy.idleTimeout).isEqualTo(Durations.parse("13.33s")
        )
    }
}
