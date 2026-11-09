package com.example.ecom.common.exception;

import java.util.List;

public class CartNotCheckoutableException extends RuntimeException {

    private final List<UnavailableItem> unavailableItems;

    public CartNotCheckoutableException(List<UnavailableItem> unavailableItems) {
        super("One or more items are unavailable");
        this.unavailableItems = List.copyOf(unavailableItems);
    }

    public List<UnavailableItem> getUnavailableItems(){
        return unavailableItems;
    }
}
