---
name: spring-boot-resilience4j
description: Fault tolerance patterns for Spring Boot 4.x on Java 25 using Resilience4j 2.4+ (resilience4j-spring-boot4). Use when implementing circuit breakers, retries with exponential backoff, rate limiters, bulkheads, time limiters or fallbacks, protecting services from cascading failures, or combining resilience with virtual threads. Validates configurations through Actuator endpoints.
allowed-tools: Read, Write, Edit, Bash
---

<!--
  Adapted from giuseppe-trisciuoglio/developer-kit@spring-boot-resilience4j (MIT, Boot 3 version).
  Changes in this copy: Spring Boot 4 / Resilience4j 2.4.0 (resilience4j-spring-boot4),
  spring-boot-starter-aspectj, RestClient instead of RestTemplate, records instead of Lombok builders,
  @MockitoBean instead of @MockBean, virtual-thread guidance, Spring Framework 7 native resilience
  comparison, corrected invalid @Retry attributes in examples.
-->

# Spring Boot Resilience4j Patterns (Boot 4 / Java 25)

## Overview

Resilience4j patterns (circuit breaker, retry, rate limiter, bulkhead, time limiter, fallback) for Spring Boot 4.x applications on Java 25, with configuration, virtual-thread guidance and testing workflows.

## When to Use

- Implementing fault tolerance and preventing cascading failures
- Adding circuit breakers, retry logic, or rate limiting to calls to external services
- Handling transient failures with exponential backoff
- Protecting services from overload and resource exhaustion
- Combining multiple patterns for comprehensive resilience

## Resilience4j vs Spring Framework 7 native resilience

Spring Framework 7 (Boot 4) ships `@Retryable` and `@ConcurrencyLimit` in `org.springframework.resilience.annotation`, enabled with `@EnableResilientMethods`.

| Need | Use |
|------|-----|
| Only simple retry or a concurrency cap, no extra dependency | Spring Framework 7 native annotations |
| Circuit breaker, rate limiter, time limiter, metrics/health per instance, YAML-driven tuning | Resilience4j |

Rules:
- **Pick one per method.** Never put Spring `@Retryable` and Resilience4j `@Retry` on the same method: retries multiply (3 × 3 = 9 calls).
- Watch imports: `io.github.resilience4j.retry.annotation.Retry` vs `org.springframework.resilience.annotation.Retryable`.

## Instructions

### 1. Setup and Dependencies

Maven (`pom.xml`). Prefer the BOM so all Resilience4j modules share one version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-bom</artifactId>
            <version>2.4.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot4</artifactId>
    </dependency>
    <!-- Boot 4 renamed spring-boot-starter-aop to spring-boot-starter-aspectj -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aspectj</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
</dependencies>
```

Gradle (`build.gradle.kts`):

```kotlin
implementation(platform("io.github.resilience4j:resilience4j-bom:2.4.0"))
implementation("io.github.resilience4j:resilience4j-spring-boot4")
implementation("org.springframework.boot:spring-boot-starter-aspectj")
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

Notes:
- Do **not** use `resilience4j-spring-boot3` on Boot 4.
- Annotations work through Spring AOP proxies: they only apply to **public methods called from another bean**. Self-invocation (`this.method()`) bypasses them.
- Check Maven Central for a newer 2.x release before pinning.

### 2. Circuit Breaker Pattern

Use `RestClient` (not `RestTemplate`) and records for DTOs:

```java
public record PaymentRequest(String orderId, BigDecimal amount) {}
public record PaymentResponse(String status, String message) {
    static PaymentResponse pending(String message) { return new PaymentResponse("PENDING", message); }
}

@Service
public class PaymentService {

    private final RestClient paymentClient;

    public PaymentService(RestClient.Builder builder) {
        this.paymentClient = builder.baseUrl("http://payment-api").build();
    }

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    public PaymentResponse processPayment(PaymentRequest request) {
        return paymentClient.post()
            .uri("/process")
            .body(request)
            .retrieve()
            .body(PaymentResponse.class);
    }

    private PaymentResponse paymentFallback(PaymentRequest request, CallNotPermittedException ex) {
        return PaymentResponse.pending("Payment service temporarily unavailable");
    }

    private PaymentResponse paymentFallback(PaymentRequest request, Exception ex) {
        return PaymentResponse.pending("Payment could not be processed now");
    }
}
```

Resilience4j picks the fallback whose exception parameter is the **closest match**, so you can separate "circuit open" from "call failed".

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
        ignoreExceptions:
          - org.springframework.web.client.HttpClientErrorException
    instances:
      paymentService:
        baseConfig: default
```

See @references/configuration-reference.md for all circuit breaker options.

### 3. Retry Pattern

```java
@Service
public class ProductService {

    private final RestClient productClient;

    public ProductService(RestClient.Builder builder) {
        this.productClient = builder.baseUrl("http://product-api").build();
    }

    @Retry(name = "productService", fallbackMethod = "getProductFallback")
    public Product getProduct(Long productId) {
        return productClient.get()
            .uri("/products/{id}", productId)
            .retrieve()
            .body(Product.class);
    }

    private Product getProductFallback(Long productId, Exception ex) {
        return Product.unavailable(productId);
    }
}
```

```yaml
resilience4j:
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        enableRandomizedWait: false
        retryExceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
        ignoreExceptions:
          - org.springframework.web.client.HttpClientErrorException
    instances:
      productService:
        baseConfig: default
        maxAttempts: 5
```

Retry settings (attempts, wait, backoff) live in YAML. The `@Retry` annotation only accepts `name` and `fallbackMethod`.

### 4. Rate Limiter Pattern

```java
@Service
public class NotificationService {

    private final EmailClient emailClient;

    public NotificationService(EmailClient emailClient) {
        this.emailClient = emailClient;
    }

    @RateLimiter(name = "notificationService", fallbackMethod = "rateLimitFallback")
    public void sendEmail(EmailRequest request) {
        emailClient.send(request);
    }

    private void rateLimitFallback(EmailRequest request, RequestNotPermitted ex) {
        throw new RateLimitExceededException("Too many requests. Please try again later.");
    }
}
```

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        registerHealthIndicator: true
        limitForPeriod: 10
        limitRefreshPeriod: 1s
        timeoutDuration: 500ms
    instances:
      notificationService:
        baseConfig: default
        limitForPeriod: 5
```

### 5. Bulkhead Pattern

With Java 25 and virtual threads, **prefer `SEMAPHORE`**. It caps concurrent calls without pinning work to a platform thread pool.

```java
@Service
public class ReportService {

    private final ReportGenerator reportGenerator;

    public ReportService(ReportGenerator reportGenerator) {
        this.reportGenerator = reportGenerator;
    }

    @Bulkhead(name = "reportService", type = Bulkhead.Type.SEMAPHORE)
    public Report generateReport(ReportRequest request) {
        return reportGenerator.generate(request);
    }
}
```

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 10
        maxWaitDuration: 100ms
    instances:
      reportService:
        baseConfig: default
        maxConcurrentCalls: 5
```

Use `type = THREADPOOL` only when the method must return `CompletableFuture` and you deliberately want a separate, bounded platform-thread pool (for example, isolating legacy blocking code that pins carriers). It must not be combined with code that expects to run on the caller's virtual thread.

### 6. Time Limiter Pattern

`@TimeLimiter` only applies to methods returning `CompletableFuture` (or reactive types). Supply an explicit virtual-thread executor; do not rely on the common `ForkJoinPool`:

```java
@Configuration
class ExecutorConfig {
    @Bean(destroyMethod = "close")
    ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

@Service
public class SearchService {

    private final SearchEngine searchEngine;
    private final ExecutorService executor;

    public SearchService(SearchEngine searchEngine, ExecutorService virtualThreadExecutor) {
        this.searchEngine = searchEngine;
        this.executor = virtualThreadExecutor;
    }

    @TimeLimiter(name = "searchService", fallbackMethod = "searchFallback")
    public CompletableFuture<SearchResults> search(SearchQuery query) {
        return CompletableFuture.supplyAsync(() -> searchEngine.executeSearch(query), executor);
    }

    private CompletableFuture<SearchResults> searchFallback(SearchQuery query, TimeoutException ex) {
        return CompletableFuture.completedFuture(SearchResults.empty("Search timed out"));
    }
}
```

```yaml
resilience4j:
  timelimiter:
    configs:
      default:
        timeoutDuration: 2s
        cancelRunningFuture: true
    instances:
      searchService:
        baseConfig: default
        timeoutDuration: 3s
```

For **synchronous** calls on virtual threads, prefer setting connect and read timeouts on the HTTP client instead of wrapping the call in a `CompletableFuture` just to use `@TimeLimiter`:

```java
@Bean
RestClientCustomizer timeouts() {
    var factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    return builder -> builder.requestFactory(factory);
}
```

### 7. Combining Multiple Patterns

```java
@Service
public class OrderService {

    private final OrderClient orderClient;

    public OrderService(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Retry(name = "orderService")
    @CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
    @RateLimiter(name = "orderService")
    @Bulkhead(name = "orderService")
    public Order createOrder(OrderRequest request) {
        return orderClient.createOrder(request);
    }

    private Order createOrderFallback(OrderRequest request, Exception ex) {
        return Order.pending(request);
    }
}
```

Default aspect order (outermost first): `Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → method`. The order of annotations in source code does **not** change it. Change it only through `resilience4j.*.<pattern>AspectOrder` properties.

Implications:
- Each retry attempt goes through the circuit breaker, so failed attempts count toward the failure rate.
- Put the fallback on **one** annotation (usually the circuit breaker). Fallbacks on several layers make the behavior hard to reason about.
- Add `CallNotPermittedException` to the retry `ignoreExceptions` so an open circuit is not retried.

### 8. Exception Handling (Problem Details) and Monitoring

Boot 4 supports RFC 9457 `ProblemDetail` natively:

```java
@RestControllerAdvice
public class ResilienceExceptionHandler {

    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service currently unavailable");
        pd.setTitle("SERVICE_UNAVAILABLE");
        return pd;
    }

    @ExceptionHandler(RequestNotPermitted.class)
    ProblemDetail handleRateLimited(RequestNotPermitted ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        pd.setTitle("TOO_MANY_REQUESTS");
        return pd;
    }

    @ExceptionHandler(BulkheadFullException.class)
    ProblemDetail handleBulkheadFull(BulkheadFullException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service at capacity");
        pd.setTitle("CAPACITY_EXCEEDED");
        return pd;
    }
}
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,circuitbreakers,circuitbreakerevents,retries,retryevents,ratelimiters,bulkheads,timelimiters
  endpoint:
    health:
      show-details: when-authorized
  health:
    circuitbreakers:
      enabled: true
    ratelimiters:
      enabled: true
```

Endpoints:
- `GET /actuator/health`: overall health including registered indicators
- `GET /actuator/circuitbreakers`: circuit breaker states
- `GET /actuator/circuitbreakerevents`: recent state transitions and errors
- `GET /actuator/metrics/resilience4j.circuitbreaker.calls`: Micrometer metrics

Do not expose these endpoints publicly in production. Protect them with Spring Security or a separate management port.

### 9. Virtual Threads Checklist (Java 25)

Enable with `spring.threads.virtual.enabled: true`.

- Java 24+ (JEP 491) removed most `synchronized` pinning, so blocking inside Resilience4j-protected calls is cheap on virtual threads.
- SEMAPHORE bulkhead is the main concurrency guard, because virtual threads make "unlimited" concurrency easy. Cap calls to fragile downstreams explicitly.
- Rate limiter `timeoutDuration` blocks the caller. On virtual threads this is cheap, but keep it short to avoid queueing.
- Retry `waitDuration` sleeps the current thread. That is fine on virtual threads; avoid long waits on platform threads.
- Do not pool virtual threads, and do not set a `THREADPOOL` bulkhead expecting virtual threads inside it.

### Testing and Verification Workflow

1. **Circuit Breaker**: force failures, check `GET /actuator/circuitbreakers` shows `OPEN`, wait `waitDurationInOpenState`, then verify `HALF_OPEN` and finally `CLOSED`.
2. **Retry**: invoke the endpoint and verify the metric `resilience4j.retry.calls{kind="successful_with_retry"}` increases.
3. **Rate Limiter**: exceed `limitForPeriod`, then verify a 429 response with a Problem Details body.
4. **Bulkhead**: send concurrent requests above `maxConcurrentCalls` and verify `BulkheadFullException` (503).
5. **Time Limiter**: stub a delay beyond `timeoutDuration` and verify the fallback runs.

In tests use `@MockitoBean` (Boot 4 removed `@MockBean`) and Awaitility instead of `Thread.sleep`. Reset registries between tests (`circuitBreakerRegistry.circuitBreaker("x").reset()`).

See @references/testing-patterns.md for unit and integration testing strategies.

## Best Practices

- **Provide fallback methods**: degrade gracefully with meaningful responses
- **Use exponential backoff**: avoid overwhelming recovering services (`exponentialBackoffMultiplier: 2`)
- **Set thresholds from data**: start with `failureRateThreshold` between 50 and 70%, then tune with metrics
- **Use constructor injection**: no field injection, no Lombok
- **Enable health indicators**: `registerHealthIndicator: true`
- **Retry only transient errors**: timeouts, connection errors and 5xx; skip 4xx, business exceptions and `CallNotPermittedException`
- **Keep config in YAML**: tune per environment without redeploying code
- **Document fallback behavior**: make fallback logic clear and predictable

## Constraints and Warnings

- A fallback method must have the same parameters as the protected method, plus one exception parameter, and the same return type
- Annotations only apply to public methods called through the Spring proxy (no self-invocation)
- Retried operations must be idempotent. Be careful with `@Retry` on POST or payment calls: use idempotency keys
- Circuit breaker state lives in each JVM instance; replicas do not share it
- Do not mix Spring Framework 7 `@Retryable` with Resilience4j `@Retry` on the same method
- `@TimeLimiter` does not interrupt synchronous code; use client timeouts instead
- `THREADPOOL` bulkheads use platform threads and memory. Prefer `SEMAPHORE` with virtual threads

## Examples

### Before → After: Circuit Breaker

```java
// BEFORE: no protection
public PaymentResponse processPayment(PaymentRequest request) {
    return paymentClient.post().uri("/process").body(request).retrieve().body(PaymentResponse.class);
}

// AFTER: circuit breaker with fallback
@CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
public PaymentResponse processPayment(PaymentRequest request) {
    return paymentClient.post().uri("/process").body(request).retrieve().body(PaymentResponse.class);
}
private PaymentResponse paymentFallback(PaymentRequest request, Exception ex) {
    return PaymentResponse.pending("Service temporarily unavailable");
}
```

### Before → After: Retry with Backoff

```java
// BEFORE: single attempt against a remote API
public Order getOrder(Long orderId) {
    return orderClient.get().uri("/orders/{id}", orderId).retrieve().body(Order.class);
}

// AFTER: retry configured in YAML (resilience4j.retry.instances.orderService)
@Retry(name = "orderService", fallbackMethod = "getOrderFallback")
public Order getOrder(Long orderId) {
    return orderClient.get().uri("/orders/{id}", orderId).retrieve().body(Order.class);
}
private Order getOrderFallback(Long orderId, Exception ex) {
    return orderCache.get(orderId).orElseThrow(() -> new OrderUnavailableException(orderId, ex));
}
```

Do not retry local repository calls such as `findById(...).orElseThrow(NotFound)`. A "not found" result is not transient.

### Before → After: Rate Limiting on a Controller

```java
// BEFORE: unbounded requests
@GetMapping("/api/data")
public Data fetchData() { return dataService.process(); }

// AFTER: rate limited; RequestNotPermitted is mapped to 429 by ResilienceExceptionHandler
@RateLimiter(name = "dataService")
@GetMapping("/api/data")
public Data fetchData() { return dataService.process(); }
```

**See also:** [Configuration Reference](references/configuration-reference.md) · [Testing Patterns](references/testing-patterns.md) · [Examples](references/examples.md) · [Resilience4j Docs](https://resilience4j.readme.io/)

> The files in `references/` come from the original Boot 3 version. When you use them, apply the Boot 4 adjustments above: `@MockitoBean`, `RestClient`, records, `spring-boot-starter-aspectj` and `resilience4j-spring-boot4`.
