package com.example.ecom.auth;

import com.example.ecom.common.exception.EmailAlreadyUsedException;
import com.example.ecom.common.exception.InvalidCredentialsException;
import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class AuthService {
    private static final int BCRYPT_MAX_BYTES = 72;

    /**
     * A real BCrypt hash of a string nobody knows. When the email does not exist
     * we still pay for one comparison: returning in ~1ms instead of ~60ms lets an
     * attacker distinguish "unknown email" from "wrong password" by timing alone,
     * even though both responses are identical.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException(email);
        }

        byte[] passwordBytes = request.password().getBytes(UTF_8);
        if (passwordBytes.length > BCRYPT_MAX_BYTES) {
            throw new InvalidRequestException("Password must be at most 72 bytes when UTF-8 encoded");
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName(),
                Role.CUSTOMER);

        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());

        if (request.password().getBytes(UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidCredentialsException();
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        TokenService.AccessToken token = tokenService.issue(user);
        long expiresIn = Duration.between(Instant.now(), token.expiresAt()).toSeconds();
        return new LoginResponse(token.value(), "Bearer", expiresIn);
    }
}
