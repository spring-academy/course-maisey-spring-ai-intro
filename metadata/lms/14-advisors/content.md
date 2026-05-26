## What are Advisors?

**Advisors** intercept and modify requests and responses in the Spring AI chat pipeline. They work like a chain of filters — similar to servlet filters or Spring AOP advice — each one processing the request before it reaches the model and the response after it comes back.

```
User Request → [Advisor 1] → [Advisor 2] → [Advisor 3] → Chat Model → LLM
                                                               |
Response    ← [Advisor 3] ← [Advisor 2] ← [Advisor 1] ← Chat Model
```

Spring AI provides two advisor interfaces:

```java
// For blocking calls
public interface CallAdvisor extends Advisor {
    ChatClientResponse adviseCall(
        ChatClientRequest request, CallAdvisorChain chain);
}

// For streaming calls
public interface StreamAdvisor extends Advisor {
    Flux<ChatClientResponse> adviseStream(
        ChatClientRequest request, StreamAdvisorChain chain);
}
```

Each advisor can modify the request before passing it on, modify the response before returning it, block the request entirely (e.g., for safety), or share state with other advisors via the `advise-context` map. Advisors implement Spring's `Ordered` interface — lower values execute first on the way in and last on the way out.

## Built-in Advisors

### Chat Memory

Maintaining conversation history across turns requires an advisor:

| Advisor | How it works |
|---------|-------------|
| `MessageChatMemoryAdvisor` | Appends conversation history as message objects to the prompt |
| `PromptChatMemoryAdvisor` | Incorporates history into the system text |
| `VectorStoreChatMemoryAdvisor` | Retrieves relevant past turns from a vector store |

`MessageChatMemoryAdvisor` is the most common choice. It requires a `ChatMemory` instance (e.g., `InMemoryChatMemory`) and a per-conversation `conversationId` to scope the history:

```java
chatClient.prompt()
    .user("Remember my name is Alice")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user-123"))
    .call()
    .content();
```

### SimpleLoggerAdvisor

Logs request and response data for debugging. Enable with `logging.level.org.springframework.ai.chat.client.advisor=DEBUG`.

### SafeGuardAdvisor

Filters requests to prevent harmful or inappropriate content.

### ReReadingAdvisor

Implements the RE2 (Re-Reading) strategy that improves LLM reasoning by repeating the question in the prompt.

### Combining Advisors

Recommended ordering when using multiple advisors:

```java
ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 1. conversation history
        QuestionAnswerAdvisor.builder(vectorStore).build(),     // 2. RAG context
        new SimpleLoggerAdvisor()                               // 3. logging (last)
    )
    .build();
```

## AI Agent Patterns

The Advisor concept and tool calling together enable **agentic patterns**. Drawn from Anthropic's research on building effective agents, Spring AI distinguishes between:

- **Workflows** — code paths where LLMs are orchestrated through fixed steps
- **Agents** — LLMs dynamically directing their own process and tool usage

Key principle: **start simple, add complexity only when needed**. Most applications don't need full autonomous agents.

### Common Patterns

**Chain Workflow** — break a task into sequential LLM calls where each output feeds the next. Use when steps are clear and each has a focused responsibility.

**Routing Workflow** — classify the input first, then dispatch to a specialized handler with its own prompt. Use for requests that fall into distinct categories requiring different treatment.

**Parallelization** — run multiple LLM calls concurrently and aggregate results. Use for large volumes of similar items or multiple independent perspectives.

**Orchestrator-Workers** — a central LLM plans tasks and delegates to worker LLMs. Use when subtasks can't be predicted upfront.

**Evaluator-Optimizer** — one LLM generates output, another evaluates it; loop until quality criteria are met.

**The Agentic Loop** — the model operates in a loop, using tools until the task is complete. You already saw this with tool calling — Spring AI handles the loop automatically.

## Advanced Capabilities (spring-ai-agent-utils)

The Spring AI community provides an experimental `spring-ai-agent-utils` toolkit with additional patterns:

- **Agent Skills** — portable, composable folders of instructions that agents discover and load on demand
- **AskUserQuestionTool** — human-in-the-loop: the agent pauses and asks for clarification instead of guessing
- **TodoWriteTool** — structured task tracking to maintain working memory across steps
- **Dynamic Tool Discovery** — agents receive only a search tool initially and discover specific tools on demand (34–64% token savings)
- **A2A Protocol** — multi-agent collaboration where specialized agents discover and coordinate with each other
