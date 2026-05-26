---
title: Prompt Engineering
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

## Create the System Prompt Template

Create the prompts directory:

```terminal:execute
command: mkdir -p ~/sample-app/src/main/resources/prompts
session: 1
description: Add system prompt template file
cascade: true
```

Add a system prompt template with a `{customerTier}` placeholder:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/prompts/support-system.st
hidden: true
text: |
  You are the Support Assistant, an AI-powered helper for Broadcom Tanzu Spring customers.

  Your responsibilities:
  - Answer questions about Tanzu Spring support offerings
  - Provide information about Spring Boot, Spring Framework, and Spring Cloud
  - Help with CVE and security-related inquiries
  - Assist with support ticket creation and management
  - Guide customers on billing and subscription questions

  Guidelines:
  - Be professional and helpful
  - If you don't know something, say so honestly
  - For urgent production issues, recommend creating a P1 support ticket
  - Always mention relevant documentation links when helpful

  Current context:
  - Customer tier: {customerTier}
```

## Create ChatService

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
description: Add ChatService with system prompt
text: |
  package com.example.supportassistant.chat;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.core.io.Resource;
  import org.springframework.stereotype.Service;

  @Service
  public class ChatService {

      private final ChatClient chatClient;

      @Value("classpath:/prompts/support-system.st")
      private Resource systemPromptResource;

      public ChatService(ChatClient chatClient) {
          this.chatClient = chatClient;
      }

      public String chat(String userQuery, String customerTier) {
          return chatClient.prompt()
                  .system(sys -> sys
                        .text(systemPromptResource)
                        .param("customerTier", customerTier))
                  .user(userQuery)
                  .call()
                  .content();
      }
  }
```

## Add the `/chat/support` Endpoint

Wire the service into `ChatController`:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
text: "public ChatController"
description: Update ChatController to use the new service
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
cascade: true
hidden: true
text: |2
      private final ChatService chatService;

      public ChatController(ChatClient chatClient, ChatService chatService) {
          this.chatService = chatService;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
line: 53
hidden: true
text: |2

      @GetMapping("/support")
      public String supportChat(
              @RequestParam String query,
              @RequestParam(defaultValue = "Standard") String tier) {

          return chatService.chat(query, tier);
      }
```

## Start and Test

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http -b localhost:8080/chat/support query=="What CVEs are covered?" tier=="Premium"
```

```execute
http -b localhost:8080/chat/support query=="How do I upgrade to Spring Boot 3?" tier=="Standard"
```

## Add a Few-Shot Classification Prompt

Create a second template that uses few-shot examples to classify queries:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/prompts/topic-classifier.st
description: Add few-shot prompting example
text: |
  Add a classification for the query to the answer based on these categories:
  - TECHNICAL: Questions about code, configuration, or implementation
  - BILLING: Questions about invoices, payments, or subscriptions
  - SECURITY: Questions about CVEs, vulnerabilities, or patches
  - UPGRADE: Questions about version upgrades or migrations
  - GENERAL: Other questions

  Examples:
  Query: "My Spring Boot app won't start after adding a new dependency"
  Category: TECHNICAL
  Answer: "Please provide me the stack trace"

  Query: "When will I receive my invoice for Q4?"
  Category: BILLING
  Answer: "You will receive your invoice in the second week of Q1"

  Query: "Is there a patch for the latest Log4j vulnerability?"
  Category: SECURITY
  Answer: "Yes, there is a patch available!"

  Query: "How do I migrate from Spring Boot 2.7 to 3.2?"
  Category: UPGRADE
  Answer: "For commercial customers a solution called Spring Application Advisor is available"
```

Wire the classifier prompt into `ChatService`:

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
line: 18
description: Configure classifier system prompt
cascade: true
text: |2

      @Value("classpath:/prompts/topic-classifier.st")
      private Resource classifierPrompt;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
line: 31
hidden: true
text: |2
                  .system(classifierPrompt)
```

## Restart and Verify Classification

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http -b localhost:8080/chat/support query=="What CVEs are covered?" tier=="Premium"
```

```execute
http -b localhost:8080/chat/support query=="How do I upgrade to Spring Boot 3?" tier=="Standard"
```

## Stop the Application

```terminal:interrupt
session: 2
```
