package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RequestRateLimitFilterTest {
    @Test
    void rejectsRequestsBeyondConfiguredPerMinuteLimitPerClient() {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        ReflectionTestUtils.setField(filter, "requestsPerMinute", 2);

        assertTrue(filter.isAllowed("127.0.0.1"));
        assertTrue(filter.isAllowed("127.0.0.1"));
        assertFalse(filter.isAllowed("127.0.0.1"));
        assertTrue(filter.isAllowed("127.0.0.2"));
    }
}
