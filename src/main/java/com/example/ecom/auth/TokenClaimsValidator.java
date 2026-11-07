package com.example.ecom.auth;

import com.example.ecom.user.Role;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;

/**
 * The claims our own login always sets, checked on the way back in. Both checks
 * fail closed: an unknown role or a non-numeric subject means drift or
 * tampering, so the token is invalid rather than authenticated with no
 * authorities.
 */
class TokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID_TOKEN =
            new OAuth2Error("invalid_token", "Token claims are invalid",  null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        boolean knownRole = Arrays.stream(Role.values())
                .anyMatch(role -> role.name().equals(jwt.getClaimAsString(TokenService.ROLE_CLAIM)));

        return knownRole && isNumeric(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }

    private boolean isNumeric(String subject){
        if (subject == null || subject.isBlank()){
            return false;
        }
        try {
            Long.parseLong(subject);
            return true;
        } catch (NumberFormatException e){
            return false;
        }
    }
}
