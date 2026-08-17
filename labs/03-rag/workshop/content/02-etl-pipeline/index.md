---
title: The ETL Pipeline
---

RAG indexing is a classic ETL: **E**xtract documents, **T**ransform them into chunks the model can digest, **L**oad them (as embedding vectors) into the store.

## The Knowledge Base

The sample app ships Markdown support docs for Spring, content the model can't fully know on its own. Anything your support assistant should "know" goes here:

```terminal:execute
command: ls ~/sample-app/src/main/resources/knowledge-base/
session: 1
```

Have a look at one of them:

```editor:open-file
file: ~/sample-app/src/main/resources/knowledge-base/tanzu-spring.md
```

Notice how each top level section is separated by a horizontal rule (`---`). You will use these markers in a moment to split the file into one document per section.

## Implement the Indexer

Create a component that runs the ETL pipeline once at startup:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
description: Create KnowledgeBaseIndexer
text: |
  package com.example.support_assistant;

  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.boot.context.event.ApplicationReadyEvent;
  import org.springframework.context.event.EventListener;
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

      @EventListener(ApplicationReadyEvent.class)
      public void index() {
          var documentReaderConfig = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeBlockquote(true)
                .withIncludeCodeBlock(true)
                .build();
          var documentReader = new MarkdownDocumentReader(Arrays.asList(knowledgeFiles), documentReaderConfig);
          List<Document> documents = documentReader.read();

          var tokenTextSplitter = TokenTextSplitter.builder()
                .withMinChunkLengthToEmbed(25)
                .build();
          var splitDocuments = tokenTextSplitter.split(documents);
          vectorStore.add(splitDocuments);
          log.info("Loaded {} document chunks into vector store", splitDocuments.size());
      }
  }
```

The three stages of the pipeline are three Spring AI building blocks. 
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: "var documentReaderConfig = MarkdownDocumentReaderConfig.builder()"
before: 0
after: 6
description: MarkdownDocumentReader
```

**Extract.** The `MarkdownDocumentReader` reads the Markdown files and returns them as `Document` objects. How you extract and split your data matters for more than the embedding step, because these chunks are the exact text the chat model later receives as context. 

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: ".withHorizontalRuleCreateDocument(true)"
before: 0
after: 0
description: withHorizontalRuleCreateDocument(true)
```

This starts a new `Document` at every horizontal rule, so you get one document per section instead of one large document per file. 

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: ".withIncludeBlockquote(true)"
before: 0
after: 1
description: withIncludeBlockquote(true) and withIncludeCodeBlock(true)
```

Blockquotes and fenced code blocks are dropped by default. Turning both on makes sure no part of the source text is lost before embedding.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: "var tokenTextSplitter = TokenTextSplitter.builder()"
before: 0
after: 3
description: TokenTextSplitter
```

**Transform.** The `TokenTextSplitter` cuts each document into chunks that fit the embedding model. With `withMinChunkLengthToEmbed(25)` it skips chunks shorter than 25 characters, because a tiny fragment such as a lone heading carries little meaning and would only add noise to the store.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: "vectorStore.add(splitDocuments);"
before: 0
after: 0
description: vectorStore.add(splitDocuments)
```

**Load.** This one line embeds every chunk and stores it. The embedding model you configured in the previous step is called here.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/KnowledgeBaseIndexer.java
text: "@EventListener(ApplicationReadyEvent.class)"
before: 0
after: 1
description: "@EventListener(ApplicationReadyEvent.class)"
```

Spring publishes an `ApplicationReadyEvent` when the context is ready to serve requests, so this annotation runs the pipeline once at startup and fills the vector store before the first user query arrives.

Doing it at startup is fine for this lab, but not for a real system. It re-embeds every document on every restart and it never notices a document that changes while the application runs. In production you index outside the application lifecycle, triggered when a document is added, changed, or removed, for example by a scheduled job, a message on a queue, or a webhook from your content system. You also store an identifier and a version or checksum with each chunk, so you can update or delete only the affected entries instead of rebuilding the whole store.

## Watch the Logs

Go back to the Terminal to view the logs during restart of the application:
```dashboard:open-dashboard
name: Terminal
```

You should see a log line like:
```
Loaded 30 document chunks into vector store
```

Each of those chunks has been run through the embedding model and stored as a vector, ready for similarity search.