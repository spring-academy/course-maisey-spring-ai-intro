## What is Spring AI?

Spring AI is a framework that brings AI capabilities to the Java and Spring ecosystem. It provides a **consistent, portable programming model** across AI providers — the same code works with OpenAI, Anthropic, Google Gemini, Ollama, and others.

Key features:

| Feature | What it gives you |
|---------|------------------|
| **Portable `ChatClient` API** | Swap AI providers without rewriting application code |
| **Spring Boot auto-configuration** | Zero-boilerplate setup for any supported model |
| **Structured output** | Map AI responses directly to Java records and objects |
| **RAG support** | Built-in ETL pipeline and vector store integrations |
| **Tool calling** | Connect the model to your own services with `@Tool` annotations |
| **MCP server** | Expose your tools as a Model Context Protocol server |
| **Advisors** | Intercept and enrich the chat pipeline (memory, logging, safety) |
| **Observability** | Micrometer metrics for token usage, latency, and error rates |
| **Testing support** | Mock providers and LLM-as-judge evaluators |

## What You'll Build

Throughout this course you'll incrementally build a **Tanzu Spring Support Assistant** — an AI-powered application that can:

- Answer questions about Spring support offerings using a `ChatClient`
- Classify and format responses as typed Java objects
- Answer from documentation using Retrieval-Augmented Generation (RAG)
- Create and list support tickets via tool calling
- Expose its tools over the Model Context Protocol (MCP)
- Maintain conversation history across multiple turns using Advisors

## Course Structure

The course alternates between short **theory articles** and **hands-on labs**:

1. **AI Fundamentals** — LLMs, tokens, and the core techniques (RAG, tool calling, prompt engineering)
2. **Simple Chat** → lab: build a blocking and streaming `ChatClient` endpoint
3. **Prompt Engineering** → lab: add system prompts and few-shot classification
4. **Structured Output** → lab: map responses to Java records with `.entity()`
5. **Embeddings & RAG** → lab: load documents into a vector store and answer questions from them
6. **Tool Calling** → lab: connect the AI to a live database via `@Tool` methods
7. **MCP Integration** → lab: expose your tools as an MCP server
8. **Advisors & Agentic Patterns** → lab: add conversation memory and debug logging
9. **Observability** → lab: instrument token usage and latency with Micrometer
10. **Testing** → lab: validate response quality with the Spring AI Evaluator framework

## Prerequisites

- Java 21 and Maven (provided in the lab environment)
- Familiarity with Spring Boot (dependency injection, REST controllers, `@Bean`)
- No prior AI or ML experience required — the AI Fundamentals article covers everything you need
