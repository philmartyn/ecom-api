package com.example.ecom.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public long currentUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

}
