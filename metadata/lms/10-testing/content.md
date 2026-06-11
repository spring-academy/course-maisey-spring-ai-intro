## The Testing Challenge

Traditional tests are deterministic — same input, same output. AI responses are often non-deterministic:

```
"What is 2+2?" → "4"
"What is 2+2?" → "The answer is 4"
"What is 2+2?" → "2+2 equals 4"
```

All correct, but `assertEquals("4", response)` fails on two out of three. Additionally, **different models behave differently** — a response that works with GPT-4o may differ significantly with Claude or Gemini, and even the same model family at different sizes can produce varying quality and formatting.

## What to Test and How

Most of your application code — controllers, services, repositories — is tested as usual with standard Spring Boot testing. The AI-specific parts require different strategies:

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Business logic | Controllers, services, entities | Standard unit/integration tests |
| Structured output | Schema compliance, valid enum values | Assert structure and types |
| RAG retrieval | Document relevance | Vector store search assertions |
| Tool calling | Tool invocation logic | Mock tools, verify calls |
| Free-form responses | Relevance, quality | Spring AI Evaluators (LLM-as-judge) |
| Full integration | End-to-end with real model | Integration tests in CI/CD |

## Spring AI Evaluation Framework

For free-form responses, Spring AI provides the **Evaluator** framework. An evaluator assesses response *quality and relevance* rather than comparing exact strings — typically by using another LLM as the judge.

```java
@FunctionalInterface
public interface Evaluator {
    EvaluationResponse evaluate(EvaluationRequest evaluationRequest);
}
```

An `EvaluationRequest` contains:
- `userText` — the original question
- `dataList` — contextual data (e.g., documents retrieved by RAG)
- `responseContent` — the AI model's response

The `EvaluationResponse` provides a simple `.isPass()` result.

Spring AI ships with two evaluators:

| Evaluator | Purpose | Best For |
|-----------|---------|----------|
| `RelevancyEvaluator` | Is the response relevant to the question given the context? | RAG validation |
| `FactCheckingEvaluator` | Is the response factually consistent with the provided context? | Hallucination detection |

## Integration Testing Strategies

**Testcontainers + Ollama** — run a local model in a container for integration tests without API key dependencies:

```java
@Container
static OllamaContainer ollama = new OllamaContainer("ollama/ollama:latest");

@DynamicPropertySource
static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.ollama.base-url", ollama::getEndpoint);
    registry.add("spring.ai.ollama.chat.model", () -> "llama3.2:1b");
}
```

**Production model tests** (GPT-4o, Claude) — run in CI/CD, not on every local commit. They require API keys, are slower, and may be non-deterministic.

## Key Principles

- Test semantics, not exact strings — check that the response *contains* relevant concepts rather than matching a fixed string
- Use the **mock provider** for fast, deterministic local tests
- Use **Testcontainers** for integration tests against a real (local) model
- Reserve **production model tests** for CI/CD pipelines before releases
- Account for **model variability** — write assertions that hold across model sizes and providers
