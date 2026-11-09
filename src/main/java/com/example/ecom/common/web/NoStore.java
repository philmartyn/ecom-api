package com.example.ecom.common.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.net.URI;

public final class NoStore {

    private NoStore() {}

    public static ResponseEntity.BodyBuilder ok() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
    }

    public static ResponseEntity.BodyBuilder created(URI location) {
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore());
    }

}
