---
title: Embeddings & RAG
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

## Explore the Knowledge Base

Take a look at the documentation files that will become the knowledge base:

```terminal:execute
command: ls -la ~/sample-app/src/main/resources/data/
session: 1
```

```terminal:execute
command: head -50 ~/sample-app/src/main/resources/data/spring-enterprise-support.md
session: 1
```

## Add RAG Dependencies

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "</dependencies>"
description: Add RAG dependencies
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
          <dependency>
              <groupId>org.springframework.ai</groupId>
              <artifactId>spring-ai-advisors-vector-store</artifactId>
          </dependency>

          <dependency>
              <groupId>org.springframework.ai</groupId>
              <artifactId>spring-ai-markdown-document-reader</artifactId>
          </dependency>
      </dependencies>
```

## Configure the Embedding Model and Vector Store

Add the embedding model to the OpenAI configuration:

```editor:select-matching-text
file: ~/sample-app/src/main/resources/application-openai.yaml
text: "model: gpt-4o"
description: Add embedding model configuration
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/resources/application-openai.yaml
hidden: true
before: 0
after: 0
text: |2
            model: gpt-4o
        embedding:
          options:
            model: text-embedding-3-small
```

Add the `SimpleVectorStore` bean to the existing configuration class:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
text: "import org.springframework.context.annotation.Configuration;"
description: Add VectorStore bean
before: 0
after: 0
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |
  import org.springframework.context.annotation.Configuration;
  import org.springframework.ai.embedding.EmbeddingModel;
  import org.springframework.ai.vectorstore.SimpleVectorStore;
  import org.springframework.ai.vectorstore.VectorStore;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/supportassistant/SupportAssistantConfiguration.java
line: 17
hidden: true
text: |2

      @Bean
      public VectorStore vectorStore(EmbeddingModel embeddingModel) {
          return SimpleVectorStore.builder(embeddingModel).build();
      }
```

## Create the DocumentLoader

Create the knowledge module directory:

```terminal:execute
command: mkdir -p ~/sample-app/src/main/java/com/example/supportassistant/knowledge
session: 1
description: Create DocumentLoader
cascade: true
```

Add a `DocumentLoader` that reads the Markdown files, chunks them, and loads the chunks into the vector store on startup:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/knowledge/DocumentLoader.java
hidden: true
text: |
    package com.example.supportassistant.knowledge;

    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.ai.document.Document;
    import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
    import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
    import org.springframework.ai.transformer.splitter.TokenTextSplitter;
    import org.springframework.ai.vectorstore.VectorStore;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.core.io.Resource;
    import org.springframework.stereotype.Component;

    import jakarta.annotation.PostConstruct;
    import java.util.List;
    import java.util.Arrays;

    @Component
    public class DocumentLoader {

        private static final Logger log = LoggerFactory.getLogger(DocumentLoader.class);

        private final VectorStore vectorStore;

        @Value("classpath:data/*.md")
        private Resource[] knowledgeFiles;

        public DocumentLoader(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
        }

        @PostConstruct
        public void loadDocuments() {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder().build();
            MarkdownDocumentReader documentReader = new MarkdownDocumentReader(Arrays.asList(knowledgeFiles), config);
            List<Document> documents = new TokenTextSplitter().apply(documentReader.get());
            vectorStore.add(documents);
            log.info("Loaded {} document chunks into vector store", documents.size());
        }
    }
```

## Create the KnowledgeService

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/knowledge/KnowledgeService.java
description: Create KnowledgeService with QuestionAnswerAdvisor
text: |
  package com.example.supportassistant.knowledge;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
  import org.springframework.ai.vectorstore.SearchRequest;
  import org.springframework.ai.vectorstore.VectorStore;
  import org.springframework.stereotype.Service;

  @Service
  public class KnowledgeService {

      private final ChatClient chatClient;
      private final VectorStore vectorStore;

      public KnowledgeService(ChatClient chatClient, VectorStore vectorStore) {
          this.chatClient = chatClient;
          this.vectorStore = vectorStore;
      }

      public String answerQuestion(String question) {
          SearchRequest ragSearchRequest = SearchRequest.builder().topK(3).build();
          QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore).searchRequest(ragSearchRequest).build();

          return chatClient.prompt()
                  .user(question)
                  .advisors(ragAdvisor)
                  .call()
                  .content();
      }
  }
```

## Create the KnowledgeController

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/supportassistant/knowledge/KnowledgeController.java
description: Create KnowledgeController
text: |
  package com.example.supportassistant.knowledge;

  import org.springframework.web.bind.annotation.*;

  import java.util.Map;

  @RestController
  @RequestMapping("/knowledge")
  public class KnowledgeController {

      private final KnowledgeService knowledgeService;

      public KnowledgeController(KnowledgeService knowledgeService) {
          this.knowledgeService = knowledgeService;
      }

      @GetMapping("/ask")
      public Map<String, String> askQuestion(@RequestParam String question) {
          String answer = knowledgeService.answerQuestion(question);
          return Map.of(
                  "question", question,
                  "answer", answer
          );
      }
  }
```

## Start and Test the RAG Endpoint

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

```execute
http localhost:8080/knowledge/ask question=="What severity levels are supported for CVE patches?"
```

```execute
http localhost:8080/knowledge/ask question=="What's the difference between Premium and Standard support?"
```

```execute
http localhost:8080/knowledge/ask question=="Which Spring Boot versions have extended LTS support?"
```

## Stop the Application

```terminal:interrupt
session: 2
```
