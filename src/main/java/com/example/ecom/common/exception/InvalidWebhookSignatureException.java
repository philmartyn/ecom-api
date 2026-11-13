package com.example.ecom.common.exception;

/**
 * The signature header was missing, malformed, stale, or did not match. One
 * exception for every case on purpose: telling an attacker which part failed
 * helps them iterate.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

  public InvalidWebhookSignatureException() {
    super("Invalid webhook signature");
  }
}