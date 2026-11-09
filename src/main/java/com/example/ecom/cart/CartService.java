package com.example.ecom.cart;

import com.example.ecom.catalog.Product;
import com.example.ecom.catalog.ProductRepository;
import com.example.ecom.common.exception.CartItemNotFoundException;
import com.example.ecom.common.exception.InsufficientStockException;
import com.example.ecom.common.exception.ProductNotFoundException;
import com.example.ecom.user.User;
import com.example.ecom.user.UserAccess;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserAccess userAccess;

    public CartService(CartRepository cartRepository,
                       ProductRepository productRepository,
                       UserAccess userAccess) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userAccess = userAccess;;
    }

    public CartResponse getCart(long userId) {
        userAccess.requireExists(userId);

        return cartRepository.findByUserId(userId)
                .map(CartResponse::from)
                .orElseGet(CartResponse::empty);
    }

    @Transactional
    public CartResponse addItem(long userId, long productId, int quantity) {
        User user = userAccess.lockUser(userId);
        Product product = activeProduct(productId);
        Cart cart = cartFor(user);

        ensureStock(product, cart.quantityAfterAdding(product, quantity));

        cart.addItem(product, quantity);
        cartRepository.saveAndFlush(cart);
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse changeQuantity(long userId, long productId, int quantity) {
        User user = userAccess.lockUser(userId);
        Product product = activeProduct(productId);

        Cart cart = cartRepository.findByUserId(user.getId())
                .orElseThrow(() -> new CartItemNotFoundException(productId));

        if (!cart.containsProduct(product)) {
            throw new CartItemNotFoundException(productId);
        }
        ensureStock(product, quantity);

        cart.changeQuantity(product, quantity);
        cartRepository.saveAndFlush(cart);
        return CartResponse.from(cart);
    }

    @Transactional
    public CartResponse removeItem(long userId, long productId) {
        User user = userAccess.lockUser(userId);

        Optional<Cart> existing = cartRepository.findByUserId(user.getId());
        if (existing.isEmpty()) {
            return CartResponse.empty();
        }

        Cart cart = existing.get();
        productRepository.findById(productId).ifPresent(cart::removeItem);
        cartRepository.saveAndFlush(cart);
        return CartResponse.from(cart);
    }

    private Product activeProduct(long productId) {
        return productRepository.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private Cart cartFor(User user) {
        return cartRepository.findByUserId(user.getId())
                .orElseGet(() -> cartRepository.save(new Cart(user)));
    }

    private static void ensureStock(Product product, long resultingQuantity) {
        if (resultingQuantity > product.getStockQuantity()) {
            throw new InsufficientStockException();
        }
    }

}
