---
title: Getting Started
---

In this lab, you'll add **Retrieval Augmented Generation (RAG)** to the support assistant: index a small Markdown knowledge base, retrieve the most relevant chunks per query, and have the model answer grounded in them.

Your starting point in `~/sample-app` is the assistant from the **Spring AI fundamentals** lab, a `ChatClient` with a default system prompt and a `/api/v1/chat` endpoint returning a structured `SupportResponse` record.

{{< note >}}
Every call to OpenAI in this lab is mocked. The application sends its requests to a local mock server that returns predefined responses, so you do not need a real API key. The application code stays exactly the same as it would be against the real OpenAI service.
{{< /note >}}

## Add the RAG Dependencies

To keep things simple, this lab uses an in memory vector store. For production you would normally use an external service instead, which needs an extra dependency such as `spring-ai-starter-vector-store-pgvector`. Embedding models can also need their own dependency depending on the provider. For example `spring-ai-starter-model-bedrock-converse` covers chat but does **not** ship an embedding model, so on AWS Bedrock you would add the broader `spring-ai-starter-model-bedrock` starter next to it. Some providers such as Anthropic do not offer an embedding model at all, so there you would use a different provider for the embeddings.

Two additional Spring AI modules are needed.

- `spring-ai-vector-store-advisor` connects the vector store to the chat pipeline. Before the model is called it searches the store for the content that is most relevant to the user question and adds that content to the prompt, so the answer is grounded in your own data.
- `spring-ai-markdown-document-reader` reads Markdown files and splits them into smaller pieces of text that can be stored in the vector store.

```editor:insert-lines-before-line
file: ~/sample-app/pom.xml
line: 46
description: Add the RAG dependencies to pom.xml
text: |2

  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-vector-store-advisor</artifactId>
  		</dependency>
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-markdown-document-reader</artifactId>
  		</dependency>
```

## Configure the Embedding Model

For most providers the embedding settings live in the same namespace as the chat settings, under `spring.ai.<provider>.embedding.*`. Some providers are an exception. On AWS Bedrock for example the chat model and the embedding model can come from different starters, so their settings live under different namespaces. Because the sample app has several provider starters on the classpath, `spring.ai.model.embedding` explicitly picks which one serves the embedding model, just like `spring.ai.model.chat` already does for chat.

Append the OpenAI embedding configuration:
```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Configure the OpenAI embedding model
text: |
  spring.ai.openai.embedding.model=text-embedding-3-small
```

## Provide a VectorStore Bean
As already mentioned, this lab uses the in memory `SimpleVectorStore`. This store is not provided by auto-configuration, so we create the bean ourselves in `SupportAssistantConfiguration`. This step is only needed for the in memory store. External stores such as pgvector are auto-configured by their own starter, so you would not have to define the bean yourself.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
description: Add the SimpleVectorStore bean
cascade: true
line: 18
text: |2

      @ConditionalOnMissingBean(VectorStore.class)
      @Bean
      VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
          return SimpleVectorStore.builder(embeddingModel).build();
      }
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 3
hidden: true
text: |-
  import org.springframework.ai.embedding.EmbeddingModel;
  import org.springframework.ai.vectorstore.SimpleVectorStore;
  import org.springframework.ai.vectorstore.VectorStore;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
```

`@ConditionalOnMissingBean` makes this bean drop out the moment a real `VectorStore` shows up. For example, if you added the `spring-ai-starter-vector-store-pgvector` starter, its auto-configured PostgreSQL-backed store would take over. Note the `EmbeddingModel` parameter, the store uses it to turn documents into vectors.

## Run the App

Start the application (the first run downloads the new dependencies):

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before continuing.
{{< /note >}}

Smoke-check the actuator to confirm everything wired up:

```execute
curl http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`. Keep the app running in the second terminal.


## Summary

You've added necessary RAG dependencies, configured the OpenAI embedding model declaratively, and provided an in-memory `VectorStore`. In the next section, you'll fill it with knowledge.
