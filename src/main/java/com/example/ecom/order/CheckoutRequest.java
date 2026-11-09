package com.example.ecom.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull @Valid ShippingAddressRequest shipping) {
}
