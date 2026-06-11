---
title: The ChatClient API
---

# The Fluent ChatClient API

`ChatModel` works, but everyday code reads better with the fluent `ChatClient`. It wraps a `ChatModel`, lets you compose a prompt, invoke the model, and shape the response in a single readable chain — and it gives us a place to put shared defaults later.

## Switch to ChatClient

Spring Boot auto-configures a `ChatClient.Builder` but not a `ChatClient` itself, so first we expose one as a bean:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
description: Create SupportAssistantConfiguration
text: |
  package com.example.support_assistant;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;

  @Configuration
  public class SupportAssistantConfiguration {

      @Bean
      public ChatClient chatClient(ChatClient.Builder builder) {
          return builder.build();
      }
  }
```

Now inject the `ChatClient` into the service alongside `ChatModel` (we keep `ChatModel` around for reference, but from here on all calls go through `ChatClient`) and replace `generateResponse` with the fluent chain:

```java
String generateResponse(String query) {
    return chatClient.prompt()      // start building a request
            .user(query)            // add the user's message
            .call()                 // send the request (blocking)
            .content();             // extract the response text
}
```

Click to apply:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatModel chatModel;"
before: 0
after: 0
description: Apply - switch to ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private final ChatModel chatModel;
      private final ChatClient chatClient;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportAssistantService(ChatModel chatModel) {"
before: 0
after: 2
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportAssistantService(ChatModel chatModel, ChatClient chatClient) {
          this.chatModel = chatModel;
          this.chatClient = chatClient;
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 14
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .user(query)
                  .call()
                  .content();
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.chat.client.ChatClient;
```

Restart and test:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Add a Streaming Endpoint

Models generate text token by token. Swap `.call()` for `.stream()` to get a reactive `Flux<String>` and stream tokens to the client as soon as they arrive — this is what powers the "typewriter" effect in chatbots.

Add a streaming method to the service:

```java
Flux<String> streamResponse(String query) {
    return chatClient.prompt()
            .user(query)
            .stream()
            .content();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 5
description: Apply - add streamResponse to the service
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .user(query)
                  .call()
                  .content();
      }

      Flux<String> streamResponse(String query) {
          return chatClient.prompt()
                  .user(query)
                  .stream()
                  .content();
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import reactor.core.publisher.Flux;
```

And a streaming endpoint to the controller, producing Server-Sent Events (SSE):

```java
@GetMapping(path = "/api/{version}/chat/stream",
            version = "1.0",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> chatStream(@RequestParam String query) {
    return service.streamResponse(query);
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: '@GetMapping(path = "/api/{version}/chat")'
before: 0
after: 3
description: Apply - add streaming endpoint to the controller
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
cascade: true
hidden: true
text: |2
      @GetMapping(path = "/api/{version}/chat")
      String chat(@RequestParam String query) {
          return service.generateResponse(query);
      }

      @GetMapping(path = "/api/{version}/chat/stream",
                  version = "1.0",
                  produces = MediaType.TEXT_EVENT_STREAM_VALUE)
      Flux<String> chatStream(@RequestParam String query) {
          return service.streamResponse(query);
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
line: 3
hidden: true
text: |-
  import org.springframework.http.MediaType;
  import reactor.core.publisher.Flux;
```

Restart and try it with `curl -N` (no buffering) to see the typewriter effect:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -N -G "http://localhost:8080/api/1.0/chat/stream" --data-urlencode "query=Tell me about Spring AI"
```

Notice the `data:` prefix of the SSE protocol on each chunk.

## Inline User Template

`PromptTemplate` is still useful, but for one-off templating at the call site, `ChatClient` accepts a lambda that builds the user message with its own placeholder syntax — no separate `PromptTemplate` needed:

```java
String generateResponse(String query) {
    return chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .call()
            .content();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 5
description: Apply - inline user template
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .content();
      }
```

## Inline System Prompt

Same idea for the system role — declare it inline on the request:

```java
String generateResponse(String query) {
    return chatClient.prompt()
            .system("You are a Spring and AI expert.")
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .call()
            .content();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 7
description: Apply - inline system prompt
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .system("You are a Spring and AI expert.")
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .content();
      }
```

Restart and test:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Move the System Prompt to a Default

Repeating the system prompt on every call is duplication. Put it on the `ChatClient` bean as a default, and every call through that client picks it up automatically. A per-call `.system(...)` would still win if you ever need to override.

Update the bean in `SupportAssistantConfiguration`:

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultSystem("You are a Spring and AI expert.")
            .build();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "return builder.build();"
before: 0
after: 0
description: Apply - default system prompt on the ChatClient bean
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
          return builder
                  .defaultSystem("You are a Spring and AI expert.")
                  .build();
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 8
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .content();
      }
```

The `.system(...)` line is dropped from the service at the same time.

## Access the Full Response

`.content()` is a shortcut for the text. When you also want metadata (token counts for billing, finish reason, model id, ...), ask for the full `ChatResponse` instead:

```java
String generateResponse(String query) {
    var chatResponse = chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .call()
            .chatResponse();
    log.info("Chat Response {}", chatResponse);
    return chatResponse.getResult().getOutput().getText();
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 7
description: Apply - access the full ChatResponse
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      String generateResponse(String query) {
          var chatResponse = chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .chatResponse();
          log.info("Chat Response {}", chatResponse);
          return chatResponse.getResult().getOutput().getText();
      }
```

Restart and test, then check the logs in the second terminal for the full `ChatResponse` with its usage metadata:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Summary

You've switched to the fluent `ChatClient`: a configured bean with shared defaults (`defaultSystem`), inline user/system prompts, blocking and streaming calls, and access to the full `ChatResponse`.

One more jump to go: structured output.
