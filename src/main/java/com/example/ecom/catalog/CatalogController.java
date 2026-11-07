package com.example.ecom.catalog;

import com.example.ecom.common.web.PageResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController (CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return catalogService.listCategories();
    }

    @GetMapping("/products")
    public PageResponse<ProductSummaryResponse> products(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "name,asc") String sort) {

        return catalogService.listProducts(
                new ProductQuery(q, category, page, size, sort));
    }

    @GetMapping("/products/{slug}")
    public ProductDetailResponse product(@PathVariable("slug") String slug) {
        return catalogService.getProduct(slug);
    }
}
