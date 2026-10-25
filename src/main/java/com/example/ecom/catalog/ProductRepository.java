package com.example.ecom.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByActiveTrue(Pageable pageable);

    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);
}