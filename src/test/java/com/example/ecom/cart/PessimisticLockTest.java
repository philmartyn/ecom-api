package com.example.ecom.cart;

import com.example.ecom.AbstractIntegrationTest;
import com.example.ecom.user.Role;
import com.example.ecom.user.User;
import com.example.ecom.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the guarantee behind ADR 0003: a cart mutation locks the User row, so a
 * second mutation for the same User waits.
 * <p>
 * There is deliberately no class-level {@code @Transactional}: the whole point is
 * to hold a real database lock across a thread boundary. Thread 1 opens a
 * transaction, takes {@code SELECT … FOR UPDATE} on the User and keeps the
 * transaction open. Thread 2 opens its own transaction and calls the same method.
 * If the lock exists, thread 2 has not finished after a second; when thread 1
 * commits, thread 2 completes. Removing {@code @Lock(PESSIMISTIC_WRITE)} makes
 * the short wait succeed and this test fail.
 * <p>
 * The users created here commit, so they are deleted afterwards and use emails no
 * other fixture uses.
 */
@SpringBootTest
class PessimisticLockTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        createdUserIds.forEach(userRepository::deleteById);
    }

    @Test
    void aSecondTransactionBlocksWhileTheUserRowIsLocked() throws Exception {
        User user = newUser("lock-holder@example.com");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Void> holder = executor.submit((Callable<Void>) () -> {
            transaction.executeWithoutResult(status -> {
                userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                locked.countDown();
                await(release);
            });
            return null;
        });

        assertThat(locked.await(5, TimeUnit.SECONDS))
                .as("thread 1 should acquire the lock quickly")
                .isTrue();

        Future<User> contender = executor.submit(() ->
                transaction.execute(status ->
                        userRepository.findByIdForUpdate(user.getId()).orElseThrow()));

        assertThatThrownBy(() -> contender.get(1, TimeUnit.SECONDS))
                .as("the second transaction must still be waiting for the lock")
                .isInstanceOf(TimeoutException.class);

        release.countDown();

        assertThat(contender.get(10, TimeUnit.SECONDS).getId()).isEqualTo(user.getId());
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void locksOnDifferentUsersDoNotBlockEachOther() throws Exception {
        User first = newUser("lock-first@example.com");
        User second = newUser("lock-second@example.com");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Void> holder = executor.submit((Callable<Void>) () -> {
            transaction.executeWithoutResult(status -> {
                userRepository.findByIdForUpdate(first.getId()).orElseThrow();
                locked.countDown();
                await(release);
            });
            return null;
        });

        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        Future<User> other = executor.submit(() ->
                transaction.execute(status ->
                        userRepository.findByIdForUpdate(second.getId()).orElseThrow()));

        assertThat(other.get(2, TimeUnit.SECONDS).getId())
                .as("the lock is per User, not global")
                .isEqualTo(second.getId());

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aPlainReadIsNotBlockedByTheLock() throws Exception {
        User user = newUser("lock-reader@example.com");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();

        Future<Void> holder = executor.submit((Callable<Void>) () -> {
            try {
                transaction.executeWithoutResult(status -> {
                    userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                    locked.countDown();
                    await(release);
                });
            } catch (Throwable t) {
                holderFailure.set(t);
            }
            return null;
        });

        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        Future<User> reader = executor.submit(() ->
                transaction.execute(status -> userRepository.findById(user.getId()).orElseThrow()));

        assertThat(reader.get(2, TimeUnit.SECONDS).getId())
                .as("GET /api/cart does not take the write lock")
                .isEqualTo(user.getId());

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(holderFailure.get()).isNull();
    }

    private User newUser(String email) {
        User user = userRepository.saveAndFlush(
                new User(email, "password-hash", "Lock", "Test", Role.CUSTOMER));
        createdUserIds.add(user.getId());
        return user;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the lock", e);
        }
    }
}
