package team.startup.report.global.security

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringJUnitConfig(SecurityConfig::class, DefaultSecurityAccessTests.WebSecurity::class)
@WebAppConfiguration
class DefaultSecurityAccessTests {
    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @Test
    fun `authenticated local users cannot access default paths`() {
        listOf("/actuator/info", "/unknown").forEach { path ->
            mvc.perform(get(path).with(user("local").roles("ADMIN"))).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `basic credentials do not enable login or emit a challenge`() {
        mvc
            .perform(get("/unknown").with(httpBasic("local", "password")))
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist("WWW-Authenticate"))
    }

    @Test
    fun `login page is unavailable`() {
        mvc.perform(get("/login")).andExpect(status().isUnauthorized)
    }

    @TestConfiguration
    @EnableWebSecurity
    class WebSecurity
}
