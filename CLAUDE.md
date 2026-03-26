# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
./mvnw spring-boot:run        # Run the application (http://localhost:8080)
./mvnw test                   # Run all tests
./mvnw clean package          # Build JAR
./mvnw -Dtest=ProductCatalogIntegrationTest test  # Run a single test class
```

## Architecture Overview

This is a Spring Boot 3 / Java 21 REST API demonstrating a **migration showcase** — the app is designed to swap out its three infrastructure layers:

| Layer     | Current (embedded)       | Migration target |
|-----------|--------------------------|------------------|
| Database  | H2 in-memory             | PostgreSQL       |
| Messaging | ActiveMQ Artemis         | RabbitMQ         |
| Cache     | Caffeine                 | Valkey           |

### Request Flow

```
REST Request → ProductController → ProductService → ProductRepository (JPA/H2)
                                        ↓
                              ProductEventPublisher (JMS → Artemis)
                                        ↓
                              ProductEventListener → SseEmitter (SSE stream)
```

`ProductService` is the central component: it handles CRUD, applies `@Cacheable`/`@CacheEvict`, and triggers JMS events on create/delete.

### Key Endpoints

| Method | Path            | Description                         |
|--------|-----------------|-------------------------------------|
| GET    | `/products`     | List all products                   |
| GET    | `/products/{id}`| Get single product (Caffeine cached)|
| POST   | `/products`     | Create product (publishes JMS event)|
| DELETE | `/products/{id}`| Delete product (evicts cache + event)|
| GET    | `/cache/stats`  | Caffeine hit/miss metrics           |
| GET    | `/events`       | SSE stream of product lifecycle events |
| GET    | `/h2-console`   | H2 database browser                 |
| GET    | `/`             | Single-page demo UI                 |

### Messaging Protocol

Events on the `product-events` JMS queue use plain-text format:
- Create: `"CREATED:<id>:<name>"`
- Delete: `"DELETED:<id>"`

`ProductEventListener` forwards these to browser clients via `SseEmitter` (managed in `ProductEventSseController`).

### Caching

Caffeine cache named `products`, max 500 entries, 60s write expiry, stats enabled. Cache keys are product IDs. `CacheStatsController` exposes hit/miss/size metrics.

### Testing

`ProductCatalogIntegrationTest` is the regression baseline for migrations — it verifies CRUD, cache hit/miss behavior, cache eviction on delete, and JMS event publishing. Keep this test green before and after any migration work.
