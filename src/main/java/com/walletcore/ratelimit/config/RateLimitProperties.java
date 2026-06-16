package com.walletcore.ratelimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "walletcore.rate-limit.transfer")
public class RateLimitProperties {

    private long capacity = 10;
    private long refillTokens = 10;
    private long refillDurationMinutes = 1;

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public long getRefillTokens() { return refillTokens; }
    public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }

    public long getRefillDurationMinutes() { return refillDurationMinutes; }
    public void setRefillDurationMinutes(long refillDurationMinutes) {
        this.refillDurationMinutes = refillDurationMinutes;
    }
}
