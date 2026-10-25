package com.example.ecom.user;


import com.example.ecom.common.domain.AuditableEntity;
import jakarta.persistence.*;

import java.util.Locale;

@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false, name = "first_name", length = 100)
    private String firstName;

    @Column(nullable = false, name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    protected User() {}

    public User(String email, String passwordHash, String firstName, String lastName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;

    }

    public static String normalizeEmail(String rawEmail) {
        return rawEmail == null ? null : rawEmail .trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Role getRole() {
        return role;
    }
}
