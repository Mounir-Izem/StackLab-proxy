package app.stacklab.proxy.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void burstOfTenAllowedThenEleventhDenied() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.allow("1.2.3.4")).isTrue();
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
    }

    @Test
    void perIpBucketsAreIndependent() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiter.allow("1.1.1.1");
        }
        assertThat(limiter.allow("1.1.1.1")).isFalse();
        assertThat(limiter.allow("2.2.2.2")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleBucketsAreEvicted() {
        RateLimiter limiter = new RateLimiter();
        limiter.allow("9.9.9.9");

        Map<String, Object> buckets = (Map<String, Object>) ReflectionTestUtils.getField(limiter, "perIp");
        Object bucket = buckets.get("9.9.9.9");
        long staleAccess = System.currentTimeMillis() - Duration.ofMinutes(11).toMillis();
        ReflectionTestUtils.setField(bucket, "lastAccessMillis", staleAccess);

        ReflectionTestUtils.invokeMethod(limiter, "evictStale");

        assertThat(buckets).doesNotContainKey("9.9.9.9");
    }
}
