package com.example.ecom.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.util.Locale;

import static org.apache.commons.lang3.StringUtils.trimToNull;

@Embeddable
public class ShippingAddress {

    @Column(name = "shipping_name", nullable = false, length = 200)
    private String name;

    @Column(name = "shipping_line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "shipping_line2", length = 255)
    private String line2;

    @Column(name = "shipping_city", nullable = false, length = 100)
    private String city;

    @Column(name = "shipping_postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "shipping_country", nullable = false, length = 2)
    private String country;

    protected ShippingAddress() {}

    public ShippingAddress(String name, String line1, String line2,
                           String city, String postalCode, String country) {
        this.name = requireText(name);
        this.line1 = requireText(line1);
        this.line2 = trimToNull(line2);
        this.city = requireText(city);
        this.postalCode = requireText(postalCode);
        this.country = normalizeCountry(country);
    }

    public String getName() {
        return name;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getPostalCode(){
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    private String requireText(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new IllegalArgumentException("Shipping field " + name + " is required");
        }
        return trimmed;
    }

    private static String normalizeCountry(String country) {
        String trimmed = trimToNull(country);
        if (country == null || !trimmed.matches("[A-Za-z]{2}")) {
            throw new IllegalArgumentException("Shipping country must be a two-letter code");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
