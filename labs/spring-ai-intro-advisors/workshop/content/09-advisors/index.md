---
title: Advisors & Agentic Patterns
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

## Add Conversation Memory to the Chat Endpoint

Right now each request to `/chat/support` is stateless — the model has no memory of previous turns. Add `MessageChatMemoryAdvisor` to maintain conversation history per session.

First, wire `InMemoryChatMemory` and a memory-backed `ChatClient` into the configuration:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
text: "import org.springframework.context.annotation.Configuration;"
description: Add chat memory imports
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |
  import org.springframework.context.annotation.Configuration;
  import org.springframework.ai.chat.memory.ChatMemory;
  import org.springframework.ai.chat.memory.InMemoryChatMemory;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
line: 17
description: Add ChatMemory bean
hidden: true
text: |2

      @Bean
      public ChatMemory chatMemory() {
          return new InMemoryChatMemory();
      }
```

Now inject `ChatMemory` into `ChatService` and add the advisor:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "import org.springframework.ai.chat.client.ChatClient;"
description: Add memory advisor import
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
  import org.springframework.ai.chat.memory.ChatMemory;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "private final ChatClient chatClient;"
description: Add ChatMemory field
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
      private final ChatClient chatClient;
      private final ChatMemory chatMemory;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "public ChatService(ChatClient chatClient)"
description: Update constructor to accept ChatMemory
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
      public ChatService(ChatClient chatClient, ChatMemory chatMemory)
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "this.chatClient = chatClient;"
description: Assign chatMemory in constructor
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
          this.chatClient = chatClient;
          this.chatMemory = chatMemory;
```

## Wire the conversationId Through to the Service

Update the `chat` method signature to accept a `conversationId` and attach the memory advisor:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "public SupportChatResponse chat(String userQuery, String customerTier)"
description: Add conversationId parameter and wire memory advisor
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
      public SupportChatResponse chat(String userQuery, String customerTier, String conversationId)
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "return chatClient.prompt()"
description: Add memory advisor to the chat call
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
          return chatClient.prompt()
                  .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
```

Update the controller to pass a `conversationId` request parameter:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
text: "public SupportChatResponse supportChat("
description: Add conversationId parameter to endpoint
before: 0
after: 2
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatController.java
hidden: true
cascade: true
text: |2
      public SupportChatResponse supportChat(
              @RequestParam String query,
              @RequestParam(defaultValue = "Standard") String tier,
              @RequestParam(defaultValue = "default") String conversationId) {

          return chatService.chat(query, tier, conversationId);
```

## Start and Test Conversation Memory

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

Send the first message in conversation `user-1`:

```execute
http -b localhost:8080/chat/support query=="My name is Alice and I need help with a CVE" tier=="Premium" conversationId=="user-1"
```

Send a follow-up message in the same conversation — the model should remember the name:

```execute
http -b localhost:8080/chat/support query=="What was my name?" tier=="Premium" conversationId=="user-1"
```

Send the same follow-up in a different conversation — the model should not know the name:

```execute
http -b localhost:8080/chat/support query=="What was my name?" tier=="Standard" conversationId=="user-2"
```

## Add Debug Logging with SimpleLoggerAdvisor

Add the `SimpleLoggerAdvisor` to see the full request and response in the logs:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: "import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;"
description: Add SimpleLoggerAdvisor import
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |
  import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
  import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
text: ".advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))"
description: Append SimpleLoggerAdvisor
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/chat/ChatService.java
hidden: true
cascade: true
text: |2
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                  .advisors(new SimpleLoggerAdvisor())
```

Enable DEBUG logging for the advisor package:

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application.yaml
text: "spring:"
description: Enable advisor debug logging
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/resources/application.yaml
hidden: true
text: |
  logging:
    level:
      org.springframework.ai.chat.client.advisor: DEBUG
  spring:
```

Restart and make a request — check the terminal for the logged prompt and response:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http -b localhost:8080/chat/support query=="What support plans are available?" tier=="Standard" conversationId=="debug-test"
```

Look at the startup logs in session 2 — you'll see the full prompt including the system message, user message, and the model's response.

## Stop the Application

```terminal:interrupt
session: 2
```
