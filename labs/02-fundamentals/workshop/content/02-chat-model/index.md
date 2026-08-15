---
title: The Low-Level ChatModel API
---

The starter already put a `ChatModel` bean in your application context, so you can inject it and send your first request. In this step you build up a call to that bean piece by piece, from a plain string to a full `Prompt` with options.

## Create the Service

Create a service that passes the user's query to the model with the `call(String)` overload.

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
description: Create SupportAssistantService
text: |
  package com.example.support_assistant;

  import org.springframework.stereotype.Service;
  import org.springframework.ai.chat.model.ChatModel;

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

Expose the service through a versioned REST endpoint. The `v{version}` path segment is resolved by the API versioning you saw in `application.properties`.

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

      @GetMapping(path = "/api/v{version}/chat")
      String chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
  }
```

Try it:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

You should get back a plain-text answer. From here on you only change `generateResponse`, with a few extras.

## Add a System Message

A plain string is always a user message. To give the assistant a persona, send a `SystemMessage` next to the `UserMessage` with the multi-message overload.
```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 4
description: Add a system message
cascade: true
text: |-
  import org.springframework.ai.chat.messages.SystemMessage;
  import org.springframework.ai.chat.messages.UserMessage;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "return chatModel.call(query);"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
          return chatModel.call(
                  new SystemMessage("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs."),
                  new UserMessage(query));
```

The answers now reflect the expert persona:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Use a PromptTemplate for the User Message

The question of the user is only one part of the message you send. Build the rest of it with a `PromptTemplate`, which fills the `{question}` placeholder at call time.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 4
description: Apply templated user message
cascade: true
text: |-
  import org.springframework.ai.chat.prompt.PromptTemplate;
  import java.util.Map;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "return chatModel.call("
before: 0
after: 2
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
          var userPromptTemplate = PromptTemplate.builder()
                  .template("Answer the following question with a short, well-structured explanation: {question}")
                  .variables(Map.of("question", query))
                  .build();
          var userMessage = userPromptTemplate.createMessage();

          return chatModel.call(new SystemMessage("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs."), userMessage);
```

Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

## Full Prompt with ChatOptions and ChatResponse

To override the model for this one call and to see what comes back besides the text, wrap the messages in a `Prompt` together with `ChatOptions` and read the full `ChatResponse`. Remember that the low-level API needs the options builder of the provider, so the code uses `OpenAiChatOptions.builder()`.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 4
description: Add full Prompt with ChatOptions and ChatResponse
cascade: true
text: |-
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.ai.openai.OpenAiChatOptions;
  import org.springframework.ai.chat.prompt.Prompt;
  import java.util.List;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatModel chatModel;"
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
hidden: true
text: |2
          var prompt = new Prompt(
                  List.of(new SystemMessage("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs."), userMessage),
                  OpenAiChatOptions.builder().model("gpt-5.4-mini").temperature(0.0).build());

          var chatResponse = chatModel.call(prompt);
          log.info("Chat Response: {}", chatResponse);
          return chatResponse.getResult().getOutput().getText();
```

Verify the change took effect by calling the service:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

The application logs in the second terminal now show the full `ChatResponse`. Look for the model that served the request and for the token counts under `usage`, which you would use for cost monitoring.