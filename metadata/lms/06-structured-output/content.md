## The Problem

AI models return free-form text, but applications usually need structured data. Parsing and validating that text manually is fragile.

```
Input:  "What CVEs are covered by my Premium subscription?"
Output: SupportChatResponse(category=SECURITY, answer="As a Premium customer...")
```

Spring AI solves this by automatically generating a JSON schema from your Java type, injecting format instructions into the prompt, and parsing the model's response back into the target object — all in a single `.entity()` call.

## The `.entity()` Method

Replace `.content()` with `.entity(YourType.class)` to get typed output:

```java
SupportChatResponse response = chatClient.prompt()
    .user(query)
    .call()
    .entity(SupportChatResponse.class);
```

Spring AI performs three steps transparently:
1. **Schema generation** — derives a JSON schema from the class structure using Jackson
2. **Prompt injection** — appends format instructions to the request (e.g., "respond only in JSON")
3. **Deserialization** — parses the model's JSON response into the target type

## Guiding the Model with `@JsonPropertyDescription`

Annotating record fields with `@JsonPropertyDescription` adds field-level descriptions to the generated JSON schema. The model receives these descriptions as part of the format instructions, which helps it populate each field correctly:

```java
public record SupportChatResponse(
    @JsonPropertyDescription("Category: TECHNICAL, BILLING, SECURITY, UPGRADE, or GENERAL")
    ChatResponseCategory category,

    @JsonPropertyDescription("The helpful answer to the customer's question")
    String answer
) {}
```

Without annotations the model still works, but explicit descriptions reduce ambiguity — especially for enum fields and fields whose names alone don't convey the expected values.

## Using Enums

Enums are fully supported. Spring AI includes the enum constants in the generated schema, so the model knows exactly which values are valid. This makes classification tasks reliable without additional prompting.
