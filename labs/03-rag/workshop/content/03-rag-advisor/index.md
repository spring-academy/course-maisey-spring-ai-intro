---
title: Retrieval with Advisors
---

Spring AI's `QuestionAnswerAdvisor` plugs retrieval into the existing `ChatClient` chain. It runs a similarity search against the `VectorStore` before the model call and appends the matches to the prompt. 

## Add the QuestionAnswerAdvisor

Inject the `VectorStore` into `SupportAssistantService`:
```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
description: Inject the `VectorStore`
cascade: true
text: |-
  import org.springframework.ai.vectorstore.VectorStore;
```
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final ChatClient chatClient;"
before: 0
after: 0
cascade: true
hidden: true
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
hidden: true
text: |2
      SupportAssistantService(ChatClient chatClient, VectorStore vectorStore) {
          this.chatClient = chatClient;
          this.vectorStore = vectorStore;
      }
```

Configure and add the QuestionAnswerAdvisor to the ChatClient interaction:
```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
description: Configure and add the QuestionAnswerAdvisor
cascade: true
text: |-
  import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
  import org.springframework.ai.vectorstore.SearchRequest;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query, String conversationId) {"
before: 0
after: 8
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query, String conversationId) {
          var ragSearchRequest = SearchRequest.builder().topK(4).similarityThreshold(0.4).build();
          var ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                  .searchRequest(ragSearchRequest)
                  .build();

          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .advisors(ragAdvisor)
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                  .call()
                  .entity(SupportResponse.class);
      }
```

The `SearchRequest` defines the retrieval. The top 4 most similar chunks, and only if they pass a 0.4 cosine-similarity threshold.

## Test the Grounded Assistant

Try questions your knowledge base covers:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does VMware Tanzu Spring provide commercial support for Micrometer?"
```

Now try one it doesn't:

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about breaking changes in Spring Framework 7"
```

You'll get something like:

> I can’t answer that from the provided context ...

That's the advisor's **default prompt**, which instructs the model to refuse anything outside the retrieved context. Strict, and often what you want. For our assistant, though, we'd rather fall back to the model's general knowledge when the knowledge base has nothing.

## Customize the RAG Prompt

Override the advisor's prompt with our own template. The two placeholders are filled by the advisor: `{query}` with the user's question, `{question_answer_context}` with the retrieved chunks:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/prompts/rag-prompt.st
description: Create the custom RAG prompt
text: |
  Use the following retrieved context to answer the user's question. Follow these rules:

  1. If the answer can be found in the context, base your answer strictly on that context.
  2. If the context does not contain the information needed to answer, rely on your own general knowledge to answer.
  3. If you are unsure or the question cannot be answered from either the context or your own knowledge, say so clearly rather than guessing.
  4. Do not fabricate facts, sources, or citations.

  ---
  Context:
  {question_answer_context}
  ---

  Question:
  {query}
```

The key change is the closing line: "based on the context **if possible**". That gives the model permission to fall back to general knowledge when retrieval comes up empty, while still preferring the context when it has relevant material.

Inject the resource into the service and pass it to the advisor as a `PromptTemplate`:
```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
line: 3
description: Use the custom RAG prompt
cascade: true
text: |-
  import org.springframework.ai.chat.prompt.PromptTemplate;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.core.io.Resource;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "private final VectorStore vectorStore;"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
cascade: true
hidden: true
text: |2
      private final VectorStore vectorStore;

      @Value("classpath:/prompts/rag-prompt.st")
      private Resource ragPromptResource;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query, String conversationId) {"
before: 0
after: 14
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query, String conversationId) {
          var ragSearchRequest = SearchRequest.builder().topK(4).similarityThreshold(0.4).build();
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
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                  .call()
                  .entity(SupportResponse.class);
      }
```

Re-run both queries:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Does VMware Tanzu Spring provide commercial support for Micrometer?"
```

```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about breaking changes in Spring Framework 7"
```

The Tanzu question still comes back grounded in the indexed docs, the Spring AI question now gets a real answer instead of a refusal.
