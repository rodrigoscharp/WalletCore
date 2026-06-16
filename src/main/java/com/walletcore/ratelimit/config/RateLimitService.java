package com.walletcore.ratelimit.config;

import com.walletcore.config.error.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
    }

    public void checkTransferLimit(String userEmail) {
        var bucket = buckets.computeIfAbsent(userEmail, this::newBucket);
        if (!bucket.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Rate limit exceeded: max %d transfers per %d minute(s)",
                            properties.getCapacity(), properties.getRefillDurationMinutes()));
        }
    }

    private Bucket newBucket(String key) {
        var bandwidth = Bandwidth.builder()
                .capacity(properties.getCapacity())
                .refillGreedy(properties.getRefillTokens(),
                        Duration.ofMinutes(properties.getRefillDurationMinutes()))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
