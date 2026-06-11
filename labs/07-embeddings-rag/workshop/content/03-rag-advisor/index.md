---
title: Retrieval with Advisors
---

# Retrieval at Query Time

Spring AI's `QuestionAnswerAdvisor` plugs retrieval into the existing `ChatClient` chain — it runs a similarity search against the `VectorStore` before the model call and appends the matches to the prompt. No changes to the controller, no manual prompt stitching.

## Add the QuestionAnswerAdvisor

Inject the `VectorStore` into `SupportAssistantService`:

```java
private final ChatClient chatClient;
private final VectorStore vectorStore;

SupportAssistantService(ChatClient chatClient, VectorStore vectorStore) {
    this.chatClient = chatClient;
    this.vectorStore = vectorStore;
}
```

And add the advisor to `generateResponse`:

```java
SupportResponse generateResponse(String query) {
    var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();
    var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(ragSearchRequest)
            .build();

    return chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .advisors(ragAdvisor)
            .call()
            .entity(SupportResponse.class);
}
```

The `SearchRequest` defines the retrieval: the top 3 most similar chunks, and only if they pass a 0.7 cosine-similarity threshold.

Click to apply (the required imports are added automatically):

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatClient chatClient;"
before: 0
after: 0
description: Apply - add the QuestionAnswerAdvisor
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private final ChatClient chatClient;
      private final VectorStore vectorStore;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportAssistantService(ChatClient chatClient) {"
before: 0
after: 2
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportAssistantService(ChatClient chatClient, VectorStore vectorStore) {
          this.chatClient = chatClient;
          this.vectorStore = vectorStore;
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query) {"
before: 0
after: 8
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportResponse generateResponse(String query) {
          var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();
          var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                  .searchRequest(ragSearchRequest)
                  .build();

          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .advisors(ragAdvisor)
                  .call()
                  .entity(SupportResponse.class);
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
  import org.springframework.ai.vectorstore.SearchRequest;
  import org.springframework.ai.vectorstore.VectorStore;
```

## Test the Grounded Assistant

Restart the application:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

Try a question your knowledge base covers:

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
```

Now try one it doesn't:

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

You'll get something like:

> I can't answer that from the provided context, because it only mentions Tanzu Spring support and Spring Cloud components, not Spring AI.

That's the advisor's **default prompt** at work — it instructs the model to refuse anything outside the retrieved context. Strict, and often what you want. For our assistant, though, we'd rather fall back to the model's general knowledge when the knowledge base has nothing.

## Customize the RAG Prompt

Override the advisor's prompt with our own template. The two placeholders are filled by the advisor: `{query}` with the user's question, `{question_answer_context}` with the retrieved chunks:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/prompts/rag
description: Create the custom RAG prompt
text: |
  {query}

  Context information is below, surrounded by ---------------------

  ---------------------
  {question_answer_context}
  ---------------------

  Reply to the user based on the context if possible.
```

The key change is the closing line: "based on the context **if possible**". That gives the model permission to fall back to general knowledge when retrieval comes up empty, while still preferring the context when it has relevant material.

Inject the resource into the service and pass it to the advisor as a `PromptTemplate`:

```java
@Value("classpath:/prompts/rag")
private Resource ragPromptResource;
```

```java
SupportResponse generateResponse(String query) {
    var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();
    var promptTemplate = PromptTemplate.builder().resource(ragPromptResource).build();
    var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(ragSearchRequest)
            .promptTemplate(promptTemplate)
            .build();

    return chatClient.prompt()
            .user(u -> u
                    .text("Answer the following question with a short, well-structured explanation: {question}")
                    .param("question", query))
            .advisors(ragAdvisor)
            .call()
            .entity(SupportResponse.class);
}
```

Click to apply:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final VectorStore vectorStore;"
before: 0
after: 0
description: Apply - use the custom RAG prompt
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private final VectorStore vectorStore;

      @Value("classpath:/prompts/rag")
      private Resource ragPromptResource;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query) {"
before: 0
after: 13
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      SupportResponse generateResponse(String query) {
          var ragSearchRequest = SearchRequest.builder().topK(3).similarityThreshold(0.7).build();
          var promptTemplate = PromptTemplate.builder().resource(ragPromptResource).build();
          var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                  .searchRequest(ragSearchRequest)
                  .promptTemplate(promptTemplate)
                  .build();

          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .advisors(ragAdvisor)
                  .call()
                  .entity(SupportResponse.class);
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.chat.prompt.PromptTemplate;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.core.io.Resource;
```

Restart and re-run both queries:

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=What is Tanzu Spring Runtime?"
```

```execute
curl -G "http://localhost:8080/api/1.0/chat" --data-urlencode "query=Tell me about Spring AI"
```

The Tanzu question still comes back grounded in the indexed docs; the Spring AI question now gets a real answer instead of a refusal.

## Recap

| Step | What changed |
|------|--------------|
| 1 | Added `spring-ai-vector-store-advisor` and `spring-ai-markdown-document-reader` |
| 2 | Configured `spring.ai.openai.embedding.*` (other providers: same pattern, own namespace) |
| 3 | Fallback `SimpleVectorStore` bean via `@ConditionalOnMissingBean` |
| 4 | `KnowledgeBaseIndexer` — read Markdown, split into chunks, load into the `VectorStore` |
| 5 | `QuestionAnswerAdvisor` on the `ChatClient` chain |
| 6 | Custom RAG prompt to allow fallback when context is empty |

Your support assistant now answers from your own documents — with the model's general knowledge as a graceful fallback.
