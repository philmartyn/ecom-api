package com.example.ecom.common.config;

import com.example.ecom.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security perimeter, exercised through the real filter chain.
 * <p>
 * Public routes must stay reachable; everything else must answer {@code 401}
 * with the API's problem+json error shape and a {@code WWW-Authenticate}
 * header. The "unknown route" case is deliberate: authorization runs before
 * request mapping, so an unauthenticated caller must not be able to tell a
 * protected route from one that does not exist yet. Catalog reads are public,
 * which is why an unknown <em>product</em> answers {@code 404} without a token
 * while an unknown <em>top-level</em> route still answers {@code 401}.
 * <p>
 * A {@code 403} case is not here because no route requires {@code ADMIN} yet.
 * It arrives with the admin slice, where {@code hasRole("ADMIN")} can be
 * asserted end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityPerimeterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void leavesRegistrationPublic() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavesLoginPublic() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavesHealthAndApiDocsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void leavesTheCatalogPublic() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Test
    void leavesAProductDetailPublicEvenWhenTheSlugIsUnknown() throws Exception {
        mockMvc.perform(get("/api/products/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void rejectsAProtectedRouteWithoutATokenUsingProblemJson() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication is required"))
                .andExpect(jsonPath("$.instance").value("/api/auth/me"));
    }

    @Test
    void rejectsAnUnknownRouteBeforeItCanRevealA404() throws Exception {
        mockMvc.perform(get("/api/not-a-real-route"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void requiresATokenForEveryCartRoute() throws Exception {
        // The cart is per user, so no cart route is public. Authorization runs
        // before request mapping and body parsing, so even a bodiless POST is
        // rejected as unauthenticated rather than as a validation error.
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(post("/api/cart/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(patch("/api/cart/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(delete("/api/cart/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void requiresATokenForEveryOrderRoute() throws Exception {
        // Orders are per user, so no order route is public. Authorization runs
        // before request mapping, so even a bodiless POST is rejected as
        // unauthenticated rather than as a validation error.
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(post("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(post("/api/orders/1/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void requiresATokenForEveryPaymentRoute() throws Exception {
        // Payment attempts are per order, so no payment route is public. The
        // webhook is the single deliberate exception, tested below.
        mockMvc.perform(post("/api/orders/1/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(get("/api/orders/1/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(post("/api/orders/1/payments/2/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void leavesThePaymentWebhookPublicButStillRequiresAValidSignature() throws Exception {
        // A 400 (not a 401) proves the route is outside the JWT perimeter. The
        // signature is its only gate, so a bodiless unsigned call must not be
        // treated as authenticated.
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid webhook signature"));
    }

    @Test
    void rejectsGarbageInTheAuthorizationHeaderWithTheSameBody() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
    }
}
