package com.example.ecom.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShippingAddressRequest(
        @NotBlank @Size(max = 200)String name,
        @NotBlank @Size(max = 255)String line1,
        @Size(max = 255)String line2,
        @NotBlank @Size(max = 100)String city,
        @NotBlank @Size(max = 20)String postalCode,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}")String country) {

    public ShippingAddress toDomain() {
        return new ShippingAddress(name, line1, line2, city, postalCode, country);
    }
}
