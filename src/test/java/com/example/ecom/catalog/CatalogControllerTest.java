package com.example.ecom.catalog;

import com.example.ecom.common.exception.ApiExceptionHandler;
import com.example.ecom.common.exception.ProductNotFoundException;
import com.example.ecom.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for the catalog read endpoints: the controller, Jackson and
 * the exception advice are real, the service is a mock. Security filters are
 * off because this class is about the HTTP/JSON contract; public access is
 * proved by {@code SecurityPerimeterTest} against the real filter chain.
 * <p>
 * What is pinned here is the envelope, the summary/detail split, the absence of
 * private inventory fields, and that bad query parameters become {@code 400}
 * problem+json rather than a 500 from the repository layer.
 */
@WebMvcTest(CatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    void listsProductsWithTheEnvelopeAndWithoutPrivateFields() throws Exception {
        given(catalogService.listProducts(any()))
                .willReturn(new PageResponse<>(List.of(summary()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.items[0].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.items[0].price").value(129.99))
                .andExpect(jsonPath("$.items[0].imageUrl").value("https://example.com/kb.png"))
                .andExpect(jsonPath("$.items[0].category.slug").value("keyboards"))
                .andExpect(jsonPath("$.items[0].category.name").value("Keyboards"))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.items[0].sku").doesNotExist())
                .andExpect(jsonPath("$.items[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        ProductQuery query = capturedQuery();
        assertThat(query.q()).isNull();
        assertThat(query.category()).isNull();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sortField()).isEqualTo("name");
        assertThat(query.ascending()).isTrue();
    }

    @Test
    void passesSearchFilterPaginationAndSortToTheService() throws Exception {
        given(catalogService.listProducts(any()))
                .willReturn(new PageResponse<>(List.of(), 2, 5, 11, 3));

        mockMvc.perform(get("/api/products")
                        .param("q", "keyboard")
                        .param("category", "keyboards")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3));

        ProductQuery query = capturedQuery();
        assertThat(query.q()).isEqualTo("keyboard");
        assertThat(query.category()).isEqualTo("keyboards");
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(5);
        assertThat(query.sortField()).isEqualTo("price");
        assertThat(query.ascending()).isFalse();
    }

    @Test
    void returns400ForAnInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/products"));
    }

    @Test
    void returns400ForAnInvalidSortField() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "sku,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/products"));
    }

    @Test
    void returnsTheProductDetailWithoutPrivateFields() throws Exception {
        given(catalogService.getProduct("mechanical-keyboard")).willReturn(detail());

        mockMvc.perform(get("/api/products/mechanical-keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.slug").value("mechanical-keyboard"))
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(129.99))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/kb.png"))
                .andExpect(jsonPath("$.category.slug").value("keyboards"))
                .andExpect(jsonPath("$.category.name").value("Keyboards"))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.description").value("Clicky keys"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-25T15:04:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-26T15:04:00Z"))
                .andExpect(jsonPath("$.sku").doesNotExist())
                .andExpect(jsonPath("$.stockQuantity").doesNotExist());
    }

    @Test
    void returns404WithProblemJsonForAnUnknownSlug() throws Exception {
        willThrow(new ProductNotFoundException("nope"))
                .given(catalogService).getProduct("nope");

        mockMvc.perform(get("/api/products/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found"))
                .andExpect(jsonPath("$.instance").value("/api/products/nope"));
    }

    @Test
    void listsCategoriesInTheAgreedShape() throws Exception {
        given(catalogService.listCategories()).willReturn(List.of(
                new CategoryResponse(1L, "keyboards", "Keyboards", "Input devices")));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].slug").value("keyboards"))
                .andExpect(jsonPath("$[0].name").value("Keyboards"))
                .andExpect(jsonPath("$[0].description").value("Input devices"))
                .andExpect(jsonPath("$[0].productCount").doesNotExist());
    }

    private ProductQuery capturedQuery() {
        ArgumentCaptor<ProductQuery> captor = ArgumentCaptor.forClass(ProductQuery.class);
        then(catalogService).should().listProducts(captor.capture());
        return captor.getValue();
    }

    private ProductSummaryResponse summary() {
        return new ProductSummaryResponse(
                1L, "mechanical-keyboard", "Mechanical Keyboard",
                new BigDecimal("129.99"), "https://example.com/kb.png",
                new CategorySummary("keyboards", "Keyboards"), true);
    }

    private ProductDetailResponse detail() {
        return new ProductDetailResponse(
                1L, "mechanical-keyboard", "Mechanical Keyboard",
                new BigDecimal("129.99"), "https://example.com/kb.png",
                new CategorySummary("keyboards", "Keyboards"), true,
                "Clicky keys",
                Instant.parse("2026-10-25T15:04:00Z"),
                Instant.parse("2026-10-26T15:04:00Z"));
    }
}
