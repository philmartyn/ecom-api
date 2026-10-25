package com.example.ecom.common.exception;

/** A request that is well-formed but breaks a rule the annotations cannot express. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}