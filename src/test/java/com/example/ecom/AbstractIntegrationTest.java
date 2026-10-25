package com.example.ecom;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts one PostgreSQL container for the whole test run (the "singleton container"
 * pattern) and points the Spring datasource at it.
 * <p>
 * The static initialiser means the container starts once, no matter how many test
 * classes extend this; {@code @Testcontainers}/{@code @Container} would start a fresh
 * one per class instead, which is slower and gives every class its own migrated schema.
 * Testcontainers' Ryuk sidecar removes it when the JVM exits.
 */
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
