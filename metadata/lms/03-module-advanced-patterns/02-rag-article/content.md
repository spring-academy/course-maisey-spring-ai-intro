## From Theory to Spring AI

In the foundations section you saw what RAG is and why it works. Models only know their training data, so you retrieve relevant facts, augment the prompt, and let the model generate a grounded answer. That rests on embeddings, a vector store, and an ETL pipeline that reads, chunks, and loads your documents. This section shows how Spring AI gives you each of those pieces, and how an advisor ties them together at query time.

## The `EmbeddingModel` Contract

Just as `ChatModel` is the portable contract over chat providers, Spring AI defines **`EmbeddingModel`** as the portable contract over embedding providers (OpenAI, Ollama, and others). You hand it text and it returns vectors.

```java
float[] vector = embeddingModel.embed("How do I reset my password?");
List<float[]> vectors = embeddingModel.embed(List.of("first text", "second text"));
```

Like the chat starters, each provider ships an embedding starter (for example `spring-ai-starter-model-openai`), and the auto-configuration gives you a ready `EmbeddingModel` bean to inject. The model and other options are externalized in configuration, so you can switch embedding models without touching code.

```properties
spring.ai.openai.embedding.model=text-embedding-3-small
```

Remember the rule from the foundations section. You must use the same embedding model for indexing and for querying, because vectors are only comparable when they were produced the same way.

In practice you'll rarely call `EmbeddingModel` directly. It does its work behind the scenes, inside the vector store and the RAG advisors we'll meet next.

## The `VectorStore` Abstraction

Spring AI defines a single **`VectorStore`** abstraction over the many vector database implementations (PGVector, Redis, Qdrant, Milvus, Chroma, and many more), plus a `SimpleVectorStore` for testing. As with everything else, swapping implementations is mostly a dependency-and-configuration change, not a rewrite. You add the matching starter, and the auto-configuration provides a `VectorStore` bean.

The store works with the **`Document`** abstraction, a piece of text plus arbitrary metadata.

```java
Document doc = new Document(
    "To reset your password, open Settings and choose 'Security'.",
    Map.of("source", "support-guide.pdf", "category", "account"));
```

Adding documents is deliberately simple. You hand the store a list of `Document` objects, and it **computes the embeddings for you** (using your configured `EmbeddingModel`) before persisting the vector alongside the text and metadata.

```java
vectorStore.add(List.of(doc1, doc2, doc3));
```

Searching is just as direct. You describe what you want with a **`SearchRequest`** and get back the most similar documents.

```java
List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("I forgot my login")
        .topK(5)                  // return at most 5 matches
        .similarityThreshold(0.7) // ignore weak matches (0..1)
        .build());
```

The `topK` and `similarityThreshold` settings are the same two knobs you met in the foundations section. You pass a plain-text `query` here, and the store embeds it for you before comparing it against the stored vectors.

### Filtering by metadata

The metadata you attached to each `Document` is searchable through a **portable filter expression language** that looks like SQL and works the same way across every vector store.

```java
List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("how do I upgrade?")
        .filterExpression("category == 'billing' && version >= 2")
        .build());
```

You can also build these filters programmatically with `FilterExpressionBuilder` when the criteria are dynamic, for example when restricting results to the current user's tenant.

## The ETL Pipeline in Spring AI

The indexing phase follows the Extract, Transform, Load pattern from the foundations section, and Spring AI models it as three composable interfaces.

**Extract** is handled by a **`DocumentReader`**, which reads a source and produces `Document` objects. Spring AI ships readers for the formats you'll actually encounter. `PagePdfDocumentReader` for PDFs, `TikaDocumentReader` for Office formats like DOCX and PPTX (and HTML), `JsonReader`, `MarkdownDocumentReader`, and `TextReader` for plain text.

```java
PagePdfDocumentReader reader = new PagePdfDocumentReader("classpath:/support-guide.pdf");
List<Document> documents = reader.read();
```

**Transform** is handled by a **`DocumentTransformer`**, which takes documents and returns reshaped documents. The most important one is the **`TokenTextSplitter`**, which does the chunking you read about in the foundations section, breaking large documents into smaller, focused pieces.

```java
TokenTextSplitter splitter = TokenTextSplitter.builder()
    .withChunkSize(800)   // target chunk size in tokens
    .build();
List<Document> chunks = splitter.apply(documents);
```

Other transformers can enrich documents before storage. For instance `KeywordMetadataEnricher` and `SummaryMetadataEnricher` use a chat model to attach keywords or summaries as metadata, giving you more to filter and match on later.

**Load** is handled by a **`DocumentWriter`**, and here's the neat part. `VectorStore` itself *is* a `DocumentWriter`. So the store you search at query time is also the sink at the end of your pipeline. Because each stage is a standard Java functional interface (`Supplier`, `Function`, `Consumer`), the whole pipeline composes into a single readable line.

```java
vectorStore.write(splitter.split(reader.read()));
```

You run this once, and again whenever your documents change, to populate the store. With indexing done, everything is in place for the query-time phase.

## The Advisor Pattern

You now have all the pieces of retrieval-and-generation. Embed the question, search the vector store, attach the results to the prompt, call the model. You *could* wire those steps together by hand on every request, but retrieval is a *cross-cutting concern*, the kind of logic you want to apply consistently around many calls without scattering it through your code. Spring AI handles this with **advisors**.

If you've used Servlet filters or Spring's `HandlerInterceptor`, advisors will feel familiar. An advisor is an **interceptor that wraps a `ChatClient` call**, with a chance to act both *before* the request reaches the model and *after* the response comes back. Several advisors form a **chain**, and a request passes through all of them on the way in, hits the model, and passes back through them on the way out. This is the classic "around" pattern. Each advisor can inspect and modify the request, decide whether to proceed, and then inspect and modify the response.

Concretely, the framework wraps your `Prompt` in a **`ChatClientRequest`** (the request plus a shared context map) and hands it to the first advisor. Each advisor does its *before* work, then calls the chain to invoke the next advisor; the last one calls the model. The model's answer travels back as a **`ChatClientResponse`**, and each advisor gets to do its *after* work as it unwinds. A logging advisor captures the shape nicely, logging on the way in, delegating to the rest of the chain, and logging on the way out.

```java
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    logRequest(request);                                 // before
    ChatClientResponse response = chain.nextCall(request); // delegate down the chain → model
    logResponse(response);                               // after
    return response;
}
```

A few properties are worth understanding, because they explain how advisors behave when you combine them.

- **Order matters, and it's stack-like.** Each advisor reports a priority via `getOrder()` (lower runs first). On the way *in*, advisors run lowest-order first; on the way *out*, the order reverses, just like nested method calls. So an advisor that adds context before the model also gets the first look at the response.
- **Around both phases, two flavors.** The same advisor can implement the blocking `.call()` path (`CallAdvisor`) and the reactive `.stream()` path (`StreamAdvisor`), so cross-cutting behavior works whether you wait for the whole answer or stream it.
- **A shared context map** travels with the request through the whole chain. This is how you pass per-request parameters to an advisor at call time, for example telling a memory advisor which conversation this is.

You register advisors in one of two places. Most live on the `ChatClient` as **defaults**, applied to every call made through that client.

```java
ChatClient chatClient = builder
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // conversation memory
        QuestionAnswerAdvisor.builder(vectorStore).build())    // RAG
    .build();
```

Or you attach and parameterize them **per request**, which is also how you feed values into that shared context map.

```java
String answer = chatClient.prompt()
    .user("How do I reset my password?")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user-42"))
    .call()
    .content();
```

Spring AI ships a set of built-in advisors for exactly these recurring patterns. The **chat-memory advisors** that maintain conversation history, the **`ToolCallingAdvisor`** that runs the tool-calling loop (you'll meet it in the next section), a **`SafeGuardAdvisor`** that blocks unwanted content, a logging advisor, and, most relevant here, the RAG advisors. The beauty of the pattern is that adding any of these is a configuration change on the `ChatClient`, while your prompting code stays the same fluent chain. RAG is just one more advisor in the chain.

## Bringing It Together with the `QuestionAnswerAdvisor`

The **`QuestionAnswerAdvisor`** is the advisor that turns a plain `ChatClient` into a RAG application. In its *before* phase it takes the user's question, runs a similarity search against the vector store, and injects the matching documents into the prompt as context; the call then proceeds to the model, which answers grounded in that context. You attach it to a `ChatClient` and otherwise prompt exactly as you did in the previous module.

```java
ChatClient chatClient = builder
    .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
    .build();

String answer = chatClient.prompt()
    .user("How do I reset my password?")
    .call()
    .content();
```

That's a complete RAG application. The advisor retrieves the relevant support documents and grounds the answer in them, without you writing any retrieval code in the call itself. You can tune what it retrieves by giving it a `SearchRequest` (the same `topK` and `similarityThreshold` knobs as before), supply a custom prompt template to control how the context is framed, or pass a filter expression per request.

```java
ChatClient chatClient = builder
    .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder().topK(6).similarityThreshold(0.8).build())
        .build())
    .build();
```

Because the advisor is configured on the `ChatClient`, the rest of your application code stays the same fluent chain you already know, and retrieval just becomes part of how that particular client behaves. This pairs naturally with the structured-output and system-prompt techniques from earlier. A support assistant might use a system prompt to set its tone, the `QuestionAnswerAdvisor` to ground its facts, and `.entity(...)` to return a typed answer.

## Modular RAG with the `RetrievalAugmentationAdvisor`

The foundations section described how the retrieval flow can be broken into stages for when naive retrieval is not enough. Spring AI offers a second, more flexible advisor for exactly that, the **`RetrievalAugmentationAdvisor`**, built on a **modular RAG architecture**. It breaks the retrieval flow into the well-defined stages you read about, pre-retrieval, retrieval, post-retrieval, and generation, that you can mix and match like building blocks.

```java
Advisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
    .queryTransformers(RewriteQueryTransformer.builder()
        .chatClientBuilder(chatClientBuilder)
        .build())
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.5)
        .build())
    .build();
```

Each stage has ready-made components. **Query transformers** reshape the question before searching. `CompressionQueryTransformer` folds conversation history into a standalone query, `RewriteQueryTransformer` rephrases an awkward question for better search results, and `TranslationQueryTransformer` translates it into your documents' language. **Query expanders** like `MultiQueryExpander` turn one question into several variations to widen the net. The **`VectorStoreDocumentRetriever`** does the actual search, and a **`ContextualQueryAugmenter`** controls generation, including whether to allow an answer when no context was found.

You don't need these on day one, and the lab uses the simpler `QuestionAnswerAdvisor`. But it's worth knowing the modular path exists. When your support assistant outgrows naive retrieval, you can reach for these components without abandoning the programming model.

## What's Next

You've now seen how Spring AI implements the RAG concepts from the foundations section. `EmbeddingModel` and `VectorStore` give you embeddings and similarity search, the ETL interfaces give you indexing, and the **`QuestionAnswerAdvisor`** ties retrieval into a `ChatClient` call, with **modular RAG** waiting for when you need finer control. In the next section you'll build exactly this, a support assistant that indexes real documentation and answers questions from it.
