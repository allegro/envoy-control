package pl.allegro.tech.servicemesh.envoycontrol.chaos.api

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.chaos.domain.ChaosService
import java.util.UUID
import pl.allegro.tech.servicemesh.envoycontrol.chaos.domain.NetworkDelay as NetworkDelayDomain

@RestController
class ChaosController(val chaosService: ChaosService) {

    @PostMapping("/chaos/fault/read-network-delay")
    @ResponseStatus(HttpStatus.OK)
    fun readNetworkDelay(@RequestBody requestBody: NetworkDelay): NetworkDelayResponse =
        chaosService.submitNetworkDelay(requestBody.toDomainObject()).toResponseObject()

    @Configuration
    class SecurityConfig : WebSecurityConfigurerAdapter() {

        @Bean
        @ConfigurationProperties("chaos")
        fun basicAuthUser() = BasicAuthUser()

        override fun configure(auth: AuthenticationManagerBuilder) {
            auth.inMemoryAuthentication()
                .withUser(basicAuthUser().username)
                .password(basicAuthUser().password)
                .roles("CHAOS")
        }

        override fun configure(http: HttpSecurity) {
            http.httpBasic()
                .and()
                .authorizeRequests()
                .antMatchers(HttpMethod.POST, "/chaos/fault/**").hasRole("CHAOS")
                .and()
                .csrf().disable()
                .formLogin().disable()
        }
    }

    class BasicAuthUser {
        var username: String = "username"
        var password: String = "{noop}password"
    }
}

data class NetworkDelay(
    val source: String,
    val delay: String,
    val duration: String,
    val target: String
) {
    fun toDomainObject(): NetworkDelayDomain = NetworkDelayDomain(
        id = UUID.randomUUID().toString(),
        source = source,
        delay = delay,
        duration = duration,
        target = target
    )
}

data class NetworkDelayResponse(
    val id: String,
    val source: String,
    val delay: String,
    val duration: String,
    val target: String
)

fun NetworkDelayDomain.toResponseObject(): NetworkDelayResponse = NetworkDelayResponse(
    id = id,
    source = source,
    delay = delay,
    duration = duration,
    target = target
)
