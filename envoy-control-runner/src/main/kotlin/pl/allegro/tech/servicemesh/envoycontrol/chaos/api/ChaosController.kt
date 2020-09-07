package pl.allegro.tech.servicemesh.envoycontrol.chaos.api

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.chaos.domain.ChaosService
import java.util.UUID
import pl.allegro.tech.servicemesh.envoycontrol.chaos.domain.NetworkDelay as NetworkDelayDomain

@RestController
@RequestMapping("/chaos/fault/read-network-delay")
class ChaosController(val chaosService: ChaosService) {

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    fun readNetworkDelay(@RequestBody requestBody: NetworkDelay): NetworkDelayResponse =
        chaosService.submitNetworkDelay(requestBody.toDomainObject()).toResponseObject()

    @DeleteMapping("{networkDelayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable networkDelayId: String) {
        chaosService.deleteNetworkDelay(networkDelayId)
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun getExperimentsList(): ExperimentsListResponse =
        ExperimentsListResponse(chaosService.getExperimentsList().map { it.toResponseObject() })

    @Configuration
    class SecurityConfig : WebSecurityConfigurerAdapter() {

        @Bean
        @ConfigurationProperties("chaos")
        fun basicAuthUser() = BasicAuthUser()

        override fun configure(auth: AuthenticationManagerBuilder) {
            auth.inMemoryAuthentication()
                .withUser(basicAuthUser().username)
                .password("{noop}${basicAuthUser().password}")
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
        var password: String = "password"
    }
}

data class NetworkDelay(
    val affectedService: String,
    val delay: String,
    val duration: String,
    val targetService: String
) {
    fun toDomainObject(): NetworkDelayDomain = NetworkDelayDomain(
        id = UUID.randomUUID().toString(),
        affectedService = affectedService,
        delay = delay,
        duration = duration,
        targetService = targetService
    )
}

data class NetworkDelayResponse(
    val id: String,
    val affectedService: String,
    val delay: String,
    val duration: String,
    val targetService: String
)

fun NetworkDelayDomain.toResponseObject(): NetworkDelayResponse = NetworkDelayResponse(
    id = id,
    affectedService = affectedService,
    delay = delay,
    duration = duration,
    targetService = targetService
)

data class ExperimentsListResponse(
    val experimentList: List<NetworkDelayResponse> = emptyList()
)
