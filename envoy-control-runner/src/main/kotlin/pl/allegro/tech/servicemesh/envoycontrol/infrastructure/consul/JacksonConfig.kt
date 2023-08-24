package pl.allegro.tech.servicemesh.envoycontrol.infrastructure.consul

import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
open class JacksonConfig {

    @Bean
    fun kotlinModule() = KotlinModule.Builder().build()
}
