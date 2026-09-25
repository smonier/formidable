package org.jahia.modules.formidable.engine.actions.field;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final RateLimiter limiter = new RateLimiter(now::get);

    @Test
    void allowsTheLimitPerClientAndMinuteThenRefusesUntilTheWindowSlides() {
        // Verifies the sliding window: two calls pass under a limit of two, the third is refused, another client is
        // counted apart, and once the first call is a minute old a new one passes again.
        assertTrue(limiter.allow("10.0.0.1", 2));
        assertTrue(limiter.allow("10.0.0.1", 2));
        assertFalse(limiter.allow("10.0.0.1", 2));
        assertTrue(limiter.allow("10.0.0.2", 2));

        now.addAndGet(60_001L);
        assertTrue(limiter.allow("10.0.0.1", 2));
    }

    @Test
    void aLimitOfZeroAllowsNothing() {
        // Verifies the off switch: 0 is how the administrator disables the pre-check, and the limiter answers no
        // before recording anything.
        assertFalse(limiter.allow("10.0.0.1", 0));
        assertFalse(limiter.allow("10.0.0.1", -5));
    }

    @Test
    void aNullKeyIsOneClient() {
        // Verifies a missing address does not bypass the limit: every such call counts against the one empty key.
        assertTrue(limiter.allow(null, 1));
        assertFalse(limiter.allow(null, 1));
    }
}
