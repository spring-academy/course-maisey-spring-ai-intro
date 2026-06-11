---
title: The ChatModel API
---

# The Low-Level ChatModel API

`ChatModel` is the foundational interface that every chat provider implements (`OpenAiChatModel`, `AnthropicChatModel`, `OllamaChatModel`, ...). Thanks to the starter's auto-configuration, it is already available as a Spring bean. Let's use it.

## Create the Service

Create a service that delegates the user's query to the model. `call(String)` is the convenience overload — Spring AI wraps the string in a `Prompt` for you and unwraps the response back to a `String`:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
description: Create SupportAssistantService
text: |
  package com.example.support_assistant;

  import org.springframework.ai.chat.model.ChatModel;
  import org.springframework.stereotype.Service;

  @Service
  class SupportAssistantService {

      private final ChatModel chatModel;

      SupportAssistantService(ChatModel chatModel) {
          this.chatModel = chatModel;
      }

      String generateResponse(String query) {
          return chatModel.call(query);
      }
  }
```

## Create the Controller

Expose the service via a versioned REST endpoint. The `{version}` path segment is resolved by the API versioning we saw in `application.properties`:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
description: Create SupportAssistantController
text: |
  package com.example.support_assistant;

  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  class SupportAssistantController {

      private final SupportAssistantService service;

      SupportAssistantController(SupportAssistantService service) {
          this.service = service;
      }

      @GetMapping(path = "/api/{version}/chat")
      String chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
  }
```

Restart the application:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

And try it:

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

You should get back a plain-text answer. Everything from here on is changes to `generateResponse` (and a few extras).

## Add a System Message

A raw `String` hides the message roles. Under the hood, a `Prompt` holds an ordered list of `Message` objects with roles: the **system** role shapes the model's tone and scope, the **user** role carries the question. Let's steer the model with a `SystemMessage` using the multi-message overload:

```java
String generateResponse(String query) {
    return chatModel.call(
            new SystemMessage("You are a Spring and AI expert."),
            new UserMessage(query));
}
```

Click below to apply the change (the required imports are added automatically):

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "return chatModel.call(query);"
before: 0
after: 0
description: Apply - add a system message
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
          return chatModel.call(
                  new SystemMessage("You are a Spring and AI expert."),
                  new UserMessage(query));
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.chat.messages.SystemMessage;
  import org.springframework.ai.chat.messages.UserMessage;
```

Restart and test — the answers now reflect the expert persona:

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

## Use a PromptTemplate for the User Message

In real apps the user message is rarely a raw string — it's a template filled with runtime data. `PromptTemplate` lets you write a message with `{placeholder}` variables and fill them in at call time, keeping the wording in one place:

```java
String generateResponse(String query) {
    var userPromptTemplate = PromptTemplate.builder()
            .template("Answer the following question with a short, well-structured explanation: {question}")
            .variables(Map.of("question", query))
            .build();
    var userMessage = userPromptTemplate.createMessage();

    return chatModel.call(new SystemMessage("You are a Spring and AI expert."), userMessage);
}
```

Click to apply:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "return chatModel.call("
before: 0
after: 2
description: Apply - templated user message
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
          var userPromptTemplate = PromptTemplate.builder()
                  .template("Answer the following question with a short, well-structured explanation: {question}")
                  .variables(Map.of("question", query))
                  .build();
          var userMessage = userPromptTemplate.createMessage();

          return chatModel.call(new SystemMessage("You are a Spring and AI expert."), userMessage);
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.chat.prompt.PromptTemplate;
  import java.util.Map;
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

## Full Prompt with ChatOptions and ChatResponse

Sometimes you need to override the model or sampling for a single call, or you want the metadata that comes back with the answer. Wrap the messages in a `Prompt` together with `ChatOptions`, and unwrap the full `ChatResponse`:

```java
String generateResponse(String query) {
    var userPromptTemplate = PromptTemplate.builder()
            .template("Answer the following question with a short, well-structured explanation: {question}")
            .variables(Map.of("question", query))
            .build();
    var userMessage = userPromptTemplate.createMessage();

    var prompt = new Prompt(
            List.of(new SystemMessage("You are a Spring and AI expert."), userMessage),
            ChatOptions.builder().model("gpt-5.4-mini").temperature(0.0).build());

    var chatResponse = chatModel.call(prompt);
    log.info("Chat Response: {}", chatResponse);
    return chatResponse.getResult().getOutput().getText();
}
```

Click to apply (this also adds a `log` field and the imports):

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatModel chatModel;"
before: 0
after: 0
description: Apply - full Prompt with ChatOptions and ChatResponse
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private static final Logger log = LoggerFactory.getLogger(SupportAssistantService.class);

      private final ChatModel chatModel;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "return chatModel.call(new SystemMessage("
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
          var prompt = new Prompt(
                  List.of(new SystemMessage("You are a Spring and AI expert."), userMessage),
                  ChatOptions.builder().model("gpt-5.4-mini").temperature(0.0).build());

          var chatResponse = chatModel.call(prompt);
          log.info("Chat Response: {}", chatResponse);
          return chatResponse.getResult().getOutput().getText();
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.ai.chat.prompt.ChatOptions;
  import org.springframework.ai.chat.prompt.Prompt;
  import java.util.List;
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

The application logs in the second terminal now include the full `ChatResponse`, with metadata such as the model that served the request and the token usage. Providers bill per token, so `chatResponse.getMetadata().getUsage()` is the foundation for cost monitoring.

## Summary

You've used the low-level `ChatModel` API: plain string calls, explicit `SystemMessage`/`UserMessage` roles, reusable `PromptTemplate`s, and the full `Prompt`/`ChatOptions`/`ChatResponse` round trip.

`ChatModel` gives you full, explicit control — but everyday code reads better with the fluent `ChatClient`. That's next.
