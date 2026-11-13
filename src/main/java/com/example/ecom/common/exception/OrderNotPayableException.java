package com.example.ecom.common.exception;

import com.example.ecom.order.OrderStatus;

public class OrderNotPayableException extends RuntimeException {
    public OrderNotPayableException(OrderStatus status) {
        super("Order is not in payable status " + status);
    }
}
