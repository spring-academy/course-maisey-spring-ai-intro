---
title: OpenTelemetry Export Into Grafana
---

The actuator endpoints are good for spot checks. For a real dashboard you push everything over OTLP into a stack that stores and visualizes it. 

In this section you run that stack locally, wire the application to it, and read your traces and metrics in Grafana.

The `grafana/otel-lgtm` image bundles Grafana, Prometheus (metrics), Loki (logs), Tempo (traces), and an OTel collector into a single container. You run it with Docker Compose, and you let Spring Boot start and stop it together with the application.

{{< note >}}
Only traces and metrics are forwarded in this lab. Sending your logs over OTLP as well needs an extra OpenTelemetry logging appender and some Logback configuration in the application, which is out of scope here.
{{< /note >}}

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
      volumes:
      - ./custom-grafana-dashboard.json:/otel-lgtm/grafana/conf/provisioning/dashboards/custom/custom-dashboard.json:ro
      environment:
        GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: /otel-lgtm/grafana/conf/provisioning/dashboards/custom/custom-dashboard.json
```

The two last lines bring in a ready-made dashboard. The file `custom-grafana-dashboard.json` is already part of the project, and the volume mount puts it where Grafana looks for provisioned dashboards. The `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` variable makes it the home dashboard.

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
  management.otlp.metrics.export.step=5s
  management.metrics.tags.application=${spring.application.name}
```

Every request now ends up in a trace, metrics are pushed every 5 seconds instead of every minute so your numbers show up in Grafana almost right away, and each metric carries an `application` tag you can filter and group by. Full sampling and such a short interval are right for a workshop. In production you lower both to keep the data volume down.

## Start the App and Explore in Grafana

The application is still running from the previous section. Spring Boot DevTools restarts it when your classes change, but it does not pick up the new dependencies and the new `compose.yaml`. So stop it first.

```terminal:interrupt
session: 2
```

Then start it again.

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

Spring Boot now starts the otel-lgtm container before the application, so this run takes a bit longer.

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

Open the **Grafana** dashboard tab.
```dashboard:open-dashboard
name: Grafana
```

You land on the provisioned **Spring AI Observability** dashboard. It visualizes the core metrics Spring AI records for you, so token usage, model call latency and errors, and the time spent in the advisors and in your tools. The last row lists the matching traces from Tempo. 

The tool call table shows the arguments and the result, which are only there because you turned the observation content flags on in the previous section. 

Give it a few seconds after your requests, because metrics arrive in 5 second batches.

Now follow one request end to end. In the **Chat API traces** table, click on one of the requests.

Grafana opens the trace view with the full span tree. You see the HTTP request at the top, and below it the `ChatClient` span with the advisors nested inside, the vector store query the RAG advisor triggers, the first model call, the tool call that the model asked for, and the second model call that produces the final answer. The width of each bar tells you where the time went, which is usually the model calls.

Click a single span to see its attributes on the right. The model spans carry `gen_ai.request.model`, the token counts, and the finish reason, and the tool span carries the tool name with the arguments and the result. This is the same data as in the tables, now in the context of one request.

Compare a trace of the RAG query with a trace of the tool-calling query. The tool-calling one has the extra tool span and the second model call, which is the round trip you saw in the logs earlier.

Besides the dashboard, you can query the data yourself under **Explore**. Token usage is usually the first thing teams want, so pick the Prometheus datasource and paste in the query behind the token rate panel.
Explore opens in the **Builder** view, where you click a query together from dropdowns. Switch to the **Code** view with the toggle on the right of the query row before you paste the query in.

```promql
sum by (gen_ai_token_type, gen_ai_request_model) (
  rate(gen_ai_client_token_usage_total[1m])
)
```

In plain words this asks how many tokens per second you use across the last minute, broken down by whether they were input or output and by which model served them.

## Make Observability Opt-In With a Profile

The otel-lgtm container is heavy, and the verbose prompt and completion logging is a data volume and privacy risk. You do not want either on every run. Move all of it behind a profile so the default run stays lightweight, and you switch the full stack on only when you want it.

First, gate the otel-lgtm service behind an `otel` Docker Compose profile, so Compose does not start it unless that profile is active.

```editor:append-lines-to-file
file: ~/sample-app/compose.yaml
description: Put otel-lgtm behind the otel compose profile
text: |2
      profiles:
        - otel
```

Now collect every opt-in setting in a profile-specific `application-local-observability.properties`. Spring Boot loads this file only when the `local-observability` profile is active, and its values override `application.properties`. The last line activates the `otel` Compose profile, so turning on the Spring profile also starts the otel-lgtm container.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application-local-observability.properties
description: Create the local-observability profile with the opt-in configuration
text: |
  # Not recommended for production - high data volume and risk of exposing sensitive content
  spring.ai.chat.client.observations.log-prompt=true
  spring.ai.chat.client.observations.log-completion=true
  spring.ai.chat.observations.log-prompt=true
  spring.ai.chat.observations.log-completion=true
  spring.ai.chat.observations.include-error-logging=true
  spring.ai.tools.observations.include-content=true
  spring.ai.vectorstore.observations.log-query-response=true

  management.otlp.metrics.export.enabled=true
  management.otlp.metrics.export.step=5s
  management.metrics.tags.application=${spring.application.name}

  management.otlp.tracing.export.enabled=true
  management.tracing.sampling.probability=1.0

  spring.docker.compose.enabled=true
  spring.docker.compose.profiles.active=otel
```

Finally, take the same settings back out of `application.properties` and turn the exporters off by default, so nothing is exported and nothing sensitive is logged unless you ask for it.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "# Not recommended for production - high data volume and risk of exposing sensitive content"
description: Replace the inline observability config with disabled defaults
after: 14
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/resources/application.properties
hidden: true
text: |2
  # The observability stack is opt-in, enable it with the local-observability profile
  management.otlp.metrics.export.enabled=false
  management.otlp.tracing.export.enabled=false
  management.otlp.logging.export.enabled=false
  spring.docker.compose.enabled=false
```

Now the default run is lightweight, with no exporters and no otel-lgtm container. When you want the full stack again, start the application with the `local-observability` profile.

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-observability
```

The profile turns the exporters back on and, through `spring.docker.compose.profiles.active=otel`, starts the otel-lgtm container again.
