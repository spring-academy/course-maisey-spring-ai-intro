The patterns from the previous section are just shapes you build with the `ChatClient`, structured output, and advisors you already know. 

Spring AI is not at the same stage with all of them. Some patterns are already provided out of the box by the framework, and this article walks through those in detail. 

The remaining patterns exist as experimental implementations, and they are planned to become generally available with Spring AI 2.1. 
This article only gives you an overview of them, and not the implementation details - as those will probably change.

## Tool Search: Giving an Agent Hundreds of Tools

You know the problem from the previous section. As soon as an agent has a high number of tools, often because several MCP servers feed into it, sending every tool definition on every request costs a lot of tokens and makes the model pick the wrong tool more often. 

Spring AI's answer, generally available since the 2.0 release, is the **Tool Search Tool**. Instead of loading all tools upfront, the agent *discovers them on demand*.

### How it works

All your registered tools are indexed into a **`ToolIndex`**, but **not** sent to the model. On the first request the model sees only the Tool Search Tool. When it needs a capability, it calls that search tool  with a search query. The index returns the matching tools, and *their* full definitions are expanded into the next request, so the model now sees the search tool plus the handful of relevant tools, calls them, and produces its answer. Hundreds of tools become reachable while only a few definitions ever enter the context at a time.

This is delivered as an advisor, the **`ToolSearchToolCallingAdvisor`**, which extends the familiar `ToolCallingAdvisor` with the discovery step. You add the dedicated module:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tool-search-advisor</artifactId>
</dependency>
```

Then register the advisor and your tools as usual. Note the tools are configured on the client, but thanks to the advisor they aren't all shipped to the model:

```java
var advisor = ToolSearchToolCallingAdvisor.builder()
    .toolIndex(toolIndex)
    .build();

ChatClient chatClient = builder
    .defaultTools(supportTools)  // hundreds of tools registered, NOT all sent to the model
    .defaultAdvisors(advisor)    // activates the Tool Search Tool
    .build();

String answer = chatClient.prompt("""
        My Spring AI application fails to start after the upgrade to 2.0.
        Open a support ticket for it and tell me the ticket number.
        """)
    .call()
    .content();
```

### Choosing how tools are searched

How the search itself works is up to you. The `ToolIndex` interface hides the search implementation from the rest of the setup, and Spring AI ships three of them out of the box.

| Strategy | Implementation | Best for |
|----------|----------------|----------|
| **Semantic** | `VectorToolIndex` | Natural-language queries, fuzzy matching by meaning |
| **Keyword** | `LuceneToolIndex` | Exact term matching, known tool names |
| **Regex** | `RegexToolIndex` | Tool-name patterns like `get_*_data` |

The semantic `VectorToolIndex` works the same way as the retrieval step in the RAG section, only with tools instead of documents. Each tool description is turned into an embedding and stored in a vector store, the search query is embedded as well, and the tools that come back are the ones closest in meaning. The agent can ask for "something to open a ticket" and still find a tool called `createSupportCase`.

The `LuceneToolIndex` builds on [Apache Lucene](https://lucene.apache.org/), the open source search library that also powers Elasticsearch and Solr. It indexes the words of your tool names and descriptions and matches the query against those words, so there is no model call and no embedding involved, which makes it fast and cheap. The trade-off is that it only finds a tool when the query uses similar wording.

## Pattern Implementations That Are Not GA Yet

If you want to see the workflow patterns as running code rather than as diagrams, the Spring AI team wrote them up in [Building Effective Agents with Spring AI](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns), and each one has a complete sample implementation in the [agentic-patterns examples](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns) repository.

Several of the other patterns already have a ready-made implementation as well, a tool or an advisor you drop in instead of writing the pattern by hand. Those are maintained in the **`spring-ai-community`** project, most of them in the [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) repository.

Here is what already exists:

- **LLM-as-a-Judge / Self-Refine** — the evaluator-optimizer pattern packaged as a reusable advisor. Built on Spring AI's experimental *recursive advisors*, a `SelfRefineEvaluationAdvisor` generates a response, has a (separate, bias-avoiding) judge model rate it on a structured scale, and retries with the feedback until it passes. It turns "evaluate then improve" into a single drop-in advisor.
- **Skills** — a `SkillsTool` that lets an agent load reusable *knowledge modules* written as Markdown files with YAML front-matter. Skills are discovered by name and description at startup and their full instructions loaded only when semantically relevant, the same load-on-demand philosophy as Tool Search, applied to instructions rather than tools.
- **Plan and Execute** — a `TodoWriteTool` that lets the agent write its own task list and keep it current while it works. Every item has an id, a description, and a status of `todo`, `in_progress`, or `completed`, and only one item may be in progress at a time, which walks the model through the steps one after the other instead of letting it skip ahead. It needs chat memory, otherwise the list does not survive from one model call to the next, and you can register an event handler to stream each update to a user interface, which makes the plan from the previous section something you can actually watch happen.
- **Ask-User-Question** — an `AskUserQuestionTool` that puts a human in the loop. Instead of guessing at ambiguous instructions, the agent can pause to ask the user a structured question (with options or free text) and continue once answered, essential for high-stakes actions where you want confirmation, not assumption.
- **Subagents** — a `Task` tool that lets the main agent hand a piece of work to a separate agent with its own context window, its own system prompt, its own tools, and even its own model. The subagents are described in Markdown files with YAML front-matter, the same shape as Skills, and the main agent picks one by its description. Only the result comes back, so a long research or review step never clutters the main conversation, and a simple job can go to a cheap model while the hard analysis stays with a strong one.
- **Agent-to-Agent (A2A)** — `spring-ai-a2a` provides server-side support for exposing a Spring AI agent over the open **A2A protocol**, so agents in *different* systems can discover and delegate to one another. Where MCP connects an agent to *tools*, A2A connects an agent to *other agents*, the next layer of composition once a single agent isn't enough.

The Spring team introduced each one in an [ongoing blog series](https://spring.io/blog/2026/04/15/spring-ai-session-management#agentic-patterns-series) that has grown beyond the list above with long term memory and event sourced session management.