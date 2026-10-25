package com.example.ecom.auth;

import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trips through a real decoder rather than inspecting the string: a token
 * that only encoded claims without signing them would pass a naive test.
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-32-bytes";

    private final JwtProperties properties =
            new JwtProperties(SECRET, Duration.ofMinutes(15), "https://ecom.local");

    private final TokenService tokenService =
            new TokenService(JwtConfig.encoder(properties), properties);

    private final JwtDecoder decoder = NimbusJwtDecoder
            .withSecretKey(JwtConfig.hmacKey(properties))
            .build();

    @Test
    void issuesASignedTokenCarryingIdentityRoleAndIssuer() {
        TokenService.AccessToken accessToken = tokenService.issue(user(42L));

        Jwt jwt = decoder.decode(accessToken.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("bob@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://ecom.local");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void expiresInFifteenMinutes() {
        TokenService.AccessToken accessToken = tokenService.issue(user(42L));

        Jwt jwt = decoder.decode(accessToken.value());

        long lifetimeSeconds = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds();
        assertThat(lifetimeSeconds).isEqualTo(900);
        assertThat(jwt.getExpiresAt()).isCloseTo(accessToken.expiresAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void aTokenSignedWithADifferentSecretDoesNotVerify() {
        String token = tokenService.issue(user(42L)).value();

        JwtProperties other = new JwtProperties(
                "a-completely-different-secret-32-bytes!", Duration.ofMinutes(15), "https://ecom.local");
        JwtDecoder otherDecoder = NimbusJwtDecoder
                .withSecretKey(JwtConfig.hmacKey(other))
                .build();

        assertThatThrownBy(() -> otherDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    private User user(long id) {
        User user = new User("bob@example.com", "irrelevant-hash", "Bob", "Smith", Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
