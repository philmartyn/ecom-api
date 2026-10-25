package com.example.ecom.user;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test: no Spring, no database, milliseconds to run.
 * This is where business rules that live in the entity belong.
 */
class UserTest {

    @Test
    void storesEmailLowercasedAndTrimmed() {
        User user = new User("  Bob@Example.COM ", "hash", "Bob", "Smith", Role.CUSTOMER);

        assertThat(user.getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void normalizeEmailIsIdempotent() {
        String once = User.normalizeEmail(" Bob@Example.COM ");

        assertThat(User.normalizeEmail(once)).isEqualTo(once);
    }

    @Test
    void normalizeEmailReturnsNullForNull() {
        assertThat(User.normalizeEmail(null)).isNull();
    }

    @Test
    void keepsTheOtherFieldsAsGiven() {
        User user = new User("bob@example.com", "hash", "Bob", "Smith", Role.ADMIN);

        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getFirstName()).isEqualTo("Bob");
        assertThat(user.getLastName()).isEqualTo("Smith");
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void newUserHasNoIdYet() {
        User user = new User("bob@example.com", "hash", "Bob", "Smith", Role.CUSTOMER);

        assertThat(user.getId()).isNull();
    }

    @Test
    void roleNamesMatchTheDatabaseCheckConstraint() {
        // V1__initial_schema.sql: CHECK (role IN ('CUSTOMER', 'ADMIN'))
        // Renaming a constant without a migration breaks inserts at runtime.
        assertThat(Arrays.stream(Role.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrder("CUSTOMER", "ADMIN");
    }
}
