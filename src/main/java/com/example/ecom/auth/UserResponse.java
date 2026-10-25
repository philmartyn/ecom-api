package com.example.ecom.auth;

import com.example.ecom.user.Role;
import com.example.ecom.user.User;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        Role role,
        Instant createdAt
) {

    public static UserResponse from(User user){
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getCreatedAt());
    }
}
