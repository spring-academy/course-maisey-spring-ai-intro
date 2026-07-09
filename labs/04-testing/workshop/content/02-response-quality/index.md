---
title: Basic Response Quality Test
---

Start with the simplest possible check: did the model respond at all, and does the response mention the things you'd expect for the question? Asserting on *exact strings* is brittle, a paraphrase will flip the test red even when the answer is fine. Assert on **concepts** instead.

## Create the Test

Create a test class with two tests against the application's `ChatClient` bean:

```editor:append-lines-to-file
file: ~/sample-app/src/test/java/com/example/support_assistant/ChatResponseTest.java
description: Create ChatResponseTest.java
text: |
  package com.example.support_assistant;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.ai.chat.client.ChatClient;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
  class ChatResponseTest {

      @Autowired
      private ChatClient chatClient;

      @Test
      void responseIsNotEmpty() {
          String response = chatClient.prompt()
                  .user("Tell me about Spring AI")
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

Two patterns to notice:

- **`isNotNull().isNotBlank()`** Catches outright API failures and empty responses.
- **`satisfiesAnyOf(...)`** The test passes as long as *at least one* of the expected concepts appears. Any decent answer to "Tell me about Spring AI" mentions at least one of spring / java / ai / abstraction, regardless of exact wording.

## Run It

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=ChatResponseTest
session: 2
```

Wait for `BUILD SUCCESS` — both tests should pass.

## Summary

You've written a smoke test and a semantic assertion that survive paraphrasing. In the next step you will look at the harder question of whether a RAG answer is actually grounded in the retrieved documents.
