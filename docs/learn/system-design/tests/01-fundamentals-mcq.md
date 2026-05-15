---
test_id: sd-01-mcq
topic: system-design
type: multiple_choice
questions:
  - id: sd-q1
    text: "Какая percentile-метрика обычно характеризует tail latency и наиболее важна для UX крупных сервисов?"
    options: [p50, p95, p99, "среднее"]
    answer: p99
    remediation: sd-q1
  - id: sd-q2
    text: "Сервис A (99.9%) последовательно вызывает сервис B (99.9%). Какая итоговая availability цепочки?"
    options: ["99.9%", "99.8%", "99.99%", "99%"]
    answer: "99.8%"
    remediation: sd-q2
  - id: sd-q3
    text: "По CAP-теореме во время network partition база данных Cassandra (AP) пожертвует чем?"
    options: [Availability, Consistency, "Partition tolerance", Latency]
    answer: Consistency
    remediation: sd-q3
  - id: sd-q4
    text: "Что описывает формула Little's Law (concurrency = throughput × latency)?"
    options:
      - "связь между нагрузкой и параллельностью в стабильной системе"
      - "предел горизонтального масштабирования"
      - "критерий выбора между sync и async репликацией"
      - "условие применимости CAP-теоремы"
    answer: "связь между нагрузкой и параллельностью в стабильной системе"
    remediation: sd-q4
  - id: sd-q5
    text: "PACELC расширяет CAP, описывая поведение в условии 'Else' — когда нет партиции. Между чем выбирает система в этом случае?"
    options:
      - "Latency vs Consistency"
      - "Availability vs Throughput"
      - "Throughput vs Latency"
      - "Durability vs Availability"
    answer: "Latency vs Consistency"
    remediation: sd-q5
  - id: sd-q6
    text: "Если средняя latency сервиса 30ms, а p99 — 2000ms, что это говорит о системе?"
    options:
      - "система быстрая, p99 — статистический выброс, можно игнорировать"
      - "есть длинный хвост latency, реальный UX определяется им"
      - "p99 нерелевантна для оценки качества пользовательского опыта"
      - "среднее точнее описывает поведение системы"
    answer: "есть длинный хвост latency, реальный UX определяется им"
    remediation: sd-q6
---

# Тест: Основы System Design (multiple choice)

6 вопросов про базовые понятия — latency/throughput, availability, CAP, PACELC, tail latency.

Распределение по главам:
- p99/Little's Law/fan-out → [01-latency-throughput.md](../01-latency-throughput.md)
- composite availability → [02-availability-mttr.md](../02-availability-mttr.md)
- CAP/PACELC → [03-cap-pacelc.md](../03-cap-pacelc.md)
