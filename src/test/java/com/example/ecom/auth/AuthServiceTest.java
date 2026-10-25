package com.example.ecom.auth;

import com.example.ecom.common.exception.EmailAlreadyUsedException;
import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No Spring context: the repository is a mock and BCrypt is real but cheap.
 * Keeping the encoder real means the assertions are about actual hashing,
 * not "did we call encode()".
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AuthService authService = new AuthService(userRepository, passwordEncoder);

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
}
