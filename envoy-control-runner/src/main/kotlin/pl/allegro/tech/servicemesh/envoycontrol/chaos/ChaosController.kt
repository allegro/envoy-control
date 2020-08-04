package pl.allegro.tech.servicemesh.envoycontrol.chaos

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ChaosController {
    @PostMapping("/chaos/fault/read-network-delay")
    fun readNetworkDelay(): HttpStatus {
        return HttpStatus.OK
    }

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
