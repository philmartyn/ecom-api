package com.example.ecom.auth;

import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for {@code GET /api/auth/me}: the controller, Jackson and the
 * exception advice are real, the service is a mock. Security filters are off,
 * so the verified token is put into the {@code SecurityContext} by hand.
 * <p>
 * It has to be by hand: since Spring Security 6.1, the {@code jwt()} request
 * post-processor writes to a {@code SecurityContextRepository} (a request
 * attribute), and it is {@code SecurityContextHolderFilter} that copies that
 * into the holder. With {@code addFilters = false} that filter never runs, so
 * {@code @AuthenticationPrincipal} would resolve to null.
 * <p>
 * What this pins down is the controller contract: the token's {@code sub}
 * reaches the service as a {@code long}, the response is the same
 * {@code UserResponse} shape as registration, and it is marked {@code no-store}.
 * Real token verification is covered by {@code SecurityPerimeterTest} and
 * {@code TokenValidationIntegrationTest}.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class MeEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder is a thread-local, and MockMvc runs on the test
        // thread: without this, one test's authentication leaks into the next.
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsTheCallersProfileKeyedByTheTokensSubject() throws Exception {
        given(authService.currentUser(42L)).willReturn(new UserResponse(
                42L, "bob@example.com", "Bob", "Smith", Role.CUSTOMER,
                Instant.parse("2026-10-25T15:04:00Z")));

        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("42")
                .claim("role", "CUSTOMER")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.firstName").value("Bob"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-25T15:04:00Z"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        then(authService).should().currentUser(42L);
    }
}
