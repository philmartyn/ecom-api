package com.example.ecom.catalog;

import com.example.ecom.common.exception.ProductNotFoundException;
import com.example.ecom.common.web.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public CatalogService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository){
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public PageResponse<ProductSummaryResponse> listProducts(ProductQuery query) {
        Sort sort = Sort.by(
                query.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                query.sortField());

        PageRequest pageRequest = PageRequest.of(query.page(), query.size(), sort);

        Page<ProductSummaryResponse> page = productRepository
                .findAll(ProductSpecifications.matching(query), pageRequest)
                .map(ProductSummaryResponse::from);

        return PageResponse.from(page);
    }

    public ProductDetailResponse getProduct(String slug) {
        return productRepository.findBySlugAndActiveTrue(slug)
                .map(ProductDetailResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(slug));
    }

    public List<CategoryResponse> listCategories() {
        return categoryRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
