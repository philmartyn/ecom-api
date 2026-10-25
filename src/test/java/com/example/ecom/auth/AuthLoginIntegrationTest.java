package com.example.ecom.auth;

import com.example.ecom.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack: real properties binding, real BCrypt, real signing, real database.
 * <p>
 * The decoder is built here rather than injected: 2d adds a JwtDecoder bean, but
 * verifying with an independently constructed decoder is a stronger check than
 * one reusing the same bean the application would use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void registersLogsInAndIssuesATokenForThatUser() throws Exception {
        String registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"Bob@Example.com","password":"correct-horse-battery",
                                 "firstName":"Bob","lastName":"Smith"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long userId = ((Number) JsonPath.read(registered, "$.id")).longValue();

        String loggedIn = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bob@example.com","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        // Truncated to whole seconds, so it lands a fraction under the 15 minutes.
        long expiresIn = ((Number) JsonPath.read(loggedIn, "$.expiresIn")).longValue();
        assertThat(expiresIn).isBetween(890L, 900L);

        Jwt jwt = decoder().decode(JsonPath.read(loggedIn, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(userId));
        assertThat(jwt.getClaimAsString("email")).isEqualTo("bob@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://ecom.local");
    }

    @Test
    void rejectsAWrongPasswordWith401AndNoToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bob@example.com","password":"correct-horse-battery",
                                 "firstName":"Bob","lastName":"Smith"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bob@example.com","password":"not-the-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid credentials"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    private JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(JwtConfig.hmacKey(jwtProperties)).build();
    }
}
