# Spring Boot 4 Implementation Guide

Patterns for wiring Clean / Hexagonal Architecture with Spring Boot 4 and Java 25.

Project conventions apply: no Lombok, constructor injection, records for DTOs, `RestClient`, Problem Details, Flyway.

## Wiring Options

### Option A: stereotype annotations (simplest)

Annotate use case implementations with `@Service` and adapters with `@Component`. Spring Boot's component scan finds them from the `@SpringBootApplication` package. Do **not** also declare them with `@Bean`, or you get two beans.

### Option B: explicit wiring (application layer free of Spring)

Leave the use case classes unannotated and wire them in `config/`:

```java
@Configuration(proxyBeanMethods = false)
class OrderBeansConfig {

    @Bean
    CreateOrderUseCase createOrderUseCase(SaveOrderPort saveOrderPort,
                                          OrderEventPort orderEventPort,
                                          PlatformTransactionManager txManager) {
        var target = new CreateOrderService(saveOrderPort, orderEventPort);
        var tx = new TransactionTemplate(txManager);
        return command -> tx.execute(status -> target.create(command));
    }
}
```

Choose one option per codebase and document it.

## Dependency Injection

### Constructor injection (no Lombok)

```java
@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final SaveOrderPort saveOrderPort;
    private final OrderEventPort orderEventPort;

    public CreateOrderService(SaveOrderPort saveOrderPort, OrderEventPort orderEventPort) {
        this.saveOrderPort = saveOrderPort;
        this.orderEventPort = orderEventPort;
    }
}
```

A single constructor needs no `@Autowired`.

### Multiple adapters for one port

```java
public interface NotificationPort {
    void send(Notification notification);
}

@Component
@Primary
class EmailNotificationAdapter implements NotificationPort { /* JavaMailSender */ }

@Component
@Qualifier("sms")
class SmsNotificationAdapter implements NotificationPort { /* SmsClient */ }

@Service
public class OrderConfirmationService {

    private final NotificationPort email;
    private final NotificationPort sms;

    public OrderConfirmationService(NotificationPort email, @Qualifier("sms") NotificationPort sms) {
        this.email = email;
        this.sms = sms;
    }
}
```

Put `@Qualifier` on the constructor parameter. With Lombok this was a common bug, because the qualifier on the field was not copied to the generated constructor.

### Conditional adapters

```java
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "acme")
class AcmePaymentAdapter implements PaymentPort { /* ... */ }

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake", matchIfMissing = false)
class FakePaymentAdapter implements PaymentPort { /* local / test use */ }
```

## Driven HTTP Adapter with RestClient

```java
@Component
class PaymentRestClientAdapter implements PaymentPort {

    private final RestClient client;

    PaymentRestClientAdapter(RestClient.Builder builder, PaymentProperties props) {
        this.client = builder.baseUrl(props.baseUrl()).build();
    }

    @Override
    @CircuitBreaker(name = "payment", fallbackMethod = "unavailable")
    public PaymentResult charge(OrderId orderId, Money amount) {
        var response = client.post()
            .uri("/charges")
            .header("Idempotency-Key", orderId.value().toString())
            .body(new ChargeRequest(amount.amount(), amount.currency().getCurrencyCode()))
            .retrieve()
            .body(ChargeResponse.class);
        return new PaymentResult(response.approved(), response.transactionId());
    }

    private PaymentResult unavailable(OrderId orderId, Money amount, Exception ex) {
        return PaymentResult.pending();
    }

    record ChargeRequest(BigDecimal amount, String currency) {}
    record ChargeResponse(boolean approved, String transactionId) {}
}

@ConfigurationProperties("payment")
record PaymentProperties(URI baseUrl) {}
```

Remote DTOs (`ChargeRequest`, `ChargeResponse`) stay private to the adapter. The port speaks domain types.

## JPA Mapping

### Separate domain and JPA models

```java
// domain/model/Order.java: no annotations (see SKILL.md Example 1)

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderJpaEntity() {}

    // explicit getters/setters
}
```

If the domain enum `OrderStatus` is reused in the JPA entity, that is an allowed dependency (adapter → domain). Never the reverse.

### Manual mapper (no extra dependency)

```java
@Component
class OrderPersistenceMapper {

    OrderJpaEntity toEntity(Order order) {
        var entity = new OrderJpaEntity();
        entity.setId(order.id().value());
        entity.setCustomerId(order.customerId().value());
        entity.setStatus(order.status());
        order.items().forEach(i -> entity.addItem(OrderItemJpaEntity.from(i)));
        return entity;
    }

    Order toDomain(OrderJpaEntity e) {
        var items = e.getItems().stream().map(OrderItemJpaEntity::toDomain).toList();
        return Order.reconstitute(new OrderId(e.getId()), new CustomerId(e.getCustomerId()), items, e.getStatus());
    }
}
```

MapStruct is fine too (`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`). Use `reconstitute(...)` through an `@ObjectFactory` so invariants are not bypassed with setters.

## Transaction Management

### Rules

- `@Transactional` goes on application services (use cases), never on domain classes or controllers
- **No remote calls (HTTP, payment, e-mail) inside a database transaction.** They hold a connection, and a rollback cannot undo them
- Use `readOnly = true` for query use cases

### Safe flow: persist, then react after commit

```java
@Service
public class CreateOrderService implements CreateOrderUseCase {

    // constructor omitted

    @Override
    @Transactional
    public OrderResult create(CreateOrderCommand command) {
        var order = Order.place(command.customerId(), toItems(command));
        var saved = saveOrderPort.save(order);
        order.pullEvents().forEach(orderEventPort::publish);   // delivered AFTER_COMMIT
        return OrderResult.from(saved);
    }
}

@Service
@Transactional(readOnly = true)
public class GetOrderService implements GetOrderQuery {

    private final LoadOrderPort loadOrderPort;

    public GetOrderService(LoadOrderPort loadOrderPort) {
        this.loadOrderPort = loadOrderPort;
    }

    @Override
    public OrderResult get(OrderId id) {
        return loadOrderPort.findById(id).map(OrderResult::from)
            .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
```

## Domain Event Publishing

```java
// adapter/out/event/SpringOrderEventAdapter.java
@Component
class SpringOrderEventAdapter implements OrderEventPort {

    private final ApplicationEventPublisher publisher;

    SpringOrderEventAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OrderEvent event) {
        publisher.publishEvent(event);
    }
}

// a listener that must only run if the order was committed
@Component
class OrderNotificationListener {

    private final NotificationPort notifications;

    OrderNotificationListener(NotificationPort notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(OrderEvent.Confirmed event) {
        notifications.send(Notification.orderConfirmed(event.orderId()));
    }
}
```

In-memory events are lost if the JVM dies between commit and listener. When delivery must be guaranteed, write events to an **outbox** table in the same transaction and publish them asynchronously.

## Validation

Bean Validation belongs on **web DTOs** in `adapter/in/web`. Invariants belong in the domain and command constructors.

```java
public record OrderCreateRequest(
    @NotNull UUID customerId,
    @NotEmpty List<@Valid Line> items,
    @Valid ShippingAddressRequest shippingAddress
) {
    public record Line(@NotNull UUID productId, @Min(1) @Max(100) int quantity) {}
}
```

Custom constraint validators do not need `@Component`. Spring creates them through its `ConstraintValidatorFactory`, and they can still use constructor injection.

## Exception Handling with Problem Details

Enable `spring.mvc.problemdetails.enabled: true` and map application and domain exceptions:

```java
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderDomainException.class)
    ProblemDetail handleDomain(OrderDomainException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setTitle("Business rule violated");
        pd.setType(URI.create("https://errors.example.com/business-rule"));
        return pd;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
}
```

Validation errors (`MethodArgumentNotValidException`) are already turned into Problem Details by `ResponseEntityExceptionHandler`. The catch-all never returns the exception message.

## Testing

Boot 4 moved test slices to module-specific packages, for example:
- `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
- `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
- `org.springframework.test.context.bean.override.mockito.MockitoBean` (replaces `@MockBean`)

Check the imports against the Boot version you use; the IDE will suggest the right package.

### Persistence adapter slice test with Testcontainers

```java
@DataJpaTest
@Import({OrderPersistenceAdapter.class, OrderPersistenceMapper.class})
@Testcontainers
class OrderPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    OrderPersistenceAdapter adapter;

    @Test
    void savesAndLoadsOrder() {
        var order = Order.place(new CustomerId(UUID.randomUUID()), sampleItems());

        adapter.save(order);

        assertThat(adapter.findById(order.id()))
            .get().extracting(Order::status).isEqualTo(OrderStatus.PENDING);
    }
}
```

`@ServiceConnection` replaces the manual `@DynamicPropertySource` block. With Testcontainers 2.x, `PostgreSQLContainer` is in `org.testcontainers.postgresql` and is no longer generic. Flyway migrations run automatically in the slice.

### Controller slice test

```java
@WebMvcTest(OrderController.class)
@Import({OrderWebMapper.class, GlobalExceptionHandler.class})
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CreateOrderUseCase createOrder;

    @Test
    void createsOrder() throws Exception {
        var id = UUID.randomUUID();
        when(createOrder.create(any())).thenReturn(new OrderResult(new OrderId(id), OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "customerId": "550e8400-e29b-41d4-a716-446655440000",
                      "items": [{"productId": "550e8400-e29b-41d4-a716-446655440001", "quantity": 2}]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", endsWith("/api/orders/" + id)))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsInvalidRequestWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
```

## OpenAPI Documentation

```java
@Tag(name = "Orders", description = "Order management endpoints")
@RestController
@RequestMapping("/api/orders")
class OrderController {

    // constructor injection as in SKILL.md Example 6

    @Operation(summary = "Create a new order")
    @ApiResponse(responseCode = "201", description = "Order created")
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "422", description = "Business rule violated", content = @Content(mediaType = "application/problem+json"))
    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
        // ...
    }
}
```

OpenAPI annotations belong only in `adapter/in/web`.

## Environment-Specific Adapters

```java
@Component
@Profile("!test")
class SmtpEmailAdapter implements NotificationPort { /* real SMTP */ }

@Component
@Profile("test")
class InMemoryNotificationAdapter implements NotificationPort {

    private final List<Notification> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(Notification notification) {
        sent.add(notification);
    }

    List<Notification> sent() {
        return List.copyOf(sent);
    }
}
```

Swapping an adapter without touching the use case is the main payoff of the ports and adapters design.
