---
title: The ETL Pipeline
---

# Indexing the Knowledge Base

RAG indexing is a classic ETL: **E**xtract documents, **T**ransform them into chunks the model can digest, **L**oad them (as embedding vectors) into the store.

## The Knowledge Base

The sample app ships a few Markdown support docs for Tanzu/Spring — content the model can't fully know on its own. Anything your support assistant should "know" goes here; each file becomes one or more documents:

```terminal:execute
command: ls ~/sample-app/src/main/resources/knowledge-base/
session: 1
```

Have a look at one of them:

```editor:open-file
file: ~/sample-app/src/main/resources/knowledge-base/tanzu-spring-runtime.md
```

## Implement the Indexer

Create a component that runs the ETL pipeline once at startup:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
description: Create KnowledgeBaseIndexer
text: |
  package com.example.support_assistant;

  import jakarta.annotation.PostConstruct;
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

  import java.util.Arrays;
  import java.util.List;

  @Component
  class KnowledgeBaseIndexer {

      private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);

      private final VectorStore vectorStore;

      @Value("classpath:knowledge-base/*.md")
      private Resource[] knowledgeFiles;

      KnowledgeBaseIndexer(VectorStore vectorStore) {
          this.vectorStore = vectorStore;
      }

      @PostConstruct
      public void index() {
          var config = MarkdownDocumentReaderConfig.builder().build();
          var documentReader = new MarkdownDocumentReader(Arrays.asList(knowledgeFiles), config);
          var tokenTextSplitter = TokenTextSplitter.builder().build();
          List<Document> documents = tokenTextSplitter.apply(documentReader.get());
          vectorStore.add(documents);
          log.info("Loaded {} document chunks into vector store", documents.size());
      }
  }
```

The three Spring AI building blocks at play:

- **`MarkdownDocumentReader`** — *extracts* a `Document` per Markdown file.
- **`TokenTextSplitter`** — *transforms* each document into smaller chunks sized for the embedding model.
- **`VectorStore.add(...)`** — embeds and *loads* them. The embedding model you configured in the previous step is invoked here, transparently.

## Restart and Watch the Logs

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

During startup you should see a log line like:

```
Loaded 14 document chunks into vector store
```

Each of those chunks has been run through the embedding model and stored as a vector — ready for similarity search.

## Summary

You've built the indexing side of RAG: read Markdown files, split them into chunks, and load them into the `VectorStore`. Now let's use them at query time.
