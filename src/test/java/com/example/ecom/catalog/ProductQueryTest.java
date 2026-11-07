package com.example.ecom.catalog;

import com.example.ecom.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit spec for the catalog query object. No Spring, no database: this is the
 * one place where "what did the caller ask for?" becomes a valid, normalised
 * request.
 * <p>
 * The record is deliberately responsible for both defaults and bounds. Keeping
 * that here means the controller binds strings and ints, and the service only
 * ever sees something already safe to hand to a repository.
 */
class ProductQueryTest {

    @Test
    void appliesTheAgreedDefaults() {
        ProductQuery query = new ProductQuery(null, null, 0, 20, null);

        assertThat(query.q()).isNull();
        assertThat(query.category()).isNull();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sortField()).isEqualTo("name");
        assertThat(query.ascending()).isTrue();
    }

    @Test
    void trimsSearchAndCategoryAndTurnsBlankIntoAbsent() {
        ProductQuery query = new ProductQuery("  keyboard  ", "  keyboards  ", 1, 50, " price,DESC ");

        assertThat(query.q()).isEqualTo("keyboard");
        assertThat(query.category()).isEqualTo("keyboards");
        assertThat(query.sortField()).isEqualTo("price");
        assertThat(query.ascending()).isFalse();
    }

    @Test
    void turnsABlankSearchIntoNoFilter() {
        assertThat(new ProductQuery("   ", null, 0, 20, null).q()).isNull();
        assertThat(new ProductQuery(null, "   ", 0, 20, null).category()).isNull();
    }

    @Test
    void acceptsABareSortFieldAsAscending() {
        ProductQuery query = new ProductQuery(null, null, 0, 20, "createdAt");

        assertThat(query.sortField()).isEqualTo("createdAt");
        assertThat(query.ascending()).isTrue();
    }

    @Test
    void rejectsANegativePage() {
        assertThatThrownBy(() -> new ProductQuery(null, null, -1, 20, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsASizeOutsideTheAgreedBounds() {
        assertThatThrownBy(() -> new ProductQuery(null, null, 0, 0, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new ProductQuery(null, null, 0, 101, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void acceptsTheMaximumSize() {
        assertThat(new ProductQuery(null, null, 0, 100, null).size()).isEqualTo(100);
    }

    @Test
    void rejectsASortFieldOutsideTheWhitelist() {
        assertThatThrownBy(() -> new ProductQuery(null, null, 0, 20, "sku,asc"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsAnUnknownSortDirection() {
        assertThatThrownBy(() -> new ProductQuery(null, null, 0, 20, "name,sideways"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsAMalformedSortExpression() {
        assertThatThrownBy(() -> new ProductQuery(null, null, 0, 20, "name,asc,extra"))
                .isInstanceOf(InvalidRequestException.class);
    }
}
