The patterns from the previous section are just shapes you build with the `ChatClient`, the structured output, and the advisors you already know.

Spring AI is continuously evolving. Some patterns are already provided out of the box, and this article walks through those in detail. The remaining ones exist as experimental implementations and are planned to become generally available in future releases. For those, the article shows what each one provides and how you would use it in code, but treat the samples as a first impression rather than as a stable API, because they will probably still change.

## Giving an Agent Hundreds of Tools With Tool Search

You know the problem from the previous section. As soon as an agent has a high number of tools, often because several MCP servers feed into it, sending every tool definition on every request costs a lot of tokens and makes the model pick the wrong tool more often.

The answer of Spring AI, generally available since the 2.0 release, is the **Tool Search Tool**. Instead of loading all tools upfront, the agent *discovers them on demand*.

### How it works

All your registered tools are indexed into a **`ToolIndex`**, but they are **not** sent to the model. On the first request the model sees only the Tool Search Tool. When it needs a capability, it calls that search tool with a search query. The index returns the matching tools, and *their* full definitions are expanded into the next request, so the model now sees the search tool plus the handful of relevant tools, calls them, and produces its answer. Hundreds of tools become reachable while only a few definitions ever enter the context at a time.

This is delivered as an advisor, the **`ToolSearchToolCallingAdvisor`**, which extends the familiar `ToolCallingAdvisor` with the discovery step. You add the dedicated module.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tool-search-advisor</artifactId>
</dependency>
```

Then you register the advisor and your tools as usual. Note that the tools are configured on the client, but thanks to the advisor they are not all shipped to the model.

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
| **Semantic** | `VectorToolIndex` | Natural language queries, fuzzy matching by meaning |
| **Keyword** | `LuceneToolIndex` | Exact term matching, known tool names |
| **Regex** | `RegexToolIndex` | Tool name patterns such as `get_*_data` |

The semantic `VectorToolIndex` works the same way as the retrieval step in the RAG section, only with tools instead of documents. Each tool description is turned into an embedding and stored in a vector store, the search query is embedded as well, and the tools that come back are the ones closest in meaning. The agent can ask for "something to open a ticket" and still find a tool called `createSupportCase`.

The `LuceneToolIndex` builds on [Apache Lucene](https://lucene.apache.org/), the open source search library that also powers Elasticsearch and Solr. It indexes the words of your tool names and descriptions and matches the query against those words, so there is no model call and no embedding involved, which makes it fast and cheap. The trade-off is that it only finds a tool when the query uses similar wording.

## Implementations for the Workflow Patterns (Experimental)

If you want to see the workflow patterns as running code rather than as diagrams, the Spring AI team wrote them up in [Building Effective Agents with Spring AI](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns), and each one has a complete sample implementation in the [agentic-patterns examples](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns) repository.

### LLM as a Judge and Self-Refine

The evaluator-optimizer pattern is packaged as a reusable advisor. Built on the experimental *recursive advisors* of Spring AI, a [`SelfRefineEvaluationAdvisor`](https://spring.io/blog/2025/11/10/spring-ai-llm-as-judge-blog-post) generates a response, has a separate judge model rate it on a structured scale, and retries with that feedback until it passes. Using a different model as the judge avoids the bias a model has towards its own output. Because it is an advisor, you add it with `defaultAdvisors` and the whole evaluate and improve loop happens inside a single `call()`.

## Agentic Patterns in the spring-ai-community Project (Experimental)

Several of the other patterns already have a ready-made implementation as well. Those are maintained in the **`spring-ai-community`** project, and most of them in the [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) repository.

They are plain tools, so you register them on the `ChatClient` the same way you registered your own ticket tools. A BOM and a single dependency bring all of them in, with the Agent-to-Agent (A2A) support as the only exception, because that one ships as artifacts of its own.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agent-utils-bom</artifactId>
            <version>0.10.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils</artifactId>
</dependency>
```

The Spring team introduced each pattern in an [ongoing blog series](https://spring.io/blog/2026/04/15/spring-ai-session-management#agentic-patterns-series), which is the place to look for the full picture. Here is what already exists and how you would use it.

### Agent Skills

The [`SkillsTool`](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills) points at one or more directories of skills. Each skill is a folder with a `SKILL.md` file, and the name and the description from its YAML front matter are all that is loaded at startup. The full instructions follow only when the task matches, which is the same load on demand idea as Tool Search, applied to instructions instead of tools.

```java
@Value("classpath:skills")
private Resource skillsResource;

ChatClient chatClient = builder
    .defaultToolCallbacks(SkillsTool.builder().addSkillsResource(skillsResource).build()
    .defaultTools(FileSystemTools.builder().build(), ShellTools.builder().build())
    .build();
```

A skill can also ship reference files and scripts, so it is usually combined with the `FileSystemTools` and `ShellTools` from the same library, which let the agent read those files and run those scripts. You can also point at a directory on disk instead of a classpath resource, for example with `.addSkillsDirectory(".claude/skills")`.

### Plan and Execute

The [`TodoWriteTool`](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite) lets the agent write its own task list and keep it current while it works. Every item on that list has three fields. The `content` field says what needs to be done, `activeForm` is the same step worded as something in progress so it reads well while the agent works on it, and `status` is one of `pending`, `in_progress`, or `completed`. Only one item may be in progress at a time, which walks the model through the steps one after the other instead of letting it skip ahead. The tool describes itself as being for tasks with three or more steps, so the model decides on its own when a written plan is worth the effort.

```java
ChatClient chatClient = builder
    .defaultTools(TodoWriteTool.builder()
        .todoEventHandler(todos -> ...)
        .build())
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
            MessageWindowChatMemory.builder().build())
        .build())
    .build();
```

Two things matter here. Chat memory is required, because without it the list does not survive from one model call to the next. The event handler is optional and hands you every update of the list and its status, so you can show the live plan in a user interface.

### Ask-User-Question

The [`AskUserQuestionTool`](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool) puts a human in the loop. Instead of guessing at an ambiguous instruction, the agent asks a structured question with two to four options, and you decide how that question reaches the user.

```java
ChatClient chatClient = builder
    .defaultTools(AskUserQuestionTool.builder()
        .questionHandler(this::askUser)
        .build())
    .build();

Map<String, String> askUser(List<Question> questions) {
    // show the question and its options, then return one answer per question
}
```

The handler is a normal Java method, so a command line application can read from the console. A web application has to bridge the gap to an asynchronous user interface, usually with a `CompletableFuture` that the handler waits on until the answer arrives over WebSocket or REST.

### Subagents

The [Task tool](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) lets the main agent hand a piece of work to a separate agent with its own context window, its own system prompt, its own tools, and even its own model. Only the result comes back, so a long research or review step never clutters the main conversation, and a simple job can go to a cheap model while the hard analysis stays with a strong one.

```java
ChatClient chatClient = builder
    .defaultTools(TaskTool.builder()
        .subagentTypes(ClaudeSubagentType.builder().build())
        .build())
    .build();
```

The subagents themselves are Markdown files in an `agents` directory, the same shape as Skills, with a name, a description that tells the main agent when to delegate, a list of allowed and forbidden tools, and the model to use. The main agent picks one by its description.

### Agent-to-Agent (A2A)

Where MCP connects an agent to tools, [A2A](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration) connects an agent to other agents, which is the next step once your subagents no longer live in the same application. The protocol itself is implemented by the A2A Java SDK, and [spring-ai-a2a](https://github.com/spring-ai-community/spring-ai-a2a) puts a Spring Boot layer on top of it, so you declare beans instead of writing protocol code.

The server side is a dependency of its own, and it is not part of the BOM above.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-a2a-server-autoconfigure</artifactId>
    <version>0.3.0</version>
</dependency>
```

With it on the classpath you provide two beans. The `AgentCard` is the description other systems read before they talk to you, with a name, a URL, the protocol version, and the list of skills your agent offers. The `AgentExecutor` is what actually answers a request, and `DefaultAgentExecutor` already implements it on top of a `ChatClient`, so all you write is the lambda that pulls the text out of the incoming message and calls your client.

```java
@Bean
AgentCard agentCard(@Value("${server.port:8080}") int port) {
    return new AgentCard.Builder()
        .name("Support Agent")
        .description("Answers Spring AI questions and opens support tickets")
        .url("http://localhost:" + port + "/a2a/")
        .version("1.0.0")
        .capabilities(new AgentCapabilities.Builder().streaming(false).build())
        .skills(Collections.emptyList())
        .defaultInputModes(List.of("text"))
        .defaultOutputModes(List.of("text"))
        .build();
}

@Bean
AgentExecutor agentExecutor(ChatClient chatClient) {
    return new DefaultAgentExecutor(chatClient, (chatClient, requestContext) -> {
        String userMessage = DefaultAgentExecutor.extractTextFromMessage(requestContext.getMessage());
        return chatClient.prompt().user(userMessage).call().content();
    });
}
```

The autoconfiguration does the rest. It publishes the card under `/.well-known/agent-card.json` for discovery, accepts the JSON-RPC messages of the protocol, and routes each of them through your `AgentExecutor`.

On the client side there is no autoconfiguration, so you add the `a2a-java-sdk-client` artifact and work with the SDK directly. `A2A.getAgentCard` fetches the card of a remote agent from its well known URL, and `Client.builder(agentCard)` gives you the connection you send messages over. The trick is to wrap that call in an ordinary `@Tool` method, because then delegating to a remote agent looks like any other tool call and the model decides on its own which agent to route to.

```java
@Service
public class RemoteAgentConnections {
    @Tool(description = "Sends a task to a remote agent. Use this to delegate work to specialized agents.")
    String sendMessage(@ToolParam(description = "The name of the agent") String agentName,
                    @ToolParam(description = "The task description to send") String task) {
        // build a Message, send it with the SDK Client, and return the answer
    }
}
```

```java
ChatClient chatClient = builder
    .defaultSystem(promptListingTheRemoteAgents)
    .defaultTools(remoteAgentConnections)
    .build();
```

The series has grown beyond this list as well, with long term memory through `AutoMemoryTools` and an event sourced Session API that is meant to replace `ChatMemory`.
