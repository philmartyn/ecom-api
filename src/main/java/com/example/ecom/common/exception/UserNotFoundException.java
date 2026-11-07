package com.example.ecom.common.exception;

/**
 * A verified token naming a user id that no longer exists. Mapped to 401, not
 * 404: the token identifies nobody, so the caller is not authenticated.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(long id) {
        super("No user with id " + id);
    }
}
