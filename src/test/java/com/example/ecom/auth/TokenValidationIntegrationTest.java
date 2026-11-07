package com.example.ecom.auth;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token verification end to end: real signing, the real decoder, the real
 * filter chain and a real database.
 * <p>
 * One test earns a token the way a client does (register, log in, call
 * {@code /me}). The rest mint tokens by hand from the same encoder, which is
 * the only practical way to produce the invalid cases — expired, foreign
 * signature, wrong issuer, unknown role, non-numeric subject. Each of those
 * must fail with the same generic {@code 401}, because telling an attacker
 * which check failed is an oracle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TokenValidationIntegrationTest extends AbstractIntegrationTest {

    /** 38 bytes: long enough for HS256, and definitely not the configured secret. */
    private static final String WRONG_SECRET = "wrong-secret-for-tests-only-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserRepository userRepository;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void returnsTheProfileForATokenIssuedByLogin() throws Exception {
        long userId = register("Bob@Example.com", "correct-horse-battery");
        String token = login("bob@example.com", "correct-horse-battery");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value((int) userId))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.firstName").value("Bob"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void rejectsATokenWhosePayloadWasEdited() throws Exception {
        register("bob@example.com", "correct-horse-battery");
        String token = login("bob@example.com", "correct-horse-battery");

        String escalated = replaceClaim(token, "role", "ADMIN");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + escalated))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        Instant past = Instant.now().minus(Duration.ofMinutes(20));

        String expired = mintToken(claims -> claims
                .issuedAt(past)
                .expiresAt(past.plus(Duration.ofMinutes(5))));

        assertRejected(expired);
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() throws Exception {
        String foreign = mint(rogueEncoder(), claims -> { });

        assertRejected(foreign);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        String foreignIssuer = mintToken(claims -> claims.issuer("https://evil.example"));

        assertRejected(foreignIssuer);
    }

    @Test
    void rejectsATokenWhoseRoleIsNotAKnownRole() throws Exception {
        String unknownRole = mintToken(claims -> claims.claim("role", "SUPERUSER"));

        assertRejected(unknownRole);
    }

    @Test
    void rejectsATokenWhoseSubjectIsNotANumericUserId() throws Exception {
        String badSubject = mintToken(claims -> claims.subject("not-a-number"));

        assertRejected(badSubject);
    }

    @Test
    void rejectsATokenWhoseUserNoLongerExists() throws Exception {
        long userId = register("bob@example.com", "correct-horse-battery");
        String token = login("bob@example.com", "correct-horse-battery");

        userRepository.deleteById(userId);
        userRepository.flush();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
    }

    // --- helpers ---------------------------------------------------------

    private void assertRejected(String token) throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
    }

    private long register(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s","firstName":"Bob","lastName":"Smith"}
                """.formatted(email, password);

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }

    /** A token our own encoder would accept, unless the customiser says otherwise. */
    private String mintToken(Consumer<JwtClaimsSet.Builder> customiser) {
        return mint(jwtEncoder, customiser);
    }

    private String mint(JwtEncoder encoder, Consumer<JwtClaimsSet.Builder> customiser) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("role", "CUSTOMER");
        customiser.accept(claims);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private JwtEncoder rogueEncoder() {
        SecretKey key = new SecretKeySpec(WRONG_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Rewrites a claim and keeps the original signature, which is exactly what a
     * tampered token looks like. The payload is re-encoded, so the signature no
     * longer matches even though the header and signature segments are intact.
     */
    @SuppressWarnings("unchecked")
    private String replaceClaim(String token, String claim, Object value) throws Exception {
        String[] parts = token.split("\\.");
        Map<String, Object> claims = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]), Map.class);
        claims.put(claim, value);

        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(claims));
        return parts[0] + "." + payload + "." + parts[2];
    }
}
