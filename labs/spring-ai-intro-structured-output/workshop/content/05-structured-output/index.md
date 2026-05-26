---
title: Structured Output
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

## Define the Data Model

Create the response category enum in the chat package:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatResponseCategory.java
description: Add ChatResponseCategory enum
text: |
  package com.example.supportassistant.chat;

  public enum ChatResponseCategory {
      TECHNICAL,
      BILLING,
      SECURITY,
      UPGRADE,
      GENERAL
  }
```

Create the structured response record, using `@JsonPropertyDescription` to guide the model:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/SupportChatResponse.java
description: Add SupportChatResponse record
text: |
  package com.example.supportassistant.chat;

  import com.fasterxml.jackson.annotation.JsonPropertyDescription;

  public record SupportChatResponse(
          @JsonPropertyDescription("The category of the support question: TECHNICAL, BILLING, SECURITY, UPGRADE, or GENERAL")
          ChatResponseCategory category,

          @JsonPropertyDescription("The helpful answer to the customer's question")
          String answer
  ) {}
```

## Switch ChatService to Return Structured Output

Replace `.content()` with `.entity(SupportChatResponse.class)` and update the return type:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: ".content()"
description: Switch to structured output
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
text: .entity(SupportChatResponse.class)
cascade: true
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "public String chat"
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
text: public SupportChatResponse chat
```

## Update the Controller Return Type

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
text: "String supportChat"
description: Change return type to SupportChatResponse
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
hidden: true
text: SupportChatResponse supportChat
```

## Start and Test

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http -b localhost:8080/chat/support query=="What CVEs are covered?" tier=="Premium"
```

You should see a JSON object with `category` and `answer` fields:

```json
{
  "category": "SECURITY",
  "answer": "As a Premium customer, you have access to patches and updates for all CVEs..."
}
```

## Stop the Application

```terminal:interrupt
session: 2
```
