package com.example.ecom.common.web;

import com.example.ecom.common.exception.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public class PageParams {
    public static final int MAX_SIZE = 100;

    private PageParams() {}

    public static void requireValid(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_SIZE);
        }
    }

    public static PageRequest toPageRequest(int page, int size, Sort sort) {
        requireValid(page, size);
        return PageRequest.of(page, size, sort);
    }
}
