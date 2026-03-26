package com.example.productcatalog.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/cache/stats")
public class CacheStatsController {

    private final CacheManager cacheManager;

    public CacheStatsController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @GetMapping
    public Map<String, Object> stats() {
        CaffeineCache caffeineCache = (CaffeineCache) cacheManager.getCache("products");
        Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
        CacheStats stats = nativeCache.stats();

        return Map.of(
            "size",       nativeCache.estimatedSize(),
            "hits",       stats.hitCount(),
            "misses",     stats.missCount(),
            "hitRate",    Math.round(stats.hitRate() * 100) + "%",
            "evictions",  stats.evictionCount()
        );
    }
}
