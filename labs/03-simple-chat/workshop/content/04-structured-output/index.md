---
title: Structured Output
---

# Structured Output with .entity(...)

The most interesting jump. Instead of free-form text, ask the model to return a Java type and let Spring AI handle both the prompting and the deserialization. Behind the scenes, Spring AI instructs the model to respond in a matching schema and deserializes the result for you — no string parsing on your side.

## Define the Response Type

First, create an enum for the category of the support request:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportCategory.java
description: Create SupportCategory enum
text: |
  package com.example.support_assistant;

  enum SupportCategory {
      TECHNICAL,
      BILLING,
      SECURITY,
      GENERAL
  }
```

Then a record for the structured answer. The `@JsonPropertyDescription` annotations are passed to the model as part of the generated schema, guiding what each field should contain:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportResponse.java
description: Create SupportResponse record
text: |
  package com.example.support_assistant;

  import com.fasterxml.jackson.annotation.JsonPropertyDescription;

  record SupportResponse(
          @JsonPropertyDescription("The category of the support question: TECHNICAL, BILLING, SECURITY, or GENERAL")
          SupportCategory category,

          @JsonPropertyDescription("The helpful answer to the customer's question")
          String answer
  ) { }
```

## Return the Record

Change `generateResponse` to return the record via `.entity(...)`:

```java
SupportResponse generateResponse(String query) {
    return chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .call()
            .entity(SupportResponse.class);
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 9
description: Apply - return a SupportResponse entity
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportResponse generateResponse(String query) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .entity(SupportResponse.class);
      }
```

And change the controller method to match:

```java
@GetMapping(path = "/api/{version}/chat", version = "1.0")
SupportResponse chat(@RequestParam String query) {
    return service.generateResponse(query);
}
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: '@GetMapping(path = "/api/{version}/chat")'
before: 0
after: 3
description: Apply - return SupportResponse from the controller
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
cascade: true
hidden: true
text: |2
      @GetMapping(path = "/api/{version}/chat", version = "1.0")
      SupportResponse chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
```

## Restart and Test

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

The same `curl` now returns JSON:

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

```json
{
  "category": "...",
  "answer": "..."
}
```

The model returns structured, type-safe data your application can use directly, rather than free-form prose you'd have to parse yourself. This is the building block we'll expand on in the dedicated **Structured Output** section of the course.

## Recap

| Step | What changed | Key API |
|------|--------------|---------|
| 1 | Add system message | `SystemMessage`, `UserMessage` |
| 2 | Templated user message | `PromptTemplate` |
| 3 | Per-call options + metadata | `Prompt`, `ChatOptions`, `ChatResponse` |
| 4 | Fluent API | `ChatClient.prompt().user(...).call().content()` |
| 5 | Streaming endpoint | `.stream()`, `Flux<String>`, SSE |
| 6 | Inline user template | `.user(u -> u.text(...).param(...))` |
| 7 | Inline system prompt | `.system(...)` |
| 8 | Default system prompt | `ChatClient.Builder#defaultSystem` |
| 9 | Access full response | `.call().chatResponse()` |
| 10 | Structured output | `.call().entity(Class)` |

You now have the core mental model in practice: `ChatModel` is the portable contract over provider REST APIs, and `ChatClient` is the fluent, batteries-included API you'll reach for in everyday application code.
