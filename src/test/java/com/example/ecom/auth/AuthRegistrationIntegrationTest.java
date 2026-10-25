package com.example.ecom.auth;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack: real controller, real service, real BCrypt, real PostgreSQL,
 * real Flyway schema and unique index. Only the HTTP transport is simulated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthRegistrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersACustomerAndRejectsTheSameEmailInADifferentCase() throws Exception {
        register("Bob@Example.com", "correct-horse-battery")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists());

        User saved = userRepository.findByEmail("bob@example.com").orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("correct-horse-battery", saved.getPasswordHash())).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();

        register("bob@example.com", "another-password")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already registered"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidPayloadWithoutWritingAnything() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short",
                                 "firstName":"Bob","lastName":"Smith"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());

        assertThat(userRepository.count()).isZero();
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","firstName":"Bob","lastName":"Smith"}
                        """.formatted(email, password)));
    }
}
