package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TcpProxyFilterFactoryTest {

    private val tcpProxyFilterFactory: TcpProxyFilterFactory = TcpProxyFilterFactory()

    @Test
    fun `should create tcp proxy filter`() {
        // when
        val filter = tcpProxyFilterFactory.createFilter(
            "cluster_tcp",
            isSsl = true,
            host = "test.org",
            statsPrefix = "stats_test"
        )

        // then
        assertThat(filter.filterChainMatch.transportProtocol).isEqualTo("tls")
        assertThat(filter.filterChainMatch.serverNamesCount).isEqualTo(1)
        assertThat(filter.filterChainMatch.serverNamesList[0]).isEqualTo("test.org")
        assertThat(filter.filtersList.size).isEqualTo(1)
        assertThat(filter.filtersList[0].name).isEqualTo("envoy.tcp_proxy")
    }
}
