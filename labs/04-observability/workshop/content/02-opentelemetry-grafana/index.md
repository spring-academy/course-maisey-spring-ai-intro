---
title: OpenTelemetry Export Into Grafana
---

The actuator endpoints are good for spot checks. For a real dashboard you push everything over OTLP into a stack that stores and visualizes it. In this section you run that stack locally, wire the application to it, and read your traces, logs, and metrics in Grafana.

The `grafana/otel-lgtm` image bundles Grafana, Mimir (metrics), Loki (logs), Tempo (traces), and an OTel collector into a single container. You run it with Docker Compose, and you let Spring Boot start and stop it together with the application.

## Add the otel-lgtm Stack to Docker Compose

Create a `compose.yaml` in the project root with the otel-lgtm service.

```editor:append-lines-to-file
file: ~/sample-app/compose.yaml
description: Create compose.yaml with the otel-lgtm service
text: |
  services:
    otel-lgtm:
      image: grafana/otel-lgtm:latest
      container_name: support-assistant-otel-lgtm
      ports:
        - "3000:3000" # Grafana UI
        - "4317:4317" # OTLP gRPC
        - "4318:4318" # OTLP HTTP
```

## Let Spring Boot Manage the Compose Stack

The `spring-boot-docker-compose` module detects the `compose.yaml` at startup, runs `docker compose up`, and stops the containers again when the application shuts down. So you do not start the stack by hand. It also recognizes the `grafana/otel-lgtm` image and fills in the OTLP endpoints for you, which you rely on further down.

Add it after the `spring-boot-devtools` dependency.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-boot-devtools</artifactId>"
description: Add the Spring Boot Docker Compose dependency
before: 2
after: 3
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-devtools</artifactId>
  			<scope>runtime</scope>
  			<optional>true</optional>
  		</dependency>
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-docker-compose</artifactId>
  			<scope>runtime</scope>
  			<optional>true</optional>
  		</dependency>
```

## Add the OpenTelemetry Starter

Spring Boot **4.0** is the first release with an official `spring-boot-starter-opentelemetry` starter, and Spring Boot **4.1** builds on that initial support. 

This starter pulls in `io.micrometer:micrometer-registry-otlp`, the OTLP metrics registry. It plays the same role over OTLP that `micrometer-registry-prometheus` played for scraping, so you swap one for the other.
```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>micrometer-registry-prometheus</artifactId>"
description: Replace the Prometheus registry with the OpenTelemetry starter
before: 2
after: 2
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-starter-opentelemetry</artifactId>
  		</dependency>
```

## Set the Trace Sampling Rate

You do not set any OTLP URLs. Because `spring-boot-docker-compose` recognizes the `grafana/otel-lgtm` image, Spring Boot reads the container ports and provides the traces, metrics, and logs endpoints as connection details for you. The only thing left is how much to sample.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Capture every request in traces
text: |2

  management.tracing.sampling.probability=1.0
```

`sampling.probability=1.0` captures every request. That is fine for development, but lower it to `0.1` or `0.01` before anything close to production traffic.

## Start the App and Explore in Grafana

Spring Boot now starts the otel-lgtm container first, so the first run takes a bit longer while the image is pulled.
```dashboard:open-dashboard
name: Terminal
```

{{< note >}}
Wait for "Started SupportAssistantApplication" before proceeding.
{{< /note >}}

Generate some traffic. First a RAG query.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does VMware Tanzu Spring provide commercial support for Micrometer?"
session: 1
```

Then a tool-calling query.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Open a high priority ticket to request a trial for VMware Tanzu Spring"
session: 1
```

Open the **Grafana** dashboard tab. Sign in with the default credentials `admin` / `admin`. Mimir, Loki, and Tempo are pre-configured as datasources.

```dashboard:open-dashboard
name: Grafana
```

- **Explore → Tempo.** Search by service name `support-assistant`, then click a trace to see the chat span tree, from the HTTP request through the ChatClient call, the vector store query, the tool invocation, the second ChatClient call, and the response.
- **Explore → Loki.** Query `{service_name="support-assistant"}` to show the structured Spring AI logs, with prompts and completions if you enabled the observation content flags.
- **Explore → Mimir.** The same `gen_ai_*` metrics, ready to graph.

Token usage is the metric most teams want first, because it is the proxy for cost. To visualize it per request, paste this into **Explore → Mimir**.

```promql
sum by (gen_ai_token_type, gen_ai_request_model) (
  rate(gen_ai_client_token_usage_total[1m])
)
```

In plain words this asks how many tokens per second you use across the last minute, broken down by whether they were input or output and by which model served them.

## Disable the Stack by Default

The otel-lgtm container is heavy and you do not want it on every run. Turn off the Compose management and the OTLP exporters by default, so the base application stays lightweight.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Disable the Compose stack and OTLP export by default
text: |2

  # Disabled by default, the observability stack is opt-in for local exploration
  spring.docker.compose.enabled=false
  management.otlp.metrics.export.enabled=false
  management.otlp.tracing.export.enabled=false
  management.otlp.logging.export.enabled=false
```
