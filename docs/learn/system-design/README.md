# System Design

Ключевые концепции проектирования распределённых систем для интервью на staff+.

Не заучивание шаблонов из *Designing Data-Intensive Applications*, а понимание
trade-off'ов и умение объяснить, почему выбран X, а не Y, в конкретных условиях.

Каждая глава построена по схеме: теория с интуицией → разбор на типичных
ситуациях → усложнения реальной жизни → экономические соображения, где они
уместны. Главы независимы — можно читать в любом порядке.

## Главы

| № | Глава | Статус |
|---|-------|--------|
| 01 | [Latency, throughput и кривая утилизации](01-latency-throughput.md) | готово |
| 02 | [Доступность, MTTR и cell-based architecture](02-availability-mttr.md) | готово |
| 03 | [CAP и PACELC без мифов](03-cap-pacelc.md) | готово |
| 04 | [Модели консистентности — спектр от линеаризуемости до eventual](04-consistency-models.md) | готово |
| 05 | [Время в распределённой системе — Lamport, vector, HLC, TrueTime](05-distributed-time.md) | готово |
| 06 | [Tail tolerance, SLO и graceful degradation](06-tail-tolerance-slo.md) | готово |

## Тесты

- [Основы (MCQ)](tests/01-fundamentals-mcq.md) — 6 вопросов на percentiles, CAP, PACELC, Little's Law
- [Масштабирование (freeform)](tests/02-scalability-freeform.md) — 2 вопроса с открытым ответом

Тесты будут переработаны в формат «сценарий → разбор» отдельным проходом.

## Что планируется добавить (за пределами стартового набора 01-06)

- Системные паттерны: rate limiting, idempotency, outbox, bulkhead
- Очереди и event-driven: Kafka, exactly-once, ordering, partitioning
- Горизонтальное масштабирование: vertical/horizontal, шардинг, репликация, кеширование
- Хранилища данных: модели, ACID, изоляция, индексы, MVCC, LSM vs B-tree
- Consensus: Raft, Paxos, leader election, log replication
- Distributed transactions: 2PC, Saga, TCC, transactional outbox
- Case studies: «спроектируй URL shortener / news feed / chat»

## Где история обучения

- `remediation/static/` — курированные пояснения к вопросам, которые легко спутать
- `remediation/generated/` — пояснения, написанные LLM по моим провалам (дневник пробелов)
