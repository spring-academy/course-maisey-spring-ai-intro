---
title: Testing
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

## Verify the Existing Provider Test

Stop any running application, then confirm the baseline test passes:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=AiProviderConfigurationTest 2>&1 | tail -20
session: 2
```

This test verifies that the `ChatClient` bean is configured and the AI provider responds.

## Create a RAG Relevancy Evaluation Test

This test uses `RelevancyEvaluator` — an LLM-as-judge — to assert that the RAG response is relevant to the retrieved context:

```editor:append-lines-to-file
file: ~/sample-app/src/test/java/com/example/supportassistant/RagEvaluationTest.java
description: Create RAG evaluation test
text: |
  package com.example.supportassistant;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.model.ChatResponse;
  import org.springframework.ai.evaluation.EvaluationRequest;
  import org.springframework.ai.evaluation.EvaluationResponse;
  import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
  import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
  import org.springframework.ai.vectorstore.VectorStore;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
  class RagEvaluationTest {

      @Autowired
      private ChatClient chatClient;

      @Autowired
      private ChatClient.Builder chatClientBuilder;

      @Autowired
      private VectorStore vectorStore;

      @Test
      void ragResponseIsRelevantToRetrievedContext() {
          String question = "What are the key features of Tanzu Spring?";

          ChatResponse chatResponse = chatClient.prompt()
                  .user(question)
                  .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                  .call()
                  .chatResponse();

          EvaluationRequest evaluationRequest = new EvaluationRequest(
                  question,
                  chatResponse.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS),
                  chatResponse.getResult().getOutput().getText()
          );

          RelevancyEvaluator evaluator = new RelevancyEvaluator(chatClientBuilder);
          EvaluationResponse evaluationResponse = evaluator.evaluate(evaluationRequest);

          assertThat(evaluationResponse.isPass())
                  .as("RAG response should be relevant to the retrieved context")
                  .isTrue();
      }
  }
```

## Create a Basic Response Quality Test

For simple quality checks that don't need an LLM judge, assert on semantics rather than exact strings:

```editor:append-lines-to-file
file: ~/sample-app/src/test/java/com/example/supportassistant/ChatResponseTest.java
description: Create response quality test
text: |
  package com.example.supportassistant;

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

## Run All Tests

```terminal:execute
command: cd ~/sample-app && ./mvnw test 2>&1 | tail -30
session: 2
```

All tests run against the mock provider — fast and deterministic. This is the right setup for local development and CI pipelines.
