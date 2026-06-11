---
title: Basic Response Quality Test
---

Start with the simplest possible check: did the model respond at all, and does the response mention the things you'd expect for the question? Asserting on *exact strings* is brittle — a paraphrase will flip the test red even when the answer is fine. Assert on **concepts** instead.

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
                  .user("What is Spring Boot?")
                  .call()
                  .content();

          assertThat(response)
                  .isNotNull()
                  .isNotBlank();
      }

      @Test
      void responseContainsRelevantConcepts() {
          String response = chatClient.prompt()
                  .user("What is Spring Boot?")
                  .call()
                  .content();

          assertThat(response.toLowerCase())
                  .satisfiesAnyOf(
                          r -> assertThat(r).contains("framework"),
                          r -> assertThat(r).contains("java"),
                          r -> assertThat(r).contains("application"),
                          r -> assertThat(r).contains("spring")
                  );
      }
  }
```

Two patterns to notice:

- **`isNotNull().isNotBlank()`** — the smoke test. Catches outright API failures and empty responses.
- **`satisfiesAnyOf(...)`** — the semantic assertion. The test passes as long as *at least one* of the expected concepts appears. Any decent answer to "What is Spring Boot?" mentions at least one of framework / java / application / spring, regardless of exact wording.

## Run It

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=ChatResponseTest
session: 2
```

Wait for `BUILD SUCCESS` — both tests should pass.

{{< note >}}
Each test hits the real OpenAI API and burns tokens. Cheap, but it's still cost — pin the cheapest model in a test profile (`spring.ai.openai.chat.model=...`) if you want to keep bills predictable. The same applies to Anthropic and Amazon Bedrock. With **Ollama** there's no cost since the model runs locally, but the tests are slower.
{{< /note >}}

These tests don't touch the database or the vector store, so the same code passes regardless of whether you're on the in-memory `SimpleVectorStore` (default) or the commented-out PostgreSQL/pgvector setup.

## Summary

You've written a smoke test and a semantic assertion that survive paraphrasing. Next: the harder question — is a RAG answer actually grounded in the retrieved documents?
