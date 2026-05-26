## The ChatClient API

Spring AI's primary abstraction for interacting with language models is the `ChatClient`. It provides a fluent builder API that lets you compose a prompt, call the model, and process the response in a single chain:

```java
chatClient.prompt()
    .user(query)    // add the user's question as a user message
    .call()         // execute the request (blocking)
    .content();     // extract the plain text content from the response
```

For longer responses, a streaming variant returns a reactive `Flux<String>`:

```java
chatClient.prompt()
    .user(query)
    .stream()               // stream tokens as they arrive
    .content();             // returns Flux<String>
```

To access the full response including token usage and model metadata, use `.chatResponse()` instead of `.content()`.

## Dependencies and Configuration

To use Spring AI with OpenAI (or any other supported provider), add the relevant starter to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Also add the Spring AI BOM to manage versions consistently across all Spring AI dependencies.

The starter provides the implementation and Spring Boot auto-configuration so that `ChatClient` is available as a bean automatically.

The key configuration properties for the OpenAI provider are:

| Property | Description |
|----------|-------------|
| `spring.ai.openai.api-key` | Your API key (typically from an environment variable) |
| `spring.ai.openai.chat.options.model` | The model to use (e.g., `gpt-4o`, `gpt-4-turbo`) |

{{< note >}}
The hands-on lab uses a built-in **mock service** by default. The mock uses the same configuration structure but points to `http://localhost:8080/mock` instead of OpenAI's servers, so your code works identically with both.
{{< /note >}}

## Blocking vs. Streaming

| Approach | Method | Return type | When to use |
|----------|--------|-------------|-------------|
| Blocking | `.call().content()` | `String` | Short responses, simple integrations |
| Streaming | `.stream().content()` | `Flux<String>` | Long responses, real-time UX |

Streaming uses Server-Sent Events (`text/event-stream`) and returns each token as a `data:` frame as it is generated.

## Response Metadata

`.call().chatResponse()` returns the full `ChatResponse` object, which includes:

- `result` — the generated message with finish reason
- `metadata.usage` — token counts (prompt tokens + completion tokens)
- `metadata` — model name and request details

Tracking token counts via `metadata.usage` is important for cost management, since AI providers charge per token.
