package com.example.ecom.common.exception;

/**
 * One exception for both "no such email" and "wrong password", with a fixed
 * message. The response would otherwise be a free account-enumeration oracle.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}