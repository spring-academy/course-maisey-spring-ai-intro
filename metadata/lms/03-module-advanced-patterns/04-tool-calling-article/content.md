## From Theory to Spring AI

In the foundations section you saw what tool calling is and why it matters. A model can reason but it cannot act, so you give it tools. The model never runs anything itself. It only proposes a call, your application executes the matching code, and the result goes back so the model can continue. This section shows how Spring AI lets you expose ordinary Java methods as tools, and how it runs the request-execute-respond loop for you.

## Defining a Tool with `@Tool`

The most natural way to define a tool in Spring AI is to write an ordinary method and mark it with `@Tool`. Here are two tools for our support assistant, one that retrieves information and one that takes an action.

```java
class SupportTools {

    @Tool(description = "Get the current status of a customer's order by its ID")
    String getOrderStatus(@ToolParam(description = "The order ID, e.g. A-1234") String orderId) {
        return orderRepository.findStatus(orderId);
    }

    @Tool(description = "Open a support ticket for a customer and return the ticket number")
    String openTicket(@ToolParam(description = "Short summary of the problem") String summary) {
        return ticketService.create(summary).number();
    }
}
```

There's nothing special about these methods, they're regular Java that does whatever you want. What the annotations add is the **description the model reads**. As you saw in the foundations section, the description is the contract. The model decides whether and how to call a tool based entirely on its name, its description, and its parameter descriptions, so treat these texts as carefully as the prompt itself.

Two annotations do the work.

- **`@Tool`** marks the method as a tool. Its `description` tells the model what the tool does. The tool's name defaults to the method name but can be overridden, and it must be unique within the set you provide.
- **`@ToolParam`** describes a parameter so the model knows what to put there, and can mark it optional with `required = false` (parameters are required by default).

From these, Spring AI automatically generates the **JSON schema** of the tool's inputs that the model needs, so you don't write that by hand. You just describe your method, and the framework derives the contract the model sees.

## Giving Tools to the `ChatClient`

Tools attach to a `ChatClient` call the same fluent way everything else does. Hand the client an instance of your tools class with `.tools(...)`, and prompt as usual.

```java
String answer = chatClient.prompt()
    .user("What's the status of order A-1234, and open a ticket if it's delayed?")
    .tools(new SupportTools())
    .call()
    .content();
```

If a set of tools should be available on every call a client makes, register them as defaults on the builder instead, exactly like a default system prompt.

```java
ChatClient chatClient = builder
    .defaultTools(new SupportTools())
    .build();
```

The `.tools()` method is flexible about what it accepts. `@Tool`-annotated objects like the one above, ready-made `ToolCallback` instances, or `ToolCallbackProvider`s. However you supply them, the model sees the same thing, a list of named, described, schema-typed tools it may choose to call.

## The Framework Runs the Loop for You

Look again at that example. The user's request might require *two* tool calls (check the status, then conditionally open a ticket), and after each one the model needs the result before it can decide what to do next. This is the tool-calling loop from the foundations section. Yet your code is a single `.call()`. Who runs that back-and-forth?

This is where the **advisor** mechanism from the RAG section returns. When you attach tools to a `ChatClient`, Spring AI automatically registers a **`ToolCallingAdvisor`** into the chain. That advisor manages the entire lifecycle. It sends the request, sees when the model asks for a tool, executes the matching method, feeds the result back, and repeats until the model produces a final answer instead of another tool request. All of that happens inside your one `.call()`. You write the tools and hand them over; the framework drives the loop.

Because this is just an advisor, it composes with everything else on the client. Your support assistant can carry a RAG `QuestionAnswerAdvisor` *and* tools at the same time, grounding answers in documentation while also being able to act, all from the same prompt chain. And because it's auto-registered, you rarely think about it. You can disable the automatic registration globally with `spring.ai.chat.client.tool-calling.enabled=false`, or per call, on the rare occasion you want to run the loop yourself.

## Passing Data the Model Shouldn't See with `ToolContext`

The foundations section explained why some tool inputs must never come from the model. The current user's id, the tenant they belong to, or an auth token are values your application controls, not values you'd want the model to guess or be able to influence. For this, Spring AI provides **`ToolContext`**. You attach extra data to the call, and your tool reads it from the context rather than receiving it as a model-supplied parameter.

```java
class SupportTools {

    @Tool(description = "Get the current status of a customer's order by its ID")
    String getOrderStatus(String orderId, ToolContext toolContext) {
        String tenantId = (String) toolContext.getContext().get("tenantId");
        return orderRepository.findStatus(orderId, tenantId);
    }
}
```

```java
String answer = chatClient.prompt()
    .user("What's the status of order A-1234?")
    .tools(new SupportTools())
    .toolContext(Map.of("tenantId", "acme"))
    .call()
    .content();
```

The key guarantee is that **`ToolContext` data is never sent to the model**. The model sees only `orderId` in the tool's schema; the `tenantId` is injected by your application at execution time. This keeps the model's view limited to what it legitimately needs to reason about, while your code retains control over the sensitive, security-relevant inputs.

## Tools as Functions

Annotated methods are the common case, but a tool is really just an implementation of the **`ToolCallback`** interface, which pairs a **`ToolDefinition`** (the name, description, and input schema the model sees) with the logic to execute. Spring AI also lets you build a tool from a plain `Function`, which is handy when the logic already lives in a service or a bean.

```java
ToolCallback weatherTool = FunctionToolCallback
    .builder("currentWeather", new WeatherService())
    .description("Get the weather in a location")
    .inputType(WeatherRequest.class)
    .build();
```

This produces the same kind of `ToolCallback` you'd get from an annotated method, and you pass it to `.tools(...)` just the same. Whether you reach for `@Tool` or `FunctionToolCallback` is a matter of where your code already lives. The model can't tell the difference.

## When You Need More Control

The defaults handle most applications, but a few knobs are worth knowing exist for when you need them.

- **Returning a result directly.** By default every tool result goes back to the model for a final, natural-language response. Sometimes you want the raw tool output returned straight to your caller instead, skipping that extra model round-trip. Setting `returnDirect = true` on a `@Tool` does exactly that.
- **Handling failures.** When a tool throws, Spring AI wraps it in a `ToolExecutionException`. By default the error *message* is sent back to the model so it can recover or explain the problem gracefully; you can instead make failures propagate as exceptions (`spring.ai.tools.throw-exception-on-error=true`) when you'd rather handle them in your own code.
- **Controlling the loop yourself.** The auto-registered advisor is one of several execution strategies. For full manual control you can drive the tool-calling loop directly against a `ChatModel`, executing calls and re-prompting until there are no more, but you'll reach for that rarely.

Keep in mind the safety guidance from the foundations section. The model decides which tools to call and with what arguments, and it can be steered by the user's input, so treat a tool call as untrusted input to your own code. Validate arguments, scope what each tool is permitted to do, and use `ToolContext` (not model parameters) for anything security-sensitive.

## What's Next

You've now seen how Spring AI turns the tool-calling pattern into ordinary Java. You write methods, describe them well with `@Tool` and `@ToolParam`, hand them to the `ChatClient`, and let the auto-registered `ToolCallingAdvisor` run the request-execute-respond loop for you. You use `ToolContext` to keep sensitive data out of the model's reach, and you can compose tools with RAG and everything else on the same client. In the next section you'll put this into practice, extending the support assistant with tools so it can move beyond explaining problems to actually resolving them.
