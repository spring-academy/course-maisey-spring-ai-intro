## Why Observability Matters for AI Applications

AI applications have monitoring needs that standard web applications don't:

| Concern | Why It Matters |
|---------|---------------|
| **Token Usage** | Directly drives costs — every input and output token is billed |
| **Latency** | AI calls can take seconds; P95/P99 latencies matter for UX |
| **Error Rates** | Rate limits, timeouts, and model failures need alerting |
| **Response Quality** | Are answers accurate? Hallucination rates may need tracking |

## Spring AI and Micrometer

Spring AI integrates with Micrometer out of the box. Enable metrics exposure via Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus
```

Spring AI automatically records two key metrics:

| Metric | Description |
|--------|-------------|
| `gen_ai.client.token.usage` | Token counts, tagged with `token.type` (`input` / `output`) |
| `gen_ai.client.operation.duration` | End-to-end call duration |

## Cost Estimation

Token counts from `gen_ai.client.token.usage` let you estimate costs:

```
Cost = (prompt_tokens × input_price) + (completion_tokens × output_price)

Example (GPT-4o):
  Input:  1,000 tokens × $0.0000025 = $0.0025
  Output:    500 tokens × $0.00001  = $0.0050
  Total:                              $0.0075
```

## Production Monitoring Stack

A typical production setup pipes Micrometer metrics into Prometheus and visualizes them in Grafana:

```
Spring AI Metrics → Prometheus (scrape) → Grafana (dashboard)
```

Recommended dashboards:
1. Token usage over time (trend + cost projection)
2. Call latency — P50, P95, P99
3. Error rates by type (rate limit, timeout, model error)
4. Requests per second (load pattern)

## Best Practices

**Set token limits** to prevent runaway costs:
```yaml
spring.ai.openai.chat.options.max-tokens: 1000
```

**Cache repeated queries** — identical prompts return identical responses; caching avoids redundant API calls and cost.

**Route by complexity** — send simple queries to cheaper, faster models and reserve expensive models for complex tasks.

**Alert on anomalies** — set alerts for unusual spikes in token usage, error rates, or latency that may indicate a prompt regression or model degradation.
