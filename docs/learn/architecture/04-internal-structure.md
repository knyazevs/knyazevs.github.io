# Внутренняя структура сервиса: hexagonal, clean, ports & adapters

Когда сервис уже выделен и его границы понятны (см. главы 01-03), встаёт вопрос
**внутреннего устройства**. Где хранить бизнес-логику, как изолировать её от БД и
HTTP, как тестировать без поднятия инфраструктуры. Hexagonal architecture (она же
ports & adapters) и Clean architecture — два самых распространённых ответа,
по сути об одном и том же.

## Зачем отделять домен от инфраструктуры

### Базовая проблема

Типичный сервис без изоляции домена:

```
class OrderController {
    val db = DatabaseConnection.get()
    val emailClient = SendGridClient(apiKey)
    val paymentGateway = StripeClient(apiKey)
    
    fun handlePost(request: HttpRequest): HttpResponse {
        // SQL-запрос, бизнес-логика, отправка email, вызов Stripe — всё в куче
    }
}
```

Что плохого:

- **Тестировать невозможно** без БД, SendGrid, Stripe — то есть unit-тестов нет, только
  интеграционные с external services.
- **Бизнес-логика спрятана** среди технических вызовов. Невозможно прочитать «что
  делает оформление заказа» — нужно отделять зерна от плёвел.
- **Замена технологии — переписать всё.** Перешли с Stripe на Adyen, или с
  PostgreSQL на DynamoDB — изменения по всему коду.
- **Параллельная работа невозможна.** Бизнес-логика и интеграции — один файл,
  два разработчика блокируют друг друга.

### Идея отделения

Положить **бизнес-логику в центре**, технические детали (БД, HTTP, внешние API)
— по краям. Бизнес-логика **не знает** про конкретные технологии — она работает с
абстракциями (интерфейсами).

Hexagonal architecture, Clean architecture, Onion architecture — все говорят про
одно и то же, разными словами:

- В центре — **домен**: модели предметной области (Aggregate, Entity, Value
  Object из главы 03), бизнес-правила, чистая логика без технологий.
- Вокруг — **слой сервисов** (use cases, application services): оркестрация
  бизнес-операций, использует домен и абстрактные интерфейсы.
- По краям — **адаптеры**: реализация интерфейсов через конкретные технологии
  (БД, HTTP, внешние API). Адаптеры зависят от центра, не наоборот.

Главный принцип: **зависимости направлены внутрь**. Адаптер знает про домен;
домен не знает про адаптер.

### На практике

Что это даёт:

- **Unit-тесты бизнес-логики** без БД — заменяешь адаптер БД на in-memory моку,
  проверяешь логику.
- **Замена технологии** — переписать один адаптер, не трогая бизнес-логику.
- **Чтение кода** — открыл доменный модуль, видишь бизнес-логику без шума.
- **Параллельная работа** — один разработчик пишет домен, другой — адаптеры.

## Ports & adapters в коде

### Структура

В коде это выглядит примерно так (псевдокод):

```
domain/
  Order.kt               # Aggregate, бизнес-правила
  OrderRepository.kt     # ИНТЕРФЕЙС хранения, не реализация
  PaymentService.kt      # ИНТЕРФЕЙС платежей
  
application/
  CheckoutUseCase.kt     # use case: оформление заказа
                         # использует Order, OrderRepository, PaymentService

infrastructure/
  PostgresOrderRepository.kt   # АДАПТЕР: реализация OrderRepository через PG
  StripePaymentService.kt      # АДАПТЕР: реализация PaymentService через Stripe
  HttpController.kt            # АДАПТЕР: вход через HTTP
```

Принципы:

1. **`domain/`** не импортирует ничего из `application/` или `infrastructure/`.
   Только стандартная библиотека.
2. **`application/`** импортирует `domain/`, но не `infrastructure/`. Работает
   только с интерфейсами.
3. **`infrastructure/`** импортирует и `domain/`, и `application/` — реализует
   их интерфейсы.

Если зависимости направлены правильно, нельзя случайно вкатить SQL в Order
Aggregate. Компилятор не позволит.

### Порты и адаптеры — терминология

- **Порт** (port) — интерфейс на границе ядра. `OrderRepository`, `PaymentService`
  — это порты.
- **Адаптер** (adapter) — реализация порта через конкретную технологию.
  `PostgresOrderRepository`, `StripePaymentService` — адаптеры.

Различают **driving adapters** (входящие, инициируют действия — HTTP controller,
CLI, очередь) и **driven adapters** (исходящие, реагируют на запросы из ядра —
БД, внешние API, отправка событий). На картинках архитектуры driving обычно слева,
driven справа, ядро в центре.

### На практике

Применение в Kotlin/Spring (или любом другом стеке):

```kotlin
// domain/Order.kt
class Order(val id: OrderId, val items: List<OrderItem>, val state: OrderState) {
    fun pay(): Order {
        require(state == OrderState.CREATED) { "Order already paid or cancelled" }
        return copy(state = OrderState.PAID)
    }
}

// domain/OrderRepository.kt — порт
interface OrderRepository {
    fun findById(id: OrderId): Order?
    fun save(order: Order)
}

// application/PayOrderUseCase.kt
class PayOrderUseCase(
    private val orderRepo: OrderRepository,
    private val payments: PaymentService,
) {
    fun execute(orderId: OrderId, paymentMethod: PaymentMethod): Order {
        val order = orderRepo.findById(orderId) ?: throw OrderNotFound(orderId)
        payments.charge(order.totalAmount, paymentMethod)
        val paidOrder = order.pay()
        orderRepo.save(paidOrder)
        return paidOrder
    }
}

// infrastructure/PostgresOrderRepository.kt — адаптер
class PostgresOrderRepository(private val db: Database) : OrderRepository {
    override fun findById(id: OrderId): Order? = db.query(...)
    override fun save(order: Order) = db.update(...)
}
```

Тестируешь use case через моки портов:

```kotlin
val orderRepo = InMemoryOrderRepository()  // тестовый адаптер
val payments = MockPaymentService()
val useCase = PayOrderUseCase(orderRepo, payments)
useCase.execute(orderId, paymentMethod)  // никаких БД и Stripe
```

### Когда модель усложняется

**Domain events.** Часто use case должен опубликовать событие (`OrderPaid`),
чтобы на него отреагировали другие части системы (отправить уведомление,
запланировать доставку). Это тоже порт — `EventPublisher`. Адаптеры реализуют его
через Kafka, RabbitMQ, EventBridge.

**Транзакции.** Use case должен транзакционно обновить БД. Это нарушает чистоту
домена (домен не должен знать про транзакции). Решения:
- **Аннотация `@Transactional`** на use case (Spring) — простое и стандартное.
- **Unit of Work** паттерн — отдельная абстракция, которая накрывает несколько
  репозиториев одной транзакцией.
- **Outbox** для событий — событие пишется в ту же транзакцию, что и изменение
  данных, потом отдельный процесс публикует его в шину.

**Read-side оптимизации.** Когда нужны сложные query с JOIN, fit'ить их в
репозиторий неудобно. Подход CQRS: отдельные **read-models** для query (через
прямые SQL или специальные view), отдельная пишущая часть с агрегатами и use
cases. Read-side не обязан проходить через домен.

## Тестируемость как практический выигрыш

### Что становится возможно

При правильном hexagonal:

- **Unit-тесты домена** — без зависимостей. Order Aggregate тестируется одним
  тестом, проверяя инварианты.
- **Use-case тесты** через мокированные порты. Полная логика оформления заказа
  тестируется без БД, без Stripe, без HTTP — за миллисекунды.
- **Integration тесты адаптеров** — отдельно от логики. PostgresOrderRepository
  тестируется против реальной PostgreSQL (Testcontainers), проверяет SQL.
- **End-to-end тесты** — мало, только для критичных flow.

Пирамида тестов работает: много unit, среднее количество integration, мало e2e.
Тесты быстрые, надёжные, легко поддерживаются.

### На практике

Типичный pitfall: **«fake» хранилище в тестах сильно расходится с реальной БД**.
InMemoryRepository не имеет SQL-ограничений, не имеет уникальных индексов, не
имеет транзакций.

Решение: для критичной логики хранения использовать **Testcontainers** — поднимает
реальную PostgreSQL в Docker для теста, тест видит реальное поведение БД.
Медленнее, чем in-memory, но без сюрпризов в проде.

Гибрид: use case тесты с in-memory репозиторием (быстро, проверяют логику);
интеграционные тесты репозитория против реальной БД (медленно, проверяют SQL и
ограничения). Не путать ответственность.

### Когда модель усложняется

**Тестирование внешних API.** Что делать с тестом, где use case вызывает
PaymentService через Stripe API?

- В unit-тестах — мок PaymentService (порта).
- В интеграционных — Stripe Test Mode (или WireMock с записанными ответами).
- В e2e — против настоящего Stripe Test environment.

Не путать слои. Unit-тест use case'а **не должен** ходить даже в Stripe Test —
это integration. Use case тестируется как чистая логика над абстракцией.

### Где деньги

Hexagonal даёт долгосрочную **скорость разработки** ценой **больше кода
в начале**. Без него разработчик быстрее напишет первый use case (всё в куче).
Через полгода:

- Сервис без hexagonal: каждое изменение бизнес-логики ломает 5 тестов, требует
  поднятия БД, занимает день.
- Сервис с hexagonal: изменение в Order Aggregate — поправил, прогнал unit-тесты,
  готово за час.

Окупается **не сразу**. На MVP ценность сомнительная (всё может выкинуть и
переписать). Когда сервис стабилизировался и активно развивается — критично.

Альтернатива для маленьких сервисов: **simpler architecture** (controller →
service → repository, без hexagonal), осознанно выбранная как trade-off
скорости запуска против долгосрочной maintainability.

Книги для углубления:
- *Clean Architecture* (Robert C. Martin, 2017) — fundamentals, особенно главы
  про dependency rule
- *Implementing Domain-Driven Design* (Vaughn Vernon) — практический пример
  hexagonal на DDD
- Alistair Cockburn — оригинальный пост про Hexagonal architecture (2005), читается
  за час
- *Hexagonal Architecture Explained* (Alistair Cockburn, 2024) — короткая книга,
  обновлённая версия теории
