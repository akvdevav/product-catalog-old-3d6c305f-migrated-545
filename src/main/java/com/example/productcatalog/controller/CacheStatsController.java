package com.example.productcatalog.controller;

import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/cache/stats")
public class CacheStatsController {

    private final RedisCacheManager cacheManager;

    public CacheStatsController(RedisCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @GetMapping
    public Map<String, Object> stats() {
        // Note: RedisCacheManager does not expose detailed statistics like CaffeineCache.
        // This is a placeholder implementation; actual Redis metrics would require
        // custom monitoring or integration with Redis INFO commands.
        return Map.of(
            "message", "Redis cache statistics not directly available via RedisCacheManager"
        );
    }
}