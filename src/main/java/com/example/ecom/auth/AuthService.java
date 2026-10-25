package com.example.ecom.auth;

import com.example.ecom.common.exception.EmailAlreadyUsedException;
import com.example.ecom.common.exception.InvalidRequestException;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class AuthService {
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException(email);
        }

        byte[] passwordBytes = request.password().getBytes(StandardCharsets.UTF_8);
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

}
