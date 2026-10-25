package com.example.ecom.common.exception;

public class EmailAlreadyUsedException extends RuntimeException {
    private final String email;

    public EmailAlreadyUsedException(String email) {
        super("An account with " + email + " already exists");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
