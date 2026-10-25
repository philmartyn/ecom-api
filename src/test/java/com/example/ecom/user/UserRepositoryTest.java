package com.example.ecom.user;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.common.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository test against a real PostgreSQL container. Flyway applies V1 to the
 * container, so this exercises the actual DDL - indexes, CHECK constraints and all.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsUserByNormalizedEmail() {
        userRepository.saveAndFlush(user("Bob@Example.com"));

        assertThat(userRepository.findByEmail("bob@example.com"))
                .isPresent()
                .get()
                .extracting(User::getFirstName)
                .isEqualTo("Bob");
    }

    @Test
    void existsByEmailMatchesTheStoredNormalizedValue() {
        userRepository.saveAndFlush(user("Bob@Example.com"));

        assertThat(userRepository.existsByEmail("bob@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void uniqueIndexRejectsTheSameEmailInDifferentCase() {
        userRepository.saveAndFlush(user("bob@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user("BOB@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesRoleAsStringAndPopulatesTimestamps() {
        User saved = userRepository.saveAndFlush(user("bob@example.com"));

        Object storedRole = entityManager
                .createNativeQuery("SELECT role FROM users WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(storedRole).isEqualTo("CUSTOMER");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void savingTheSameManagedUserDoesNotCreateASecondRow() {
        User saved = userRepository.saveAndFlush(user("bob@example.com"));
        long countAfterFirstSave = userRepository.count();

        userRepository.saveAndFlush(saved);

        assertThat(userRepository.count()).isEqualTo(countAfterFirstSave);
        assertThat(userRepository.findAll()).hasSize(1);
    }

    private User user(String email) {
        return new User(email, "$2a$10$abcdefghijklmnopqrstuv", "Bob", "Smith", Role.CUSTOMER);
    }
}
