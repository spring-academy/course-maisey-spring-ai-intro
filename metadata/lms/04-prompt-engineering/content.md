## Message Roles

LLMs understand three distinct message roles that structure the conversation:

| Role | Purpose | Example |
|------|---------|---------|
| **System** | Sets assistant behavior and persistent context | "You are a helpful Spring expert" |
| **User** | The human's input for this turn | "How do I configure Spring Security?" |
| **Assistant** | The model's previous responses | Used to provide conversation history |

The **system** role is processed before the user message and shapes how the model interprets and responds to everything that follows.

## Prompt Templates

Instead of concatenating strings to build prompts, Spring AI's `PromptTemplate` provides placeholder-based substitution using `{variable}` syntax. This separates the prompt structure from runtime values and makes prompts version-controllable.

Template files are typically stored in `src/main/resources/prompts/` as `.st` (StringTemplate) files and loaded via `@Value("classpath:/prompts/my-template.st")`.

In the `ChatClient` fluent API, system prompts with template variables are set like this:

```java
chatClient.prompt()
    .system(sys -> sys
        .text(systemPromptResource)     // load from classpath
        .param("customerTier", "Premium")) // replace {customerTier}
    .user(userQuery)
    .call()
    .content();
```

Internally, `ChatClient` uses `PromptTemplate` with a `TemplateRenderer` to substitute variables before sending the request to the model.

## Prompt Engineering Techniques

**Zero-shot prompting** — ask the model directly without examples. Works for straightforward tasks where the model already has relevant knowledge.

**Few-shot prompting** — provide examples of input/output pairs in the prompt. Particularly effective for classification tasks because the model learns the expected format and categories from the examples:

```
Query: "My Spring Boot app won't start after adding a dependency"
Category: TECHNICAL

Query: "When will I receive my invoice for Q4?"
Category: BILLING
```

**Chain-of-thought prompting** — ask the model to reason step by step before giving a final answer. Improves accuracy on complex reasoning tasks.

The right technique depends on the task: classification tasks benefit from few-shot examples, while reasoning tasks benefit from chain-of-thought. Start with zero-shot; add examples if outputs are inconsistent.
