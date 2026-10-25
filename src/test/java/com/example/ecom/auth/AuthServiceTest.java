package com.example.ecom.auth;

import com.example.ecom.common.exception.EmailAlreadyUsedException;
import com.example.ecom.common.exception.InvalidCredentialsException;
import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No Spring context: the repository and token service are mocks, BCrypt is real
 * but cheap. Keeping the encoder real means the assertions are about actual
 * hashing, not "did we call encode()". Token internals belong to TokenServiceTest.
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final TokenService tokenService = mock(TokenService.class);
    private final AuthService authService =
            new AuthService(userRepository, passwordEncoder, tokenService);

    @Test
    void registersACustomerWithNormalizedEmailAndHashedPassword() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest(" Bob@Example.COM ", "correct-horse-battery", "Bob", "Smith"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        User user = saved.getValue();

        assertThat(user.getEmail()).isEqualTo("bob@example.com");
        assertThat(user.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(user.getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(passwordEncoder.matches("correct-horse-battery", user.getPasswordHash())).isTrue();
        assertThat(response.email()).isEqualTo("bob@example.com");
        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void rejectsADuplicateEmailWithoutSaving() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Bob@Example.com", "correct-horse-battery", "Bob", "Smith")))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessageContaining("bob@example.com");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsAPasswordOver72BytesEvenWhenUnderTheCharacterLimit() {
        String emojiPassword = "\uD83D\uDE00".repeat(20); // 20 characters, 80 bytes in UTF-8
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("bob@example.com", emojiPassword, "Bob", "Smith")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotHandTheRawPasswordAnywhereButTheEncoder() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest("bob@example.com", "correct-horse-battery", "Bob", "Smith"));

        assertThat(response.toString()).doesNotContain("correct-horse-battery");
    }

    @Test
    void logsInWithMixedCaseEmailAndReturnsAToken() {
        User user = existingUser(7L, "bob@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));
        when(tokenService.issue(user))
                .thenReturn(new TokenService.AccessToken("signed.jwt.value", expiresAt));

        LoginResponse response = authService.login(
                new LoginRequest("Bob@Example.COM", "correct-horse-battery"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt.value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isBetween(890L, 900L);
    }

    @Test
    void rejectsAWrongPasswordAndAnUnknownEmailWithTheSameResponse() {
        User user = existingUser(7L, "bob@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("bob@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Email or password is incorrect");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Email or password is incorrect");

        verify(tokenService, never()).issue(any());
    }

    @Test
    void treatsAnOverLongPasswordAsBadCredentialsRatherThanACrash() {
        String emojiPassword = "\uD83D\uDE00".repeat(20); // 80 bytes

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("bob@example.com", emojiPassword)))
                .isInstanceOf(InvalidCredentialsException.class);

        // The guard runs before the lookup, so bcrypt never sees the oversized value.
        verify(userRepository, never()).findByEmail(anyString());
    }

    private User existingUser(long id, String email, String rawPassword) {
        User user = new User(email, passwordEncoder.encode(rawPassword), "Bob", "Smith", Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
