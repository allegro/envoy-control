package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import pl.allegro.tech.discovery.consul.recipes.watch.Canceller
import pl.allegro.tech.discovery.consul.recipes.watch.ConsulWatcher
import pl.allegro.tech.servicemesh.envoycontrol.server.NoopReadinessStateHandler
import pl.allegro.tech.servicemesh.envoycontrol.server.ReadinessStateHandler
import reactor.test.StepVerifier

class ConsulClusterStateChangesDisposeTest {

    @Test
    fun `should start watching and stop watching after dispose`() {
        val watcher = Mockito.mock(ConsulWatcher::class.java)
        val callbackCanceller = Canceller()
        val readinessStateHandler = Mockito.spy(ReadinessStateHandler::class.java)
        `when`(watcher.watchEndpoint(Mockito.eq("/v1/catalog/services"), Mockito.any(), Mockito.any())).thenReturn(callbackCanceller)

        val recipes = ConsulServiceChanges(watcher = watcher, readinessStateHandler = readinessStateHandler)
        recipes.watchState().subscribe().dispose()

        verify(readinessStateHandler, times(2)).unready()
        verify(watcher).watchEndpoint(Mockito.eq("/v1/catalog/services"), Mockito.any(), Mockito.any())
        assertThat(callbackCanceller.isCancelled).isTrue()
    }
}
