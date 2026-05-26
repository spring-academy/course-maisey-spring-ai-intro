## Why RAG?

LLMs have three inherent limitations that make them unreliable for enterprise knowledge bases:

- **Knowledge cutoff** — training data has a fixed date; the model knows nothing about recent updates, patches, or policy changes
- **No proprietary knowledge** — models are trained on public data and have no awareness of your internal documentation
- **Hallucinations** — when asked about unfamiliar topics, models generate plausible-sounding but incorrect answers

Retrieval-Augmented Generation (RAG) addresses all three by retrieving relevant passages from your own knowledge base and injecting them into the prompt as grounding context.

## How Embeddings Work

Embeddings convert text into high-dimensional numerical vectors (typically 256–1536 dimensions) that capture semantic meaning. The key property: **semantically similar texts produce vectors that are close together**.

```
"Spring Boot"      → [0.2, 0.8, 0.1, ...]
"Spring Framework" → [0.3, 0.7, 0.2, ...]  ← close (similar meaning)
"Pizza recipe"     → [0.9, 0.1, 0.4, ...]  ← far (different meaning)
```

These vectors are stored in a **vector store** optimized for similarity search. When a user asks a question:

1. The question is converted to a vector using the same embedding model
2. The vector store finds the most similar document vectors
3. The matching text chunks are retrieved and added to the prompt

## The ETL Pipeline

Before you can search your knowledge base, you need to load it into the vector store. Spring AI provides components for each step of the Extract–Transform–Load pipeline:

- **Extract** — `DocumentReader` implementations read source files (Markdown, PDF, HTML, web pages)
- **Transform** — `TokenTextSplitter` chunks documents into pieces that fit within the context window
- **Load** — `VectorStore.add()` converts chunks to embeddings and persists them

## Vector Store Options

Spring AI supports many vector stores. For development and testing, `SimpleVectorStore` stores vectors in memory with no external dependencies. For production, use a persistent vector database such as PostgreSQL/pgvector, Redis, Pinecone, or Chroma.

The embedding model is auto-configured by the AI provider starter (OpenAI provides `text-embedding-3-small` by default). Configure it via `spring.ai.openai.embedding.options.model`.

## RAG Advisors

Spring AI integrates RAG into the chat pipeline via **Advisors** (covered in depth in a later lesson). Two built-in options:

| Advisor | Description |
|---------|-------------|
| `QuestionAnswerAdvisor` | Simple, out-of-the-box RAG. Searches the vector store and injects context automatically. |
| `RetrievalAugmentationAdvisor` | Flexible advisor for advanced patterns: customizable query transformation, document selection, and context augmentation. |

`QuestionAnswerAdvisor` handles the complete flow — similarity search, context injection, and prompt augmentation — in a single advisor. Configure `topK` to control how many document chunks are retrieved per query.

## The RAG Flow in Summary

```
1. Query  → embed question into vector
2. Search → find top-K similar vectors in the store
3. Fetch  → retrieve corresponding text chunks
4. Augment → inject chunks into the system prompt
5. Generate → LLM answers using the grounded context
```
