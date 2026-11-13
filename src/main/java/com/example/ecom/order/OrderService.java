package com.example.ecom.order;

import com.example.ecom.cart.Cart;
import com.example.ecom.cart.CartItem;
import com.example.ecom.cart.CartRepository;
import com.example.ecom.common.exception.*;
import com.example.ecom.common.web.PageParams;
import com.example.ecom.common.web.PageResponse;
import com.example.ecom.payment.PaymentAttemptCloser;
import com.example.ecom.user.User;
import com.example.ecom.user.UserAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final PaymentAttemptCloser paymentAttemptCloser;
    private final UserAccess userAccess;

    public OrderService(OrderRepository orderRepository,
                        CartRepository cartRepository,
                        PaymentAttemptCloser paymentAttemptCloser,
                        UserAccess userAccess) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.paymentAttemptCloser = paymentAttemptCloser;
        this.userAccess = userAccess;
    }

    @Transactional
    public OrderResponse checkout(long userId, ShippingAddress shippingAddress) {
        User user = userAccess.lockUser(userId);

        Cart cart = cartRepository.findByUserId(userId)
                .filter(existing -> !existing.getItems().isEmpty())
                .orElseThrow(EmptyCartException::new);

        List<UnavailableItem> unavailable = cart.unavailableItems(cart);
        if (!unavailable.isEmpty()) {
            throw new CartNotCheckoutableException(unavailable);
        }

        Order order = new Order(user, shippingAddress);
        for (CartItem item : cart.getItems()) {
            order.addItem(item.getProduct(), item.getQuantity());
        }
        orderRepository.saveAndFlush(order);

        cart.clear();
        cartRepository.saveAndFlush(cart);

        return OrderResponse.from(order);
    }

    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt")
                    .and(Sort.by(Sort.Direction.DESC, "id"));

    public PageResponse<OrderSummaryResponse> listOrders(long userId, int page, int size) {
        userAccess.requireExists(userId);

        Page<Order> orders = orderRepository.findByUserId(
                userId, PageParams.toPageRequest(page, size, NEWEST_FIRST));
        return PageResponse.from(orders.map(OrderSummaryResponse::from));
    }

    public OrderResponse getOrder(long userId, long orderId) {
        userAccess.requireExists(userId);

        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public OrderResponse cancel(long userId, long orderId) {
        userAccess.requireExists(userId);

        Order order = orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.cancel();
        paymentAttemptCloser.closeOpenAttempt(orderId, "order_cancelled");
        orderRepository.saveAndFlush(order);

        return OrderResponse.from(order);
    }


}
