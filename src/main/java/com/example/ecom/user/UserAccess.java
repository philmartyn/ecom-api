package com.example.ecom.user;

import com.example.ecom.common.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an authenticated caller id to a live {@link User} row.
 *
 * <p>Cart and order mutations run under the caller's User-row write lock so two
 * requests for the same user cannot interleave their reads and writes. The lock
 * belongs to the caller's transaction, so {@link #lockUser} refuses to start one
 * of its own.</p>
 */
@Service
public class UserAccess {

    private final UserRepository userRepository;

    public UserAccess(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Confirms the caller still exists. Read paths use this so a token for a
     * deleted user gets a 401 instead of an empty or stale response.
     */
    public void requireExists(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }

    /**
     * Takes the per-user write lock and returns the locked row.
     *
     * <p>{@link Propagation#MANDATORY} is deliberate: the lock must be held by
     * the caller's transaction so it is released only when the cart or order
     * write commits. Starting a new transaction here would release the lock
     * before the caller has written anything.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User lockUser(long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
