---
title: RAG Relevancy Evaluation
---

Here is the interesting question. When the assistant answers using retrieved context, **is that answer actually backed by the retrieved chunks**? Or did the model hallucinate something that is only loosely related?

You can't write a regex for that. Spring AI's `RelevancyEvaluator` is an "LLM as judge". It takes the question, the retrieved documents, and the response, and asks another model call whether the response is really grounded in those documents. It returns pass or fail.

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
  import org.springframework.ai.chat.prompt.ChatOptions;
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
          var question = "What are the key features of VMware Tanzu Spring?";

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
          var evaluatorChatClientBuilder = chatClientBuilder.defaultOptions(ChatOptions.builder().model("gpt-5.4-nano"));
          var evaluator = new RelevancyEvaluator(evaluatorChatClientBuilder);
          var evaluationResponse = evaluator.evaluate(evaluationRequest);

          assertThat(evaluationResponse.isPass())
                  .as("RAG response should be relevant to the retrieved context")
                  .isTrue();
      }
  }
```

Here is what happens, step by step.

1. **Run the RAG query** with the same `ChatClient` and `QuestionAnswerAdvisor` your real service uses. Ask for `.chatResponse()` and not `.content()`, because you need the metadata.
2. **Pull the retrieved documents** out of the response metadata under `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`. These are the chunks the advisor fetched from the vector store before asking the model.
3. **Build an `EvaluationRequest`** from the question, the retrieved context, and the generated answer.
4. **Ask the `RelevancyEvaluator`** to judge. It is just another `ChatClient` call under the hood, using the same `ChatClient.Builder` you injected, with a built in prompt that asks "given this context, is this answer relevant?"
5. **Assert the verdict**.

The `KnowledgeBaseIndexer` reindexes the Markdown knowledge base into the in-memory vector store on every application start, including the test's `@SpringBootTest` context, so the test always has fresh data. There is no extra setup.

## Run It

```terminal:execute
command: cd ~/sample-app && ./mvnw test -Dtest=RagEvaluationTest
session: 2
```

{{< note >}}
Here the answer and the judgment use two different models. The answer comes from the default model of your injected `ChatClient`, and the judge uses `gpt-5.4-nano`, which you set with `.defaultOptions(...)` on the builder before you pass it into `RelevancyEvaluator`. Using a separate model for the judge is a good practice, and a smaller and cheaper model is often good enough to grade a response. You can also send the judge call to a completely different AI provider, which is supported by Spring AI. 
{{< /note >}}

## Run the Whole Suite

```terminal:execute
command: cd ~/sample-app && ./mvnw test
session: 2
```

You'll see both classes execute.