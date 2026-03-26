# Product Catalog Source App — Implementation Plan

## Purpose

Build a minimal Spring Boot 3.x application called `product-catalog-source` that exercises all three technology categories targeted by the AI migration tool, with a built-in web UI for live demonstration.

| Category | Source technology | Migration target |
|---|---|---|
| Messaging | ActiveMQ Artemis (embedded) | RabbitMQ |
| Database | H2 (in-memory) | PostgreSQL |
| Cache | Caffeine (in-memory) | Valkey |

The app must run entirely locally with no external services. The UI is a single static HTML page served by Spring Boot that lets you interact with all three data services in real time.

---

## Step 1 — Project Setup

### Coordinates

```
groupId:    com.example
artifactId: product-catalog-source
version:    0.0.1-SNAPSHOT
packaging:  jar
```

### Java & Spring Boot versions

- Java 21
- Spring Boot 3.4.x (latest 3.x patch)

### Dependencies (Maven)

```xml
<dependencies>

  <!-- Web -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>

  <!-- JPA + H2 -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
  </dependency>

  <!-- Messaging — Artemis embedded -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-artemis</artifactId>
  </dependency>
  <dependency>
    <groupId>org.apache.activemq</groupId>
    <artifactId>artemis-jakarta-server</artifactId>
  </dependency>

  <!-- Cache + Caffeine -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
  </dependency>
  <dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
  </dependency>

  <!-- Test -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>

</dependencies>
```

> **Note on Artemis artifact name**: Spring Boot 3.x uses Jakarta EE 10, so the embedded server artifact is `artemis-jakarta-server`, not `artemis-jms-server`.

---

## Step 2 — Configuration

### `src/main/resources/application.properties`

```properties
spring.application.name=product-catalog-source

# H2
spring.datasource.url=jdbc:h2:mem:productdb;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

# H2 console
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Artemis embedded broker
spring.artemis.mode=embedded
spring.artemis.embedded.enabled=true
spring.artemis.embedded.queues=product-events

# Caffeine cache — recordStats enables hit/miss counters exposed by /cache/stats
spring.cache.type=caffeine
spring.cache.caffeine.spec=maximumSize=500,expireAfterWrite=60s,recordStats
spring.cache.cache-names=products
```

---

## Step 3 — Domain Model

### `Product.java`

```java
package com.example.productcatalog.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    // constructors, getters, setters
}
```

### `ProductRepository.java`

```java
package com.example.productcatalog.repository;

import com.example.productcatalog.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {}
```

---

## Step 4 — Service Layer

### `ProductService.java`

```java
package com.example.productcatalog.service;

import com.example.productcatalog.messaging.ProductEventPublisher;
import com.example.productcatalog.model.Product;
import com.example.productcatalog.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository repository;
    private final ProductEventPublisher eventPublisher;

    public ProductService(ProductRepository repository, ProductEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public List<Product> getAllProducts() {
        return repository.findAll();
    }

    @Cacheable(value = "products", key = "#id")
    public Product getProduct(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }

    public Product createProduct(Product product) {
        Product saved = repository.save(product);
        eventPublisher.publishCreated(saved);
        return saved;
    }

    @CacheEvict(value = "products", key = "#id")
    public void deleteProduct(Long id) {
        repository.deleteById(id);
        eventPublisher.publishDeleted(id);
    }
}
```

---

## Step 5 — Messaging

### `ProductEventPublisher.java`

```java
package com.example.productcatalog.messaging;

import com.example.productcatalog.model.Product;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProductEventPublisher {

    private static final String QUEUE = "product-events";
    private final JmsTemplate jmsTemplate;

    public ProductEventPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void publishCreated(Product product) {
        jmsTemplate.convertAndSend(QUEUE, "CREATED:" + product.getId() + ":" + product.getName());
    }

    public void publishDeleted(Long id) {
        jmsTemplate.convertAndSend(QUEUE, "DELETED:" + id);
    }
}
```

### `ProductEventListener.java`

The listener receives JMS messages and forwards them to any connected SSE clients (the UI event log).

```java
package com.example.productcatalog.messaging;

import com.example.productcatalog.controller.ProductEventSseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductEventSseController sseController;

    public ProductEventListener(ProductEventSseController sseController) {
        this.sseController = sseController;
    }

    @JmsListener(destination = "product-events")
    public void onProductEvent(String message) {
        log.info("Received product event: {}", message);
        sseController.broadcast(message);
    }
}
```

---

## Step 6 — REST Controllers

### `ProductController.java`

```java
package com.example.productcatalog.controller;

import com.example.productcatalog.model.Product;
import com.example.productcatalog.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<Product> list() {
        return service.getAllProducts();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return service.getProduct(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@RequestBody Product product) {
        return service.createProduct(product);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.deleteProduct(id);
    }
}
```

### `CacheStatsController.java`

Exposes Caffeine hit/miss counters so the UI can display live cache statistics.

```java
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
```

### `ProductEventSseController.java`

Streams JMS events to the browser using Server-Sent Events. The UI event log subscribes to this endpoint on page load.

```java
package com.example.productcatalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/events")
public class ProductEventSseController {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping(produces = "text/event-stream")
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(String message) {
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
```

---

## Step 7 — Main Application Class

```java
package com.example.productcatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.jms.annotation.EnableJms;

@SpringBootApplication
@EnableCaching
@EnableJms
public class ProductCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductCatalogApplication.class, args);
    }
}
```

---

## Step 8 — Demo UI

Place this file at `src/main/resources/static/index.html`. Spring Boot serves it automatically at `http://localhost:8080`.

The UI has three panels — one per data service — and requires no build step or external dependencies.

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Product Catalog · Migration Demo</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: system-ui, sans-serif;
      font-size: 14px;
      background: #0f1117;
      color: #e2e8f0;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }

    header {
      padding: 18px 24px;
      background: #1a1d27;
      border-bottom: 1px solid #2d3148;
      display: flex;
      align-items: baseline;
      gap: 16px;
    }
    header h1 { font-size: 16px; font-weight: 600; color: #f1f5f9; }
    header p  { font-size: 12px; color: #64748b; }

    .grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 0;
      flex: 1;
    }

    .panel {
      padding: 20px;
      border-right: 1px solid #2d3148;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .panel:last-child { border-right: none; }

    .panel-header {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding-bottom: 12px;
      border-bottom: 1px solid #2d3148;
    }
    .panel-header h2 { font-size: 13px; font-weight: 600; }
    .panel-header span { font-size: 11px; color: #64748b; }

    /* panel accent colors */
    .panel-db   .panel-header h2 { color: #60a5fa; }
    .panel-cache .panel-header h2 { color: #34d399; }
    .panel-msg  .panel-header h2 { color: #fbbf24; }

    label { display: block; font-size: 11px; color: #94a3b8; margin-bottom: 4px; }

    input[type="text"], input[type="number"] {
      width: 100%;
      padding: 6px 10px;
      background: #1a1d27;
      border: 1px solid #2d3148;
      border-radius: 6px;
      color: #e2e8f0;
      font-size: 13px;
      outline: none;
    }
    input:focus { border-color: #3b82f6; }

    .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }

    button {
      padding: 7px 14px;
      border: none;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: opacity .15s;
    }
    button:hover { opacity: .85; }
    .btn-blue   { background: #3b82f6; color: #fff; }
    .btn-green  { background: #10b981; color: #fff; }
    .btn-red    { background: #ef4444; color: #fff; font-size: 11px; padding: 3px 8px; }
    .btn-amber  { background: #f59e0b; color: #1a1d27; }

    /* product table */
    .product-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .product-table th {
      text-align: left;
      padding: 5px 8px;
      color: #64748b;
      font-weight: 500;
      border-bottom: 1px solid #2d3148;
    }
    .product-table td { padding: 6px 8px; border-bottom: 1px solid #1e2235; vertical-align: middle; }
    .product-table tr:last-child td { border-bottom: none; }
    .empty { font-size: 12px; color: #475569; text-align: center; padding: 20px; }

    /* cache stats */
    .stats-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
    .stat-box {
      background: #1a1d27;
      border: 1px solid #2d3148;
      border-radius: 8px;
      padding: 10px 12px;
    }
    .stat-label { font-size: 10px; color: #64748b; margin-bottom: 2px; }
    .stat-value { font-size: 20px; font-weight: 600; color: #34d399; }
    .stat-value.miss { color: #f87171; }
    .stat-value.rate { color: #a78bfa; font-size: 18px; }

    .fetch-result {
      background: #1a1d27;
      border: 1px solid #2d3148;
      border-radius: 6px;
      padding: 10px 12px;
      font-size: 12px;
      min-height: 48px;
      color: #94a3b8;
    }
    .fetch-result.hit  { border-color: #34d399; color: #34d399; }
    .fetch-result.miss { border-color: #f87171; color: #f87171; }

    /* event log */
    .event-log {
      flex: 1;
      background: #1a1d27;
      border: 1px solid #2d3148;
      border-radius: 8px;
      padding: 10px;
      overflow-y: auto;
      max-height: calc(100vh - 240px);
      min-height: 200px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .event-entry {
      font-family: monospace;
      font-size: 11px;
      padding: 4px 6px;
      border-radius: 4px;
      animation: fadein .3s ease;
    }
    @keyframes fadein { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; } }
    .event-created { background: #1c2e1e; color: #4ade80; }
    .event-deleted { background: #2e1c1c; color: #f87171; }
    .event-system  { color: #475569; font-style: italic; }

    .conn-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 11px;
      color: #64748b;
    }
    .conn-dot {
      width: 6px; height: 6px;
      border-radius: 50%;
      background: #475569;
    }
    .conn-dot.connected { background: #34d399; }

    .field-group { display: flex; flex-direction: column; gap: 6px; }
    .input-row { display: flex; gap: 8px; }
    .input-row input { flex: 1; }
  </style>
</head>
<body>

<header>
  <h1>Product Catalog · Migration Demo</h1>
  <p>Spring Boot 3 · H2 · ActiveMQ Artemis · Caffeine</p>
</header>

<div class="grid">

  <!-- ── DATABASE PANEL ───────────────────────────────────────────── -->
  <div class="panel panel-db">
    <div class="panel-header">
      <h2>Database · H2</h2>
      <span>Products are persisted via JPA and stored in-memory</span>
    </div>

    <div class="field-group">
      <label>Create a product</label>
      <input type="text"   id="p-name"  placeholder="Name" />
      <input type="text"   id="p-desc"  placeholder="Description" />
      <div class="form-row">
        <input type="number" id="p-price" placeholder="Price (e.g. 9.99)" step="0.01" min="0" />
        <input type="number" id="p-stock" placeholder="Stock" min="0" />
      </div>
      <button class="btn-blue" onclick="createProduct()">Create product</button>
    </div>

    <div>
      <label>Current products</label>
      <table class="product-table">
        <thead>
          <tr>
            <th>ID</th><th>Name</th><th>Price</th><th>Stock</th><th></th>
          </tr>
        </thead>
        <tbody id="product-list">
          <tr><td colspan="5" class="empty">No products yet</td></tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- ── CACHE PANEL ───────────────────────────────────────────────── -->
  <div class="panel panel-cache">
    <div class="panel-header">
      <h2>Cache · Caffeine</h2>
      <span>Fetch a product twice — the second call skips the database</span>
    </div>

    <div>
      <label>Live cache stats</label>
      <div class="stats-grid">
        <div class="stat-box">
          <div class="stat-label">Hits</div>
          <div class="stat-value" id="stat-hits">0</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">Misses</div>
          <div class="stat-value miss" id="stat-misses">0</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">Hit rate</div>
          <div class="stat-value rate" id="stat-rate">—</div>
        </div>
        <div class="stat-box">
          <div class="stat-label">Cached entries</div>
          <div class="stat-value rate" id="stat-size">0</div>
        </div>
      </div>
    </div>

    <div class="field-group">
      <label>Fetch product by ID</label>
      <div class="input-row">
        <input type="number" id="fetch-id" placeholder="Product ID" min="1" />
        <button class="btn-green" onclick="fetchProduct()">Fetch</button>
      </div>
      <div class="fetch-result" id="fetch-result">
        Result will appear here. Fetch the same ID twice to see a cache hit.
      </div>
    </div>

    <div style="font-size:11px;color:#475569;line-height:1.6;">
      Hits and misses are tracked by Caffeine's built-in stats recorder.
      Cache entries expire after 60 seconds or when the product is deleted.
    </div>
  </div>

  <!-- ── MESSAGING PANEL ──────────────────────────────────────────── -->
  <div class="panel panel-msg">
    <div class="panel-header">
      <h2>Messaging · Artemis</h2>
      <span>JMS events from the product-events queue, streamed via SSE</span>
    </div>

    <div style="display:flex; align-items:center; justify-content:space-between;">
      <label style="margin:0">Live event log</label>
      <span class="conn-badge">
        <span class="conn-dot" id="conn-dot"></span>
        <span id="conn-label">Connecting…</span>
      </span>
    </div>

    <div class="event-log" id="event-log">
      <div class="event-entry event-system">Waiting for events…</div>
    </div>

    <div style="font-size:11px;color:#475569;line-height:1.6;">
      Every create and delete publishes a message to the <code>product-events</code> JMS queue.
      The listener receives it and forwards it here over Server-Sent Events.
    </div>
  </div>

</div>

<script>
  const API = '';

  // ── Products ──────────────────────────────────────────────────────
  async function loadProducts() {
    const res = await fetch(`${API}/products`);
    const products = await res.json();
    const tbody = document.getElementById('product-list');
    if (products.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty">No products yet</td></tr>';
      return;
    }
    tbody.innerHTML = products.map(p => `
      <tr>
        <td>${p.id}</td>
        <td>${p.name}</td>
        <td>$${Number(p.price).toFixed(2)}</td>
        <td>${p.stock}</td>
        <td><button class="btn-red" onclick="deleteProduct(${p.id})">Delete</button></td>
      </tr>
    `).join('');
  }

  async function createProduct() {
    const name  = document.getElementById('p-name').value.trim();
    const desc  = document.getElementById('p-desc').value.trim();
    const price = document.getElementById('p-price').value;
    const stock = document.getElementById('p-stock').value;
    if (!name || !price || !stock) return;

    await fetch(`${API}/products`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description: desc, price: parseFloat(price), stock: parseInt(stock) })
    });

    ['p-name','p-desc','p-price','p-stock'].forEach(id => document.getElementById(id).value = '');
    loadProducts();
    loadStats();
  }

  async function deleteProduct(id) {
    await fetch(`${API}/products/${id}`, { method: 'DELETE' });
    loadProducts();
    loadStats();
  }

  // ── Cache stats ───────────────────────────────────────────────────
  async function loadStats() {
    const res  = await fetch(`${API}/cache/stats`);
    const data = await res.json();
    document.getElementById('stat-hits').textContent   = data.hits;
    document.getElementById('stat-misses').textContent = data.misses;
    document.getElementById('stat-rate').textContent   = (data.hits + data.misses) === 0 ? '—' : data.hitRate;
    document.getElementById('stat-size').textContent   = data.size;
  }

  async function fetchProduct() {
    const id = document.getElementById('fetch-id').value;
    if (!id) return;

    const before = await (await fetch(`${API}/cache/stats`)).json();
    const hitsBefore = before.hits;

    const res = await fetch(`${API}/products/${id}`);
    if (!res.ok) {
      const el = document.getElementById('fetch-result');
      el.className = 'fetch-result miss';
      el.textContent = `Product ${id} not found.`;
      return;
    }
    const product = await res.json();

    const after = await (await fetch(`${API}/cache/stats`)).json();
    const hitsAfter = after.hits;
    const isHit = hitsAfter > hitsBefore;

    const el = document.getElementById('fetch-result');
    el.className = `fetch-result ${isHit ? 'hit' : 'miss'}`;
    el.textContent = `${isHit ? '✓ Cache HIT' : '✗ Cache MISS (fetched from DB)'} — ${product.name} · $${Number(product.price).toFixed(2)} · stock: ${product.stock}`;

    document.getElementById('stat-hits').textContent   = after.hits;
    document.getElementById('stat-misses').textContent = after.misses;
    document.getElementById('stat-rate').textContent   = after.hitRate;
    document.getElementById('stat-size').textContent   = after.size;
  }

  // ── SSE event log ─────────────────────────────────────────────────
  function connectSse() {
    const dot   = document.getElementById('conn-dot');
    const label = document.getElementById('conn-label');
    const log   = document.getElementById('event-log');
    const sse   = new EventSource(`${API}/events`);

    sse.onopen = () => {
      dot.classList.add('connected');
      label.textContent = 'Connected';
    };

    sse.onmessage = (e) => {
      const msg  = e.data;
      const div  = document.createElement('div');
      const time = new Date().toLocaleTimeString();

      if (msg.startsWith('CREATED')) {
        div.className = 'event-entry event-created';
        const [, id, name] = msg.split(':');
        div.textContent = `[${time}] ✚ CREATED  id=${id}  name=${name}`;
      } else if (msg.startsWith('DELETED')) {
        div.className = 'event-entry event-deleted';
        const [, id] = msg.split(':');
        div.textContent = `[${time}] ✖ DELETED  id=${id}`;
      } else {
        div.className = 'event-entry event-system';
        div.textContent = `[${time}] ${msg}`;
      }

      if (log.querySelector('.event-system')?.textContent.startsWith('Waiting')) {
        log.innerHTML = '';
      }
      log.appendChild(div);
      log.scrollTop = log.scrollHeight;
    };

    sse.onerror = () => {
      dot.classList.remove('connected');
      label.textContent = 'Reconnecting…';
    };
  }

  // ── Init ──────────────────────────────────────────────────────────
  loadProducts();
  loadStats();
  connectSse();
  setInterval(loadStats, 5000);
</script>
</body>
</html>
```

---

## Step 9 — Integration Test

### `ProductCatalogIntegrationTest.java`

```java
package com.example.productcatalog;

import com.example.productcatalog.model.Product;
import com.example.productcatalog.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductCatalogIntegrationTest {

    @Autowired
    ProductService service;

    @Test
    void createReadCacheDeleteTest() throws InterruptedException {
        // Create
        Product p = new Product();
        p.setName("Widget");
        p.setDescription("A test widget");
        p.setPrice(new BigDecimal("9.99"));
        p.setStock(100);
        Product saved = service.createProduct(p);
        assertThat(saved.getId()).isNotNull();

        // Read — first call is a cache miss (hits DB), second is a cache hit
        Product first  = service.getProduct(saved.getId());
        Product second = service.getProduct(saved.getId());
        assertThat(first.getName()).isEqualTo("Widget");
        assertThat(second.getName()).isEqualTo("Widget");

        // Delete — evicts from cache and publishes JMS event
        service.deleteProduct(saved.getId());

        // Allow JMS listener to process
        Thread.sleep(200);
    }
}
```

This test is the **pre-migration regression baseline**. Run it against the migrated app to confirm behavioral equivalence.

---

## Package Structure

```
src/main/java/com/example/productcatalog/
├── ProductCatalogApplication.java
├── controller/
│   ├── ProductController.java
│   ├── CacheStatsController.java
│   └── ProductEventSseController.java
├── service/
│   └── ProductService.java
├── messaging/
│   ├── ProductEventPublisher.java
│   └── ProductEventListener.java
├── model/
│   └── Product.java
└── repository/
    └── ProductRepository.java

src/main/resources/
├── application.properties
└── static/
    └── index.html

src/test/java/com/example/productcatalog/
└── ProductCatalogIntegrationTest.java
```

---

## Demo Walkthrough (after startup)

Open `http://localhost:8080` and follow these steps to exercise each service:

| Step | Action | What to observe |
|---|---|---|
| 1 | Fill in the form and click **Create product** | Row appears in the product table; event log shows `CREATED` message |
| 2 | Click **Create product** two more times with different names | Three rows in the table; three events in the log |
| 3 | Enter any product ID in the cache panel and click **Fetch** | Cache MISS — stats show +1 miss |
| 4 | Click **Fetch** again with the same ID | Cache HIT — stats show +1 hit, no new SQL in server log |
| 5 | Click **Delete** on a product row | Row disappears; event log shows `DELETED` message; cached entry evicted |
| 6 | Fetch the deleted product's ID | 404 not found — confirms H2 and cache are both cleared |

---

## Verification Checklist

Before handing the app to the migration tool, confirm:

- [ ] App starts with `./mvnw spring-boot:run` and no external services
- [ ] UI loads at `http://localhost:8080`
- [ ] Creating a product updates the table and shows a `CREATED` event in the log
- [ ] Fetching the same product ID twice shows MISS then HIT in the cache panel
- [ ] Deleting a product shows a `DELETED` event in the log
- [ ] H2 console at `http://localhost:8080/h2-console` shows the PRODUCTS table
- [ ] Integration test passes: `./mvnw test`
