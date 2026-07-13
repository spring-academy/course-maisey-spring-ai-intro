---
title: The Fluent ChatClient API
---

`ChatModel` works, but everyday code reads better with the fluent `ChatClient`. It wraps a `ChatModel`, lets you compose a prompt, invoke the model, and shape the response in a single readable chain.

## Switch to ChatClient

Spring Boot auto-configures a `ChatClient.Builder` but not a `ChatClient` itself, so first we expose one as a bean:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
description: Configure ChatClient bean
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

Now replace the `ChatModel` in our service with `ChatClient`:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatModel chatModel;"
before: 0
after: 0
description: Switch to ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
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
      SupportAssistantService(ChatClient chatClient) {
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

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "import org.springframework.ai.openai.OpenAiChatOptions;"
before: 0
after: 7
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
```


Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Add a Streaming Endpoint

Models generate text token by token. Swap `.call()` for `.stream()` to get a reactive `Flux<String>` and stream tokens to the client as soon as they arrive. This is what powers the "typewriter" effect in chatbots.

To keep this focused, we add the whole streaming call as a throwaway endpoint directly in the controller and remove it again at the end of this section. The rest of the lab stays on the blocking `.call()`. 

Inject the `ChatClient` into the controller so the streaming method can use it directly.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "import org.springframework.web.bind.annotation.RestController;"
before: 0
after: 0
description: Inject the ChatClient into the controller
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
cascade: true
text: |
  import org.springframework.web.bind.annotation.RestController;
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.http.MediaType;
  import reactor.core.publisher.Flux;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "private final SupportAssistantService service;"
before: 0
after: 4
description: Inject the ChatClient into the controller
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      private final SupportAssistantService service;
      private final ChatClient chatClient;

      SupportAssistantController(SupportAssistantService service, ChatClient chatClient) {
          this.service = service;
          this.chatClient = chatClient;
      }
```

Now add the streaming endpoint, producing Server-Sent Events (SSE):
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "String chat(@RequestParam String query) {"
before: 0
after: 2
description: Add the streaming endpoint
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      String chat(@RequestParam String query) {
          return service.generateResponse(query);
      }

      @GetMapping(path = "/api/v{version}/chat/stream",
                  produces = MediaType.TEXT_EVENT_STREAM_VALUE)
      Flux<String> chatStream(@RequestParam String query) {
          return chatClient.prompt()
                  .user(query)
                  .stream()
                  .content();
      }
```

Try it with `curl -N` (no buffering) to see the typewriter effect:
```execute
curl -N -G "http://localhost:8080/api/v1/chat/stream" --data-urlencode "query=Tell me about Spring AI"
```

Notice the `data:` prefix of the SSE protocol on each chunk.

Now that you have seen streaming work, remove the throwaway endpoint again so the controller stays simple for the rest of the lab. This drops the streaming method, the injected `ChatClient`, and the extra imports.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "String chat(@RequestParam String query) {"
before: 0
after: 11
description: Remove the streaming endpoint
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
cascade: true
hidden: true
text: |2
      String chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "private final SupportAssistantService service;"
before: 0
after: 6
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
cascade: true
hidden: true
text: |2
      private final SupportAssistantService service;

      SupportAssistantController(SupportAssistantService service) {
          this.service = service;
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "import org.springframework.web.bind.annotation.RestController;"
before: 0
after: 3
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |
  import org.springframework.web.bind.annotation.RestController;
```

## Inline User Template

`PromptTemplate` is still useful, but for one-off templating at the call site, `ChatClient` accepts a lambda that builds the user message with its own placeholder syntax:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 5
description: Add inline user template
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
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

Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Inline System Prompt

Same idea for the system role. Declare it inline on the request:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 7
description: Apply the inline system prompt
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      String generateResponse(String query) {
          return chatClient.prompt()
                  .system("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.")
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .content();
      }
```

Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Move the System Prompt to a Default

Repeating the system prompt on every call is duplication. Put it on the `ChatClient` bean as a default, and every call through that client picks it up automatically. A per-call `.system(...)` would still win if you ever need to override.

Update the bean in `SupportAssistantConfiguration`:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 8
description: Apply default system prompt on the ChatClient bean
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
cascade: true
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

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "return builder.build();"
before: 0
after: 0
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
          return builder
                  .defaultSystem("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.")
                  .build();
```

The `.system(...)` line is dropped from the service at the same time.

## Move the System Prompt to a File

Keeping the prompt text inside Java code means every wording change needs a recompile. A cleaner option is to keep the prompt in a plain text file under `src/main/resources`. You can then inject that file with `@Value` as a `Resource` and hand it straight to `defaultSystem`.

Create the prompt file under the resources folder:
```terminal:execute
command:  mkdir -p ~/sample-app/src/main/resources/prompts
cascade: true
description: Create the system prompt resource file
```

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/prompts/system-prompt.st
hidden: true
text: |
  You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.
```

Inject the resource into the bean and pass it to `defaultSystem`:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder) {"
before: 0
after: 0
description: Load the system prompt from a resource file
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
      public ChatClient chatClient(ChatClient.Builder builder,
              @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt) {
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: '.defaultSystem("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.")'
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
                  .defaultSystem(systemPrompt)
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 3
hidden: true
text: |-
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.core.io.Resource;
```

Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Access the Full Response

`.content()` is a shortcut for the text. When you also want metadata (token counts for billing, finish reason, model id, ...), ask for the full `ChatResponse` instead:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 7
description: Get access to the full ChatResponse
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
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

Test, then check the logs in the second terminal for the full `ChatResponse` with its usage metadata:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Summary

You've switched to the fluent `ChatClient`, a configured bean with shared defaults (`defaultSystem`), inline user/system prompts, a quick look at streaming, and access to the full `ChatResponse`.

One more feature to go, the structured output parsing.
