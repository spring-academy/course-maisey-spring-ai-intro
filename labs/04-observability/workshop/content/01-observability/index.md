---
title: Built-in Logs and Metrics
---

By now the Support Assistant covers chat, structured output, RAG, tool calling, and tests. In this lab you add **observability**. You get structured logs of every prompt and completion, and Micrometer metrics that include token usage. Spring AI hooks into Spring's `Observation` API, so you get metrics and traces for chat calls, embeddings, vector store queries, and tool calls without extra work.

The application in the `sample-app` directory contains the state after the previous labs. 

## Debug Logging for Spring AI

Requests and responses are not logged out of the box. You get them from the `SimpleLoggerAdvisor` that you registered on the `ChatClient` in the advisors lab.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),"
description: Show the SimpleLoggerAdvisor on the ChatClient
```

The advisor logs at `DEBUG` level, so it stays quiet until you raise the log level. That is already done in `application.properties` from the tool-calling lab, which also turns on Spring AI's own debug output such as the tool-call decisions.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.properties
text: "logging.level.org.springframework.ai=debug"
description: Show the Spring AI debug logging configuration
```

The advisor runs with the lowest precedence, so it sees the request after every other advisor has changed it. The logged prompt therefore contains the documents retrieved for RAG and the conversation history added by the memory advisor. This is helpful when an answer is surprising and you want to know why.

## Expose the Actuator Endpoints

Spring Boot Actuator adds endpoints to a running application that show what it is doing. It comes with one dependency, which is already part of the project.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-boot-starter-actuator</artifactId>"
description: Show the Actuator dependency
```

Actuator brings a whole set of endpoints. `health` reports whether the application is up, `info` shows build and version data, `env` shows the resolved configuration, `loggers` reads and changes log levels while the application runs, and `metrics` exposes everything Micrometer records. Only `health` is reachable over HTTP by default, because the other endpoints can reveal a lot about your application.

You need three of them in this lab.

* `health` confirms that the application has started.
* `metrics` lists all metric names and returns the values of a single metric.
* `prometheus` returns the same metrics in the text format that a Prometheus server scrapes.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Expose the metrics and prometheus actuator endpoints
text: |2

  management.endpoints.web.exposure.include=health,metrics,prometheus
```

{{< note >}}
⚠️ **Security note** This exposes `/actuator/metrics` and `/actuator/prometheus` on the application port. **Do not do this in production.** Bind actuator to a separate management port (`management.server.port`), restrict it to an internal network, and put authentication in front of it. The endpoints leak request paths, model names, and (with the flags below) prompt content, which are all worth protecting.
{{< /note >}}

The `prometheus` endpoint only shows up when a Micrometer Prometheus registry is on the classpath, so add it as a second dependency.

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-boot-starter-actuator</artifactId>"
description: Add the Micrometer Prometheus registry dependency
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

## Spring AI Observations and Their Content

The metrics you are about to look at come from Micrometer's `Observation` API. An observation is a single instrumentation point around a piece of work. You record it once, and registered handlers turn it into several outputs. A meter handler writes timers and counters for the metrics backend, a tracing handler creates a span, and a logging handler writes it to the application log. Spring AI records the observations for you, which is why metrics show up without any instrumentation code in your application.

Spring AI records an observation for every step of an AI call.

* The `ChatClient` call, which covers the complete request including all advisors.
* The `ChatModel` call, which is the single request to the provider and carries the token counts.
* Embedding model calls and vector store queries.
* Every tool call the model triggers.

Each observation carries key values, which become tags on the metric and attributes on the span. Only values with few possible variants belong there, such as the model name, the provider, and the operation name. The prompt and the answer are different on every request. Putting them on a metric would create a new time series each time and overwhelm the metrics backend.

That is one reason why Spring AI leaves the prompt and response content out of its observations by default. The other reason is that this content often holds personal data, which you do not want to spread into logs and traces by accident. You can opt in, and Spring AI then registers logging handlers that write the content to the application log. If a tracer is on the classpath, those log lines also carry the trace id, so you can move from a log entry to the matching trace.

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Include prompt and response content in observations
text: |2

  # Not recommended for production - high data volume and risk of exposing sensitive content
  spring.ai.chat.client.observations.log-prompt=true
  spring.ai.chat.client.observations.log-completion=true
  
  spring.ai.chat.observations.log-prompt=true
  spring.ai.chat.observations.log-completion=true
  spring.ai.chat.observations.include-error-logging=true
  
  spring.ai.tools.observations.include-content=true

  spring.ai.vectorstore.observations.log-query-response=true
```

Watch the two different prefixes. `spring.ai.chat.client.observations` sits at the `ChatClient` level, so it captures the request as your code handed it over, before the advisors changed it. `spring.ai.chat.observations` sits at the `ChatModel` level, which is the request that finally goes to the provider. The same split exists for tools and for the vector store, each with its own prefix.

Spring AI logs a warning at start-up whenever you enable one of these flags, as a reminder that the content can be sensitive.

In a workshop or development setup, this is very useful. You see every prompt the model sees, every retrieved document, and every tool argument. In production, it is a compliance risk, so keep it off by default.

## Generate Traffic and Inspect the Logs and Metrics

Start the application.

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" before proceeding.
{{< /note >}}

Now try the two flows you built in the earlier labs. First a RAG query.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does VMware Tanzu Spring provide commercial support for Micrometer?"
session: 1
```

And then a tool-calling query.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Open a high priority ticket to request a trial for VMware Tanzu Spring"
session: 1
```

Feel free to run each one a few times so the histograms have some data to show.

### Watch the Logs

Have a look at the application logs for the two requests in the second terminal. They now come from two different sources. The `SimpleLoggerAdvisor` writes the `request:` and `response:` lines at `DEBUG` level, and the observation handlers you just switched on at `INFO` level. 

Compare the prompts in that output. The `request:` line from the advisor holds the retrieved documents and the JSON schema, because the advisor runs last in the chain and sees the finished request. The `Chat Model Prompt Content:` provides the same information, but the `Chat Client Prompt Content:` line holds only the question your controller passed in, because that observation is opened before the advisors run. 

This is the difference between the two property prefixes, now visible in the logs.

The tool-calling query adds the tool components and a second round trip to the model.

The first response carries no text at all. It has `finishReason` `TOOL_CALLS` and the arguments the model wants to use. Spring AI runs your method, converts the return value to JSON, appends it as a `ToolResponseMessage`, and sends the whole conversation to the model a second time. Only that second response holds the answer for the user. Two model calls also mean two entries in the token metrics you look at next.

### Query the Metrics Endpoint

The `metrics` endpoint is the quickest way to see what Micrometer collected, with nothing but `curl`. Called without a name it returns the list of all metric names, and called with a name it returns the current values together with the tags that split them up. It only knows the values of the running instance and keeps no history, so it is made for a quick look during development. For dashboards and alerts you need the Prometheus endpoint further down.

List all metric names registered in the app.

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics | jq
session: 1
```

Look at one metric, the total tokens used per call type (input versus output).

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics/gen_ai.client.token.usage | jq
session: 1
```

The response breaks down `input` tokens (sent to the model) and `output` tokens (generated by the model). These are the numbers that drive cost.

Chat client request latency.

```terminal:execute
command: curl -s http://localhost:8080/actuator/metrics/gen_ai.client.operation | jq
session: 1
```

The metric names follow the OpenTelemetry GenAI semantic conventions. You will see useful tags such as `gen_ai.system` (`openai`, `anthropic`, ...), `gen_ai.request.model`, `gen_ai.operation.name` (`chat`, `embedding`, ...), and on token usage `gen_ai.token.type` (`input` / `output`).

The metric *names* and *shapes* are provider-agnostic. What changes is the `gen_ai.system` tag value and which labels carry meaningful data.

### Scrape the Prometheus Endpoint

The metrics endpoint gave you the values of this one running instance at this one moment. Nothing keeps them, so you cannot see how the token usage grew over the last hour, and you cannot put a chart or an alert on it. For that you need a system that collects the values over time, and the common choice is Prometheus.

Prometheus collects them by calling your application's `prometheus` actuator endpoint at a fixed interval.

```terminal:execute
command: curl -s http://localhost:8080/actuator/prometheus | grep gen_ai
session: 1
```

The names look different from the ones you used on the metrics endpoint. The Prometheus format allows no dots, so `gen_ai.client.token.usage` becomes `gen_ai_client_token_usage`, and the tag names change in the same way.

In the next section you go further and push everything into a Grafana stack over OpenTelemetry, so you get dashboards, traces, and log search instead of spot checks.
