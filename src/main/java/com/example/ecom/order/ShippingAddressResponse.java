package com.example.ecom.order;

public record ShippingAddressResponse (
        String name,
        String line1,
        String line2,
        String city,
        String postalCode,
        String country) {

    public static ShippingAddressResponse from(ShippingAddress address){
        return new ShippingAddressResponse(
                address.getName(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getPostalCode(),
                address.getCountry());
    }
}
