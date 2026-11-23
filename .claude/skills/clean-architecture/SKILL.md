---
name: clean-architecture
description: Implementation patterns for Clean Architecture, Hexagonal Architecture (Ports & Adapters) and Domain-Driven Design tactical patterns in Java 25 / Spring Boot 4.x applications. Use when structuring layered or hexagonal architectures, deciding where code belongs, separating domain logic from frameworks, implementing ports and adapters, creating entities/value objects/aggregates, enforcing the dependency rule with ArchUnit, or refactoring a monolith for testability. For the theory (dependency rule, boundaries, SOLID, component principles) see clean-architecture-principles.
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

<!--
  Adapted from giuseppe-trisciuoglio/developer-kit@clean-architecture (MIT, Java 21 / Boot 3.5 version).
  Changes in this copy: Java 25 / Spring Boot 4, no Lombok (explicit constructors, per the project's
  conventions skill), Problem Details (RFC 9457) errors, external calls kept out of database transactions,
  sealed domain events, consistent port placement, a working ArchUnit test, corrected test examples,
  Boot 4 test packages and @MockitoBean.
-->

# Clean Architecture, Hexagonal Architecture & DDD for Spring Boot 4 / Java 25

## Overview

Guidance for implementing Clean Architecture, Hexagonal Architecture (Ports & Adapters) and DDD tactical patterns in Java 25 / Spring Boot 4 applications: framework-independent domain logic, explicit ports, swappable adapters and fast tests.

These rules work together with the project's `conventions` skill: no Lombok, constructor injection, records for DTOs, `RestClient`, Problem Details, Flyway.

## When to Use

- Architecting a new Spring Boot service with clear separation of concerns
- Refactoring tightly coupled code into testable layers
- Keeping business rules independent of frameworks, databases and external APIs
- Designing ports and adapters for swappable implementations
- Applying DDD tactical patterns (entities, value objects, aggregates, domain events)
- Writing business-logic tests without a Spring context

## Instructions

### 1. Core Concepts

#### Clean Architecture and Hexagonal are the same idea

Both say: **source-code dependencies point inward, toward the business rules.** The table maps the two vocabularies:

| Ring (Clean) | Hexagonal term | Contains | Spring allowed? |
|---|---|---|---|
| Entities | Domain core | Entities, value objects, domain events, domain services, domain exceptions | **No** |
| Use cases | Application core + ports | Use case interfaces (driving ports), outbound port interfaces (driven ports), use case implementations, commands/results | Only `@Transactional` and stereotype annotations, or none if wired with `@Bean` |
| Interface adapters | Adapters | REST controllers (driving), JPA/HTTP/messaging adapters (driven), mappers, request/response DTOs | Yes |
| Frameworks & drivers | Configuration | Spring Boot app, `@Configuration`, beans wiring | Yes |

#### Ports

- **Driving (inbound) port**: an interface the outside world calls, such as `CreateOrderUseCase`. Lives in `application/port/in`.
- **Driven (outbound) port**: an interface the application needs, implemented outside, such as `LoadOrderPort`, `SaveOrderPort` or `PaymentPort`. Lives in `application/port/out`.

**Placement rule used in this project:** persistence interfaces are driven ports too, so they live in `application/port/out`, not in `domain/`. The domain stays pure model and rules. Pick one convention per codebase and enforce it with ArchUnit.

#### DDD tactical patterns

- **Entity**: has identity and lifecycle (`Order`, `Customer`)
- **Value object**: immutable and defined by its attributes, usually a `record` (`Money`, `Email`, `OrderId`)
- **Aggregate**: consistency boundary with a root entity. Reference other aggregates **by ID only**
- **Domain event**: a fact that happened (`OrderPlaced`), modeled as a `sealed interface` plus records
- **Domain service**: a rule that does not belong to a single entity; still framework-free

### 2. Package Structure (feature-first)

Organize by business capability first, then by layer inside each feature:

```
com.example.order/
├── domain/
│   ├── model/            # Order, OrderItem, OrderId, Money (entities and value objects)
│   ├── event/            # sealed OrderEvent + records
│   ├── service/          # domain services (pure Java)
│   └── exception/        # OrderDomainException, ...
├── application/
│   ├── port/in/          # CreateOrderUseCase, GetOrderQuery
│   ├── port/out/         # LoadOrderPort, SaveOrderPort, PaymentPort, OrderEventPort
│   ├── service/          # use case implementations (CreateOrderService)
│   └── command/          # CreateOrderCommand, OrderResult (records; no web annotations)
├── adapter/
│   ├── in/web/           # OrderController, OrderCreateRequest, OrderResponse, web mapper
│   └── out/
│       ├── persistence/  # OrderJpaEntity, OrderJpaRepository, OrderPersistenceAdapter, mapper
│       └── payment/      # PaymentRestClientAdapter (RestClient)
└── config/               # OrderBeansConfig (optional explicit wiring)
```

Notes:
- DTO names follow the `conventions` skill: `<Resource>CreateRequest`, `<Resource>UpdateRequest`, `<Resource>Response`. They live in `adapter/in/web`, not in `application`.
- Bean Validation annotations (`@NotNull`, `@Valid`) belong on web DTOs, not on application commands. Commands validate their invariants in the compact constructor.

### 3. Domain Layer (framework-free)

- No `org.springframework.*`, `jakarta.persistence.*` or `tools.jackson.*` imports
- Records for value objects and strongly-typed IDs, with validation in the compact constructor
- Business logic in entities (rich model). Services orchestrate; entities decide
- Factory methods (`Order.place(...)`) enforce invariants; `reconstitute(...)` rebuilds from persistence
- Domain events as a `sealed interface` with `record` implementations, so `switch` over them is exhaustive

### 4. Application Layer (use cases)

- One interface per use case in `port/in`, implemented by a class in `service/`
- Depends only on `domain` and `port/out` interfaces
- Transaction boundary is here (`@Transactional`). **No slow external calls inside a database transaction** (see Example 4)
- Returns application results (records), never JPA entities

### 5. Driven Adapters (persistence, HTTP, messaging)

- JPA entities only in `adapter/out/persistence`. Never leak them outward
- An adapter implements one or more `port/out` interfaces and maps JPA ↔ domain (manual mapper or MapStruct)
- HTTP clients use `RestClient` (or HTTP interface clients) and translate remote errors into application exceptions
- Resilience (circuit breaker, retry) goes on the adapter, not on the use case. See `spring-boot-resilience4j`

### 6. Driving Adapters (REST)

- Controllers depend on `port/in` interfaces, never on service classes or repositories
- Map web DTO → command → use case → result → response DTO
- Errors are mapped to Problem Details in a `@RestControllerAdvice`

### 7. Best Practices

1. **Dependency rule**: domain ← application ← adapters/config. Never the reverse
2. **Immutable value objects**: records with validation
3. **Rich domain model**: no anemic entities with only getters and setters
4. **Ports as interfaces in the application layer**, implemented by adapters
5. **Domain events** to decouple side effects (email, integration) from the main operation
6. **Constructor injection, no Lombok**
7. **Separate models**: web DTO ≠ command ≠ domain entity ≠ JPA entity
8. **Transactions in application services only**, and never around remote calls
9. **IDs between aggregates**, not object references
10. **Enforce the rules with ArchUnit**, not only code review

### 8. Validate Architecture Compliance (ArchUnit)

Add `com.tngtech.archunit:archunit-junit5` (test scope) and keep this test in every service:

```java
@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "tools.jackson..", "..adapter..", "..application..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule controllersUseInboundPortsOnly = noClasses()
        .that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAnyPackage("..application.service..", "..adapter.out..");

    @ArchTest
    static final ArchRule jpaEntitiesStayInPersistence = classes()
        .that().areAnnotatedWith(jakarta.persistence.Entity.class)
        .should().resideInAPackage("..adapter.out.persistence..");

    @ArchTest
    static final ArchRule hexagonalLayers = layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("Domain").definedBy("..domain..")
        .layer("Application").definedBy("..application..")
        .layer("Adapters").definedBy("..adapter..")
        .layer("Config").definedBy("..config..")
        .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");
}
```

### 9. Tests per Layer

| Layer | Test type | Tooling |
|---|---|---|
| Domain | Pure unit tests, milliseconds | JUnit 5 + AssertJ, no Spring, no mocks |
| Application | Unit tests with fakes or mocks of `port/out` | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) |
| Persistence adapter | Slice test against a real database | `@DataJpaTest` + Testcontainers `@ServiceConnection` |
| REST adapter | Slice test | `@WebMvcTest` + `@MockitoBean` for inbound ports |
| Architecture | Rules | ArchUnit |

Boot 4 note: test slice annotations moved to module-specific packages, for example `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` and `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`. `@MockBean` no longer exists; use `@MockitoBean`.

## Examples

### Example 1: Domain Entity (aggregate root) with Domain Events

```java
// domain/model/Order.java
public final class Order {

    private final OrderId id;
    private final CustomerId customerId;
    private final List<OrderItem> items;
    private OrderStatus status;
    private final List<OrderEvent> pendingEvents = new ArrayList<>();

    private Order(OrderId id, CustomerId customerId, List<OrderItem> items, OrderStatus status) {
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        this.items = List.copyOf(items);
        this.status = Objects.requireNonNull(status);
    }

    public static Order place(CustomerId customerId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new OrderDomainException("An order needs at least one item");
        }
        var order = new Order(OrderId.generate(), customerId, items, OrderStatus.PENDING);
        order.pendingEvents.add(new OrderEvent.Placed(order.id, order.total(), Instant.now()));
        return order;
    }

    public static Order reconstitute(OrderId id, CustomerId customerId, List<OrderItem> items, OrderStatus status) {
        return new Order(id, customerId, items, status);
    }

    public void confirm() {
        if (status != OrderStatus.PENDING) {
            throw new OrderDomainException("Only pending orders can be confirmed");
        }
        status = OrderStatus.CONFIRMED;
        pendingEvents.add(new OrderEvent.Confirmed(id, Instant.now()));
    }

    public Money total() {
        return items.stream().map(OrderItem::subtotal).reduce(Money.zero(Currency.getInstance("BRL")), Money::add);
    }

    public List<OrderEvent> pullEvents() {
        var events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public OrderId id() { return id; }
    public CustomerId customerId() { return customerId; }
    public List<OrderItem> items() { return items; }
    public OrderStatus status() { return status; }
}
```

```java
// domain/event/OrderEvent.java
public sealed interface OrderEvent {
    OrderId orderId();
    Instant occurredAt();

    record Placed(OrderId orderId, Money total, Instant occurredAt) implements OrderEvent {}
    record Confirmed(OrderId orderId, Instant occurredAt) implements OrderEvent {}
}
```

### Example 2: Value Objects and Strongly-Typed IDs

```java
// domain/model/Money.java
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new OrderDomainException("Amount cannot be negative");
        }
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        if (!currency.equals(other.currency)) {
            throw new OrderDomainException("Currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }
}

// domain/model/OrderId.java
public record OrderId(UUID value) {
    public OrderId { Objects.requireNonNull(value, "OrderId"); }
    public static OrderId generate() { return new OrderId(UUID.randomUUID()); }
}
```

### Example 3: Ports

```java
// application/port/in/CreateOrderUseCase.java
public interface CreateOrderUseCase {
    OrderResult create(CreateOrderCommand command);
}

// application/command/CreateOrderCommand.java
public record CreateOrderCommand(CustomerId customerId, List<OrderLine> lines) {
    public CreateOrderCommand {
        Objects.requireNonNull(customerId, "customerId");
        lines = List.copyOf(lines);
    }
    public record OrderLine(ProductId productId, int quantity, Money unitPrice) {}
}

// application/port/out/SaveOrderPort.java
public interface SaveOrderPort {
    Order save(Order order);
}

// application/port/out/LoadOrderPort.java
public interface LoadOrderPort {
    Optional<Order> findById(OrderId id);
}

// application/port/out/PaymentPort.java
public interface PaymentPort {
    PaymentResult charge(OrderId orderId, Money amount);
}

// application/port/out/OrderEventPort.java
public interface OrderEventPort {
    void publish(OrderEvent event);
}
```

### Example 4: Use Case Implementation (no Lombok, no remote call inside the DB transaction)

The original example charged the payment inside `@Transactional`. That holds a database connection while waiting on a remote service, and a rollback cannot undo a charge that succeeded. Split the flow instead:

```java
// application/service/CreateOrderService.java
@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final SaveOrderPort saveOrderPort;
    private final OrderEventPort orderEventPort;

    public CreateOrderService(SaveOrderPort saveOrderPort, OrderEventPort orderEventPort) {
        this.saveOrderPort = saveOrderPort;
        this.orderEventPort = orderEventPort;
    }

    @Override
    @Transactional
    public OrderResult create(CreateOrderCommand command) {
        var items = command.lines().stream()
            .map(l -> new OrderItem(l.productId(), l.quantity(), l.unitPrice()))
            .toList();

        var order = Order.place(command.customerId(), items);
        var saved = saveOrderPort.save(order);

        // Events are delivered after commit (see the @TransactionalEventListener in the adapter),
        // or written to an outbox table in the same transaction.
        order.pullEvents().forEach(orderEventPort::publish);

        return OrderResult.from(saved);
    }
}
```

```java
// application/service/ChargeOrderOnPlaced.java: runs after the order is committed
@Component
public class ChargeOrderOnPlaced {

    private final PaymentPort paymentPort;
    private final ConfirmOrderUseCase confirmOrder;

    public ChargeOrderOnPlaced(PaymentPort paymentPort, ConfirmOrderUseCase confirmOrder) {
        this.paymentPort = paymentPort;
        this.confirmOrder = confirmOrder;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderEvent.Placed event) {
        var result = paymentPort.charge(event.orderId(), event.total()); // remote call, no open DB transaction
        confirmOrder.apply(event.orderId(), result);                     // new short transaction
    }
}
```

For guaranteed delivery across crashes, use the Outbox pattern (see `design-patterns`) instead of in-memory events.

If you want the application layer to contain no Spring annotations at all, drop `@Service` and `@Transactional` and wire the classes with `@Bean` in `config/`. Apply transactions with a `TransactionTemplate` or a decorator there. Both styles are valid; choose one per codebase.

### Example 5: Persistence Adapter (driven)

```java
// adapter/out/persistence/OrderJpaEntity.java
@Entity
@Table(name = "orders")
public class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    protected OrderJpaEntity() {} // required by JPA

    // explicit constructor, getters and setters (no Lombok)
}

// adapter/out/persistence/OrderPersistenceAdapter.java
@Component
class OrderPersistenceAdapter implements SaveOrderPort, LoadOrderPort {

    private final OrderJpaRepository repository;
    private final OrderPersistenceMapper mapper;

    OrderPersistenceAdapter(OrderJpaRepository repository, OrderPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Order save(Order order) {
        return mapper.toDomain(repository.save(mapper.toEntity(order)));
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return repository.findById(id.value()).map(mapper::toDomain);
    }
}
```

Schema changes go through Flyway migrations (`db/migration/V<n>__*.sql`), never `ddl-auto: update`.

### Example 6: Driving Adapter (REST)

```java
// adapter/in/web/OrderController.java
@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final CreateOrderUseCase createOrder;
    private final OrderWebMapper mapper;

    OrderController(CreateOrderUseCase createOrder, OrderWebMapper mapper) {
        this.createOrder = createOrder;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
        var result = createOrder.create(mapper.toCommand(request));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(result));
    }
}

// adapter/in/web/OrderCreateRequest.java
public record OrderCreateRequest(
    @NotNull UUID customerId,
    @NotEmpty List<@Valid Line> items
) {
    public record Line(@NotNull UUID productId, @Min(1) int quantity) {}
}

// adapter/in/web/OrderExceptionHandler.java
@RestControllerAdvice
class OrderExceptionHandler {

    @ExceptionHandler(OrderDomainException.class)
    ProblemDetail handleDomain(OrderDomainException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setTitle("Business rule violated");
        return pd;
    }
}
```

### Example 7: Domain Test (no Spring)

```java
class OrderTest {

    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());

    @Test
    void placesPendingOrderAndRaisesEvent() {
        var items = List.of(new OrderItem(new ProductId(UUID.randomUUID()), 2, Money.of("10.00", "BRL")));

        var order = Order.place(CUSTOMER, items);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.total()).isEqualTo(Money.of("20.00", "BRL"));
        assertThat(order.pullEvents()).singleElement().isInstanceOf(OrderEvent.Placed.class);
    }

    @Test
    void rejectsEmptyOrder() {
        assertThatThrownBy(() -> Order.place(CUSTOMER, List.of()))
            .isInstanceOf(OrderDomainException.class);
    }
}
```

### Example 8: Use Case Test (fakes for driven ports)

```java
class CreateOrderServiceTest {

    private final InMemoryOrderStore store = new InMemoryOrderStore();   // implements SaveOrderPort, LoadOrderPort
    private final List<OrderEvent> published = new ArrayList<>();
    private final CreateOrderService service = new CreateOrderService(store, published::add);

    @Test
    void savesOrderAndPublishesPlacedEvent() {
        var command = new CreateOrderCommand(new CustomerId(UUID.randomUUID()),
            List.of(new CreateOrderCommand.OrderLine(new ProductId(UUID.randomUUID()), 1, Money.of("50.00", "BRL"))));

        var result = service.create(command);

        assertThat(store.findById(result.id())).isPresent();
        assertThat(published).singleElement().isInstanceOf(OrderEvent.Placed.class);
    }
}
```

A hand-written fake (or a lambda, since ports are functional interfaces) keeps the test independent from Spring and Mockito. Use Mockito when you only need to verify an interaction.

## Constraints and Warnings

### Critical constraints

- **Domain purity**: no `@Entity`, `@Component`, `@Autowired`, `@JsonProperty` or Spring types in `domain`
- **Dependency direction**: domain ← application ← adapters/config only
- **No entity leakage**: JPA entities never leave `adapter/out/persistence`; domain entities never go into API responses
- **No Lombok** (project convention)

### Common pitfalls

- **Anemic domain model**: all logic in services. Move rules into entities and value objects
- **Framework leakage**: JPA or Jackson annotations on domain classes
- **Remote calls inside `@Transactional`**: use after-commit events, the outbox pattern or a saga
- **One giant "service" per aggregate**: prefer one use case per class
- **Repositories returning JPA entities to the application layer**: always map
- **Cross-aggregate object references**: reference by ID
- **Over-engineering small CRUD**: for a trivial module a simple layered design is fine. Apply full hexagonal where the business rules justify it

### Performance

- Map once per boundary; avoid loading full aggregates for read-only lists. Use dedicated query ports or projections (light CQRS)
- Use `@Transactional(readOnly = true)` for query use cases
- Watch N+1 queries in persistence adapters (see `jpa-patterns`)

## References

- `references/java-clean-architecture.md`: Java patterns (records, sealed types, strongly-typed IDs, Result type, specifications), with Java 25 notes
- `references/spring-boot-implementation.md`: Spring Boot 4 wiring, JPA mapping, transactions, events, validation, Problem Details, tests
- Related skills: `clean-architecture-principles` (theory), `design-patterns` (Outbox, Saga, CQRS), `spring-boot-resilience4j` (resilient driven adapters), `conventions`, `testing-patterns`, `jpa-patterns`
