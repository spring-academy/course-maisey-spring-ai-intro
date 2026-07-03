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
          var splitDocuments = tokenTextSplitter.apply(documents);
          vectorStore.add(splitDocuments);
          log.info("Loaded {} document chunks into vector store", splitDocuments.size());
      }
  }
```

The three Spring AI building blocks involved:

- **`MarkdownDocumentReader`** — *extracts* the `Document` objects from the Markdown files.
- **`TokenTextSplitter`** — *transforms* each document into smaller chunks sized for the embedding model.
- **`VectorStore.add(...)`** — embeds and *loads* them. The embedding model you configured in the previous step is invoked here.

How you extract and split your data matters for more than just the embedding step. The chunks that come out of this pipeline are the exact text the chat model later receives as context, so cleaner and better sized chunks lead to better answers. Expect to come back and tweak this step once you see how your data behaves in real queries.

The reader and the splitter are tuned for the knowledge base through their configurations.

`MarkdownDocumentReaderConfig` controls how a Markdown file becomes documents:

- **`withHorizontalRuleCreateDocument(true)`** starts a new `Document` at every horizontal rule (`---`). Look at [tanzu-spring.md](~/sample-app/src/main/resources/knowledge-base/tanzu-spring.md) and you see each top level section is separated by a `---`. So instead of one large document per file you get one document per section, which keeps related content together and cuts across topics only at natural boundaries.
- **`withIncludeCodeBlock(true)`** keeps the content of fenced code blocks in the documents.
- **`withIncludeBlockquote(true)`** keeps the content of blockquotes in the documents. Both are excluded by default, so turning them on makes sure no part of the source text is dropped before embedding.

`TokenTextSplitter` then caps the size of each section:

- **`withMinChunkLengthToEmbed(25)`** skips chunks shorter than 25 characters. Tiny fragments such as a lone heading carry little meaning, so this avoids embedding and storing noise.

The **`@EventListener(ApplicationReadyEvent.class)`** annotation tells Spring to call the `index()` method automatically once the application has fully started. Spring publishes an `ApplicationReadyEvent` when the context is ready to serve requests, so the pipeline runs a single time at startup and populates the vector store before any user query arrives.

Running the pipeline at startup is only for demonstration purposes. It re-reads and re-embeds every document on every restart, and it does not notice when a source document changes while the application is running. In a real system you keep the vector store up to date by indexing outside the application lifecycle. You trigger indexing when a document is added, changed, or removed, for example through a scheduled job that picks up new and modified files, a message on a queue, or a webhook from your content system. You also store an identifier and a version or checksum with each chunk so you can update or delete only the affected entries instead of rebuilding the whole store.

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

## Summary

You've built the indexing side of RAG: read Markdown files, split them into chunks, and load them into the `VectorStore`. Now let's use them at query time.
