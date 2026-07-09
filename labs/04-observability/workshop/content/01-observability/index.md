---
title: Observability
---

By now the Support Assistant covers chat, structured output, RAG, tool calling, and tests. This lab wires up **observability**: structured logs of every prompt and completion, and Micrometer metrics including token usage. Spring AI hooks into Spring's `Observation` API, so you get metrics and traces for chat calls, embeddings, vector store queries, and tool invocations out of the box.

The application in the `sample-app` directory contains the state after the previous labs. If you wanted to recreate the base project yourself, you could generate it via Spring Initializr — set `PROVIDER` to the Spring AI model starter of your choice:

```bash
PROVIDER=openai # Alternatives: anthropic, ollama, bedrock-converse
curl https://start.spring.io/starter.tgz \
  -d artifactId=support-assistant \
  -d name=support-assistant \
  -d packageName=com.example.support-assistant \
  -d type=maven-project \
  -d javaVersion=25 \
  -d dependencies=web,actuator,data-jdbc,h2,devtools,docker-compose,spring-ai-${PROVIDER} \
  | tar -xzvf -
```

The `actuator` dependency is already part of the project. It provides Spring Boot's production-ready endpoints, which we'll build on in this lab. As in the previous labs, the implementation uses **OpenAI**; configurations for Anthropic, Ollama, and AWS Bedrock are available as Spring profiles (`application-<provider>.properties`) in the `src/main/resources` directory.

## Debug Logging for Spring AI

Spring AI's debug logging is already enabled in `application.properties` from the tool-calling lab:

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "logging.level.org.springframework.ai=debug"
description: Show the Spring AI debug logging configuration
```

Besides tool-call decisions, it also surfaces the rendered prompts, retrieved documents, and raw responses at the right log levels — handy when an answer is surprising and you want to know why.

## Expose the Actuator Endpoints

Expose the metrics and Prometheus endpoints via Spring Boot Actuator:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Apply - Expose the metrics and prometheus actuator endpoints
text: |2

  management.endpoints.web.exposure.include=health,metrics,prometheus
```

{{< note >}}
⚠️ **Security note** This exposes `/actuator/metrics` and `/actuator/prometheus` on the application port. **Do not do this in production.** Bind actuator to a separate management port (`management.server.port`), restrict it to an internal network, and put authentication in front of it. The endpoints leak request paths, model names, and (with the flags below) prompt content — all worth protecting.
{{< /note >}}

For the Prometheus formatting, you also need the Micrometer registry as an additional dependency:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-boot-starter-actuator</artifactId>"
description: Apply - Add the Micrometer Prometheus registry to pom.xml
before: 2
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-starter-actuator</artifactId>
  		</dependency>
  		<dependency>
  			<groupId>io.micrometer</groupId>
  			<artifactId>micrometer-registry-prometheus</artifactId>
  			<scope>runtime</scope>
  		</dependency>
```

## Verbose Spring AI Observation Content

Spring AI's observations don't include prompt and response content by default (PII risk). Opt in if you want it in logs and traces:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Apply - Include prompt and response content in observations
text: |2

  # Not recommended for production - high data volume and risk of exposing sensitive content
  spring.ai.chat.client.observations.log-prompt=true
  spring.ai.chat.client.observations.log-completion=true
  spring.ai.chat.client.observations.include-error-logging=true
  spring.ai.tools.observations.include-content=true
  spring.ai.vectorstore.observations.log-query-response=true
```

In a workshop or development setup, this is gold — you see every prompt the model sees, every retrieved document, every tool argument. In production, it's a compliance liability; default it off.

## Generate Traffic and Inspect the Metrics

Set your OpenAI API key in the terminal session the application will run in:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

Start the application:

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" before proceeding.
{{< /note >}}

Exercise the two flows you built in the earlier labs — a RAG query:

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
session: 1
```

And a tool-calling query:

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Please open a high-priority ticket: SSO login returns 502 on the Tanzu portal."
session: 1
```

Repeat each a few times so the histograms have something interesting to show.

### Inspect via the Metrics Endpoint

List all metric names registered in the app:

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics | jq
session: 1
```

Drill into one — total tokens consumed per call type (input vs output):

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics/gen_ai.client.token.usage | jq
session: 1
```

The response breaks down `input` tokens (sent to the model) and `output` tokens (generated by the model). These are the numbers that determine cost.

Chat client request latency:

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics/gen_ai.client.operation | jq
session: 1
```

Vector store query timings:

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics/spring.ai.vector.store.client.operation | jq
session: 1
```

The metric names follow the OpenTelemetry GenAI semantic conventions. Useful tags you'll see: `gen_ai.system` (`openai`, `anthropic`, ...), `gen_ai.request.model`, `gen_ai.operation.name` (`chat`, `embedding`, ...), and on token usage `gen_ai.token.type` (`input` / `output`).

The metric *names* and *shapes* are provider-agnostic. What varies is the `gen_ai.system` tag value and which labels carry meaningful data:

- **OpenAI / Anthropic / Bedrock** — every call has accurate input/output token counts because the provider returns them in the response. Cost dashboards are straightforward.
- **Ollama** — token counts are reported, but the values come from the local model and aren't billed against anything; useful for throughput, not cost.

The same applies to the vector store: the `SimpleVectorStore` used in this lab emits `spring.ai.vector.store.*` observations the same way as, for example, pgvector, so dashboards transfer between setups without changes. With pgvector, you additionally get the standard JDBC/HikariCP metrics (`hikaricp_*`, `jdbc_*`) for connection-pool health.

### Inspect via the Prometheus Endpoint

```terminal:execute
command: curl -s http://localhost:8080/actuator/prometheus | grep gen_ai
session: 1
```

You'll see entries like:

```
gen_ai_client_token_usage_sum{gen_ai_system="openai",gen_ai_request_model="gpt-5.4-mini",gen_ai_token_type="input"} 482.0
gen_ai_client_token_usage_count{gen_ai_system="openai",gen_ai_request_model="gpt-5.4-mini",gen_ai_token_type="input"} 3
gen_ai_client_operation_seconds_bucket{gen_ai_operation_name="chat",le="0.5"} 1.0
```

That's already enough to scrape with any Prometheus-compatible TSDB.

Reference: [Spring AI Observability documentation](https://docs.spring.io/spring-ai/reference/observability/index.html)

## Stop the Application

```terminal:interrupt
session: 2
```

## Going Further: OpenTelemetry Export Into Grafana

The actuator endpoints are great for spot checks. For a real dashboard, push everything via OTLP into a stack that stores and visualizes it. This is not part of this lab, but here is how you could set it up for local testing.

The `grafana/otel-lgtm` image bundles Grafana, Mimir (metrics), Loki (logs), Tempo (traces), and an OTel collector into a single container — add it as a service to the project's `compose.yaml`:

```yaml
  otel-lgtm:
    image: grafana/otel-lgtm:latest
    container_name: support-assistant-otel-lgtm
    ports:
      - "3000:3000" # Grafana UI
      - "4317:4317" # OTLP gRPC
      - "4318:4318" # OTLP HTTP
    profiles:
      - otel
```

And start it with:

```bash
docker compose --profile otel up -d
```

Spring Boot **4.1.0-RC1** ships a new, simpler observability story for AI apps: the `spring-boot-starter-opentelemetry` starter wires tracing, metrics, and logs OTLP exporters in one shot, and the Spring AI observation set is broader (per-document retrieval spans, structured tool-call attributes). Bump the parent in `pom.xml`:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0-RC1</version>
    <relativePath/>
</parent>
```

And add the starter:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
```

Then point Spring Boot at the OTLP endpoints in `application.properties`:

```properties
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
management.otlp.metrics.export.url=http://localhost:4318/v1/metrics
management.otlp.logging.endpoint=http://localhost:4318/v1/logs

management.tracing.sampling.probability=1.0
```

`sampling.probability=1.0` captures every request — fine for development, dial it down (`0.1`, `0.01`) before anything resembling production traffic.

After restarting the application and generating some traffic, Grafana is available at `http://localhost:3000` (default credentials `admin` / `admin`), with Mimir, Loki, and Tempo pre-configured as datasources:

- **Explore → Tempo** — search by service name `support-assistant`; click a trace to see the chat span tree (HTTP request → ChatClient call → vector store query → tool invocation → second ChatClient call → response).
- **Explore → Loki** — `{service_name="support-assistant"}` shows the structured Spring AI logs (with prompts and completions if you enabled the observation content flags).
- **Explore → Mimir** — the same `gen_ai_*` metrics, ready to graph.

Token usage is the metric most teams want first — it's the proxy for cost. To visualize it per request, paste this into **Explore → Prometheus**:

```promql
sum by (gen_ai_token_type, gen_ai_request_model) (
  rate(gen_ai_client_token_usage_total[1m])
)
```

What this asks: "across the last minute, how many tokens per second are we burning, broken down by whether they were input or output and by which model served them?"
