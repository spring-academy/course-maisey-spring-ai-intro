---
title: Simple Chat
---

## Optional: Configure AI Provider

By default this lab uses the built-in **mock** provider — no API key needed.

To switch to real OpenAI models, set your API key:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 1
```

Then activate the OpenAI profile:

```terminal:execute
command: export SPRING_PROFILES_ACTIVE=openai
session: 1
```

---

## Create the ChatController

Create the chat module package:

```terminal:execute
command: mkdir -p ~/sample-app/src/main/java/com/example/supportassistant/chat
session: 1
```

Add a `ChatController` with a simple blocking endpoint:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
description: Add ChatController
text: |
  package com.example.supportassistant.chat;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  @RequestMapping("/chat")
  public class ChatController {

      private final ChatClient chatClient;

      public ChatController(ChatClient chatClient) {
          this.chatClient = chatClient;
      }

      @GetMapping("/simple")
      public String simpleChat(
              @RequestParam(defaultValue = "What is Tanzu Spring?") String query) {

          return chatClient.prompt()
                  .user(query)
                  .call()
                  .content();
      }
  }
```

## Start and Test

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" before sending requests.
{{< /note >}}

```execute
http -b localhost:8080/chat/simple
```

```execute
http -b localhost:8080/chat/simple query=="What support options are available for Spring?"
```

## Add Streaming

Add the import and a streaming endpoint:

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
line: 8
description: Add streaming endpoint
cascade: true
text: import reactor.core.publisher.Flux;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
line: 29
hidden: true
text: |2

      @GetMapping(value = "/stream", produces = "text/event-stream")
      public Flux<String> streamChat(
              @RequestParam(defaultValue = "What is Tanzu Spring?") String query) {

          return chatClient.prompt()
                  .user(query)
                  .stream()
                  .content();
      }
```

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -N http://localhost:8080/chat/stream
```

## Expose Full Response Metadata

Add a third endpoint that returns the complete `ChatResponse`, including token usage:

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
line: 9
description: Add detailed response endpoint
cascade: true
text: import org.springframework.ai.chat.model.ChatResponse;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
line: 40
hidden: true
text: |2

      @GetMapping("/detailed")
      public ChatResponse detailedChat(
              @RequestParam(defaultValue = "What is Tanzu Spring?") String query) {

          return chatClient.prompt()
                  .user(query)
                  .call()
                  .chatResponse();
      }
```

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http -b localhost:8080/chat/detailed | jq
```

Inspect the `metadata.usage` field to see the token counts for this request.

## Stop the Application

```terminal:interrupt
session: 2
```
