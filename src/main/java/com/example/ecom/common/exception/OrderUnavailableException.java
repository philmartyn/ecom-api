package com.example.ecom.common.exception;

import java.util.List;

public class OrderUnavailableException extends RuntimeException {

  private final List<UnavailableItem> unavailableItems;

  public OrderUnavailableException(List<UnavailableItem> unavailableItems) {
    super("One of more items are unavailable");
    this.unavailableItems = List.copyOf(unavailableItems);
  }

  public List<UnavailableItem> getUnavailableItems() {
    return unavailableItems;
  }
}
