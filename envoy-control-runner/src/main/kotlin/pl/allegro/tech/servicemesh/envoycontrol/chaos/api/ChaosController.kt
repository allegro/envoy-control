package pl.allegro.tech.servicemesh.envoycontrol.chaos.api

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
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
    class SecurityConfig {

        @Bean
        @ConfigurationProperties("chaos")
        fun basicAuthUser() = BasicAuthUser()

        @Bean
        fun userDetailsService(): InMemoryUserDetailsManager {
            val user: UserDetails = User.builder()
                .username(basicAuthUser().username)
                .password("{noop}${basicAuthUser().password}")
                .roles("CHAOS")
                .build()

            return InMemoryUserDetailsManager(user)
        }

        @Bean
        fun filterChain(http: HttpSecurity): SecurityFilterChain? {
            http {
                httpBasic { }
                authorizeHttpRequests {
                    authorize(AntPathRequestMatcher("/chaos/fault/**", HttpMethod.POST.name()), hasRole("CHAOS"))
                    authorize(anyRequest, permitAll) // todo: ???
                }
                csrf { disable() }
                formLogin { disable() }
            }

            return http.build()
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
