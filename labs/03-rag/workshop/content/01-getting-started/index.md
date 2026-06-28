---
title: Getting Started
---

# Getting Started

In this lab, you'll add **Retrieval Augmented Generation (RAG)** to the support assistant: index a small Markdown knowledge base, retrieve the most relevant chunks per query, and have the model answer grounded in them.

Your starting point in `~/sample-app` is the assistant from the **Simple Chat** lab: a `ChatClient` with a default system prompt and a `/api/v1/chat` endpoint returning a structured `SupportResponse` record.

{{< note >}}
This lab implements RAG with **OpenAI** for both chat and embeddings. The sample app also bundles the starters for Anthropic, Amazon Bedrock, and Ollama — you could switch the chat provider via Spring profiles (e.g. `application-anthropic.properties` with `SPRING_PROFILES_ACTIVE=anthropic`). The default configuration uses OpenAI.
{{< /note >}}

## Add the RAG Dependencies

Two additional Spring AI modules are needed:

- `spring-ai-vector-store-advisor` brings the `QuestionAnswerAdvisor` that plugs retrieval into a `ChatClient` chain.
- `spring-ai-markdown-document-reader` parses `.md` files into Spring AI `Document` objects.

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

{{< note >}}
With other providers the dependencies can differ. For example, `spring-ai-starter-model-bedrock-converse` covers chat but does **not** ship an embedding model, so on AWS Bedrock you'd add the broader `spring-ai-starter-model-bedrock` starter alongside it.
{{< /note >}}

## Configure the Embedding Model

Embeddings live in the same provider namespace as chat (`spring.ai.<provider>.embedding.*`). Because the sample app has several provider starters on the classpath, `spring.ai.model.embedding` explicitly picks which one serves the embedding model — just like `spring.ai.model.chat` already does for chat.

Append the OpenAI embedding configuration:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Configure the OpenAI embedding model
text: |

  spring.ai.model.embedding=openai
  spring.ai.openai.embedding.model=text-embedding-3-small
```

For the other providers, the shape is similar — you'd append the matching block to the profile file instead:

- **Ollama**: `spring.ai.ollama.embedding.model=nomic-embed-text-v2-moe` (after `ollama pull nomic-embed-text-v2-moe`)
- **Bedrock**: `spring.ai.model.embedding=bedrock-cohere` with `spring.ai.bedrock.cohere.embedding.model=cohere.embed-multilingual-v3`
- **Anthropic**: doesn't publish an embedding model — you'd pick a separate provider (e.g. Voyage) for embeddings while keeping Anthropic for chat

## Provide a VectorStore Bean

The embedding vectors need a place to live. For this lab we use the in-memory `SimpleVectorStore` — but no auto-configuration provides it, so we create the bean ourselves in `SupportAssistantConfiguration`:

```java
@ConditionalOnMissingBean(VectorStore.class)
@Bean
VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

`@ConditionalOnMissingBean` makes this bean drop out the moment a real `VectorStore` shows up. For example, if you added the `spring-ai-starter-vector-store-pgvector` starter, its auto-configured PostgreSQL-backed store would take over — the same class works in both setups. Note the `EmbeddingModel` parameter: the store uses it to turn documents into vectors.

Click to apply (the required imports are added automatically):

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ChatClient chatClient(ChatClient.Builder builder) {"
before: 0
after: 2
description: Apply - add the SimpleVectorStore bean
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
      ChatClient chatClient(ChatClient.Builder builder) {
          return builder.defaultSystem("You are a support agent for the Spring framework. Answer clearly and always include a link to the relevant official docs when one exists, never inventing URLs.").build();
      }

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

## Set the API Key and Run the App

Set your OpenAI API key (use your own or the one provided by your instructor) — paste it after the `=` and press Enter:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

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

You've added the RAG modules, configured the OpenAI embedding model declaratively, and provided an in-memory `VectorStore`. Next: fill it with knowledge.
