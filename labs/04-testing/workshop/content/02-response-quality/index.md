---
title: Basic Response Quality Test
---

Start with the simplest possible check: did the model respond at all, and does the response mention the things you'd expect for the question? 

Asserting on *exact strings* is brittle, a paraphrase will flip the test red even when the answer is fine. Assert on **concepts** instead.

## Create the Test

Create a test class with two tests against the application's `ChatClient` bean:
```terminal:execute
command: mkdir -p ~/sample-app/src/test/java/com/example/support_assistant
session: 1
description: Create ChatResponseTest.java
cascade: true
```

```editor:append-lines-to-file
file: ~/sample-app/src/test/java/com/example/support_assistant/ChatResponseTest.java
hidden: true
text: |
  package com.example.support_assistant;

  import org.junit.jupiter.api.Test;
  import org.springframework.ai.chat.memory.ChatMemory;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.ai.chat.client.ChatClient;

  import java.util.UUID;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
  class ChatResponseTest {

      @Autowired
      private ChatClient chatClient;

      @Test
      void responseIsNotEmpty() {
          String response = chatClient.prompt()
                  .user("Tell me about Spring AI")
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                  .call()
                  .content();

          assertThat(response)
                  .isNotNull()
                  .isNotBlank();
      }

      @Test
      void responseContainsRelevantConcepts() {
          String response = chatClient.prompt()
                  .user("Tell me about Spring AI")
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                  .call()
                  .content();

          assertThat(response.toLowerCase())
                  .satisfiesAnyOf(
                          r -> assertThat(r).contains("spring"),
                          r -> assertThat(r).contains("java"),
                          r -> assertThat(r).contains("ai"),
                          r -> assertThat(r).contains("abstraction")
                  );
      }
  }
```

Two patterns to notice.
```editor:select-matching-text
file: ~/sample-app/src/test/java/com/example/support_assistant/ChatResponseTest.java
text: "assertThat(response)"
before: 0
after: 2
description: "isNotNull().isNotBlank()"
```

The first test only checks that something came back. It catches outright API failures and empty responses.

```editor:select-matching-text
file: ~/sample-app/src/test/java/com/example/support_assistant/ChatResponseTest.java
text: ".satisfiesAnyOf("
before: 0
after: 5
description: "satisfiesAnyOf(...)"
```

The second test passes as long as *at least one* of the expected concepts appears. Any decent answer to "Tell me about Spring AI" mentions at least one of spring, java, ai, or abstraction, regardless of exact wording.

## Run It

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=ChatResponseTest
session: 2
```

Wait for `BUILD SUCCESS` — both tests should pass.
