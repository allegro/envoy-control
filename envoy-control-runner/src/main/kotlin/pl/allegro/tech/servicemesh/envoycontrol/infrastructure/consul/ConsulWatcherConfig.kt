package pl.allegro.tech.servicemesh.envoycontrol.infrastructure.consul

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.Util
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.allegro.tech.discovery.consul.recipes.ConsulRecipes
import pl.allegro.tech.discovery.consul.recipes.json.JacksonJsonDeserializer
import pl.allegro.tech.discovery.consul.recipes.json.JacksonJsonSerializer
import pl.allegro.tech.discovery.consul.recipes.watch.ConsulWatcher
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulWatcherOkHttpProperties
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Configuration
open class ConsulWatcherConfig {

    @Bean
    fun consulWatcher(
        consulProperties: ConsulProperties,
        objectMapper: ObjectMapper
    ): ConsulWatcher {
        val watcherPool = watcherPool()

        val client = okHttpClient(consulProperties.watcher)

        return createConsulWatcher(consulProperties, objectMapper, client, watcherPool)
    }

    protected fun watcherPool(): ExecutorService {
        return Executors.newFixedThreadPool(1, RecipesThreadFactory())
    }

    protected fun createConsulWatcher(
        properties: ConsulProperties,
        objectMapper: ObjectMapper,
        client: OkHttpClient,
        watcherPool: ExecutorService
    ): ConsulWatcher {
        return ConsulRecipes.consulRecipes()
            .withAgentUri(URI("http://${properties.host}:${properties.port}"))
            .withJsonDeserializer(JacksonJsonDeserializer(objectMapper))
            .withJsonSerializer(JacksonJsonSerializer(objectMapper))
            .withWatchesHttpClient(client)
            .build()
            .consulWatcher(watcherPool)
            .requireDefaultConsistency()
            .build()
    }

    protected fun okHttpClient(watcherConfig: ConsulWatcherOkHttpProperties): OkHttpClient {
        return customizeClient(OkHttpClient.Builder(), watcherConfig)
            .readTimeout(watcherConfig.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .connectTimeout(watcherConfig.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build()
    }

    protected fun customizeClient(
        builder: OkHttpClient.Builder,
        watcherConfig: ConsulWatcherOkHttpProperties
    ): OkHttpClient.Builder {
        val dispatcher = Dispatcher(createDispatcherPool(watcherConfig))
        dispatcher.maxRequests = watcherConfig.maxRequests
        dispatcher.maxRequestsPerHost = watcherConfig.maxRequests

        return builder.addInterceptor(NoGzipIntercetor())
            .dispatcher(dispatcher)
    }

    protected fun createDispatcherPool(watcherConfig: ConsulWatcherOkHttpProperties): ExecutorService {
        return ThreadPoolExecutor(
            0,
            watcherConfig.dispatcherMaxPoolSize,
            watcherConfig.dispatcherPoolKeepAliveTime.toMillis(),
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            Util.threadFactory("consul-okhttp-dispatcher", false)
        )
    }

    protected class NoGzipIntercetor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .addHeader("Accept-Encoding", "identity")
                    .build()
            )
        }
    }

    private class RecipesThreadFactory : ThreadFactory {
        private val counter = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "consul-watcher-worker-${counter.getAndIncrement()}")
    }

    @Bean
    fun kotlinModule() = KotlinModule()
}
