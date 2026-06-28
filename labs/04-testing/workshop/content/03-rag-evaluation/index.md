---
title: RAG Relevancy Evaluation
---

The interesting question: when the assistant answers using retrieved context, **is that answer actually backed by the retrieved chunks**? Or did the model hallucinate something only loosely related?

You can't write a regex for that. Spring AI's `RelevancyEvaluator` is an "LLM-as-judge": it takes the question, the retrieved documents, and the response, and asks another model call whether the response is genuinely grounded in those documents. It returns pass/fail.

## Create the Test

```editor:append-lines-to-file
file: ~/sample-app/src/test/java/com/example/support_assistant/RagEvaluationTest.java
description: Create RagEvaluationTest.java
text: |
  package com.example.support_assistant;

  import org.junit.jupiter.api.Test;
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
  import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
  import org.springframework.ai.evaluation.EvaluationRequest;
  import org.springframework.ai.vectorstore.VectorStore;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;

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

          var chatResponse = chatClient.prompt()
                  .user(question)
                  .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                  .call()
                  .chatResponse();

          var evaluationRequest = new EvaluationRequest(
                  question,
                  chatResponse.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS),
                  chatResponse.getResult().getOutput().getText()
          );

          var evaluator = new RelevancyEvaluator(chatClientBuilder);
          var evaluationResponse = evaluator.evaluate(evaluationRequest);

          assertThat(evaluationResponse.isPass())
                  .as("RAG response should be relevant to the retrieved context")
                  .isTrue();
      }
  }
```

What's happening, step by step:

1. **Run the RAG query** — same `ChatClient` + `QuestionAnswerAdvisor` your real service uses. Ask for `.chatResponse()` (not `.content()`) because we need the metadata.
2. **Pull the retrieved documents** out of the response metadata under `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`. These are the chunks the advisor fetched from the vector store before asking the model.
3. **Build an `EvaluationRequest`** — question, retrieved context, generated answer.
4. **Ask the `RelevancyEvaluator`** to judge. It's just another `ChatClient` call under the hood, using the same `ChatClient.Builder` you injected, with a built-in prompt that asks "given this context, is this answer relevant?"
5. **Assert the verdict**.

The `KnowledgeBaseIndexer` reindexes the Markdown knowledge base into the in-memory vector store on every application start — including the test's `@SpringBootTest` context — so the test always has fresh data. No extra setup.

## Run It

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=RagEvaluationTest
session: 2
```

{{< note >}}
The judge runs on whatever provider your `ChatClient.Builder` is wired to — the same one that generated the answer. That's usually fine: a question asked of `gpt-5.4-mini` is judged by `gpt-5.4-mini`. If you want an independent judge (best practice for production-grade evals), build a second `ChatClient` from a different provider's `ChatModel` (e.g. Anthropic or Amazon Bedrock) and pass *that* builder into `RelevancyEvaluator`. With **Ollama** the judge call is local, so cost is zero — but it's two LLM calls per test, so plan for the runtime to roughly double.
{{< /note >}}

{{< note >}}
If you switched to the (commented-out) PostgreSQL/pgvector setup, `spring.ai.vectorstore.pgvector.remove-existing-vector-store-table=true` drops and recreates the table on context startup, so each test run starts clean. If you've turned that flag off for production, your test will keep accumulating chunks — use `@DirtiesContext` or a dedicated test profile that re-enables the flag.
{{< /note >}}

## Run the Whole Suite

```terminal:execute
command: cd ~/sample-app && ./mvnw test
session: 2
```

You'll see both classes execute. The relevancy test is materially slower (it's two LLM calls instead of one), so it's worth tagging if you want to skip it in tight loops — e.g., put `@Tag("eval")` on `RagEvaluationTest` and configure Surefire to exclude that tag by default.

## Summary

| Step | What changed | Key API |
|------|--------------|---------|
| 1 | Test deps already present | `spring-boot-starter-webmvc-test` |
| 2 | Semantic smoke test | `satisfiesAnyOf(...)` over concepts |
| 3 | LLM-as-judge evaluation | `RelevancyEvaluator`, `EvaluationRequest`, `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS` |
