---
title: Getting Started
---

# Getting Started

In this lab, you'll write automated tests for the support assistant. LLM-backed code can't be tested like normal code — the model rewords answers, picks synonyms, and sometimes refuses outright. Two patterns get us most of the way:

1. **Semantic assertions** — for cheap, deterministic-ish checks, assert on meaning (key concepts, not exact text).
2. **LLM-as-judge** — for harder questions like "is this answer actually relevant to the retrieved context?", use a separate model call to grade the output.

Both come out of the box in Spring AI; you just wire them into a regular `@SpringBootTest`.

Your starting point in `~/sample-app` is the full support assistant: RAG over a Markdown knowledge base, structured output, and tool calls to a ticket repository.

{{< note >}}
This lab uses **OpenAI**. The sample app also bundles the starters for Anthropic, Amazon Bedrock, and Ollama — you could switch the chat provider via Spring profiles (e.g. `SPRING_PROFILES_ACTIVE=anthropic`). The default configuration uses OpenAI.
{{< /note >}}

## Test Dependencies

Nothing to add. The project already ships `spring-boot-starter-webmvc-test` (which pulls in JUnit 5, AssertJ, and `@SpringBootTest`) and `spring-boot-starter-actuator-test` — they were part of the project from the start. The `RelevancyEvaluator` you'll use later lives in `spring-ai-client-chat`, which the OpenAI chat starter (like the other provider starters) already brings in transitively.

So: no new dependencies.

## Set the API Key

The tests call the real OpenAI API, so the key must be available in the terminal that runs Maven. Set your OpenAI API key (use your own or the one provided by your instructor) — paste it after the `=` and press Enter:

```terminal:input
text: export OPENAI_API_KEY=
endl: false
session: 2
```

## Stop the Running Application

The tests use `webEnvironment = DEFINED_PORT`, which starts the application context on its real port (8080). If the app from a previous lab is still running there, the tests can't start — stop it first:

```terminal:interrupt
session: 2
```

## Summary

Everything you need for testing is already on the classpath, your API key is set, and port 8080 is free. Next: a first test that checks response quality without depending on exact wording.
