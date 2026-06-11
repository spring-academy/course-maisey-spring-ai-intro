## How Tool Calling Works

Tool calling (also called function calling) lets the model interact with external systems at runtime. Instead of only generating text, the model can request to execute specific functions when the user's intent requires it.

The flow:

```
User: "What time is it in Tokyo?"
    ↓
AI: "I need to call getCurrentDateTime(timezone='Asia/Tokyo')"
    ↓
Your code: executes the method → "2024-01-15 14:30:00 JST"
    ↓
AI: "The current time in Tokyo is 2:30 PM JST."
```

Critically, the model decides **when** to call a tool and **what arguments** to pass, based on the tool's description and the user's message. Your application is responsible only for executing the method and returning the result.

## Defining Tools

Spring AI uses the `@Tool` and `@ToolParam` annotations to mark methods as callable by the model:

```java
@Tool(description = "Get the current date and time in a specific timezone.")
public String getCurrentDateTime(
        @ToolParam(description = "Timezone, e.g. 'America/New_York', 'Asia/Tokyo'")
        String timezone) {
    return ZonedDateTime.now(ZoneId.of(timezone))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
}
```

- `@Tool(description)` — tells the model what this function does and when to use it. The quality of this description directly affects how reliably the model invokes the tool.
- `@ToolParam(description)` — describes each parameter so the model knows what value to extract from the user's message.

Register tools with a `ChatClient` call using `.tools(...)`:

```java
chatClient.prompt()
    .user(message)
    .tools(dateTimeTool, ticketTool)  // model can call any of these
    .call()
    .content();
```

## The Agentic Loop

When tools are registered, Spring AI automatically handles the multi-turn tool-calling loop:

1. Model decides to call a tool → returns a tool-call request
2. Spring AI executes the `@Tool` method
3. Result is sent back to the model
4. Model either calls another tool or generates the final response

This loop runs transparently — from the caller's perspective, it is still a single `.call()`.

## Tool Best Practices

- **Write precise descriptions**: vague descriptions lead to tools being called at wrong times or with wrong arguments
- **Return structured data**: return records or strings with clear semantics so the model can incorporate the result naturally
- **Scope each tool narrowly**: one tool, one responsibility — easier for the model to reason about
- **Tools can call external APIs, databases, or any Java code** — they are plain Spring beans
