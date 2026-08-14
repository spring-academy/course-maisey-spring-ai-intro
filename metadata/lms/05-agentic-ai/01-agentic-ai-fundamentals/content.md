Every technique so far has worked in a single step. You send a prompt and you get an answer. RAG enriched that step with context, and tools let the model reach outside your application, but it was still one model call wrapped in your own code. Real problems rarely fit into one step. A request like "Triage this support ticket, research the customer's history, draft a reply, and check that it is accurate before sending" is a sequence of decisions, and the system should be able to make some of them on its own.

Systems that use a model to do multi-step work are called **agentic**. The term covers a wide range of designs, from a few model calls that you chain together, up to a system that plans its own work. The most important skill in this space is not building the most advanced agent. It is judging how much autonomy a task actually needs. Spring AI follows the advice from the "Building Effective Agents" guide by Anthropic. Start simple, and add autonomy only when it brings clear value.

## Two Ways to Orchestrate LLMs and Tools

This advice rests on an important distinction between two kinds of systems.

- **Workflows** are systems where LLMs and tools are orchestrated through predefined code paths. You decide the steps, and the model provides the intelligence at each one. The control flow lives in your Java code, so it is predictable, testable, and repeatable.
- **Pure agents** are systems where LLMs direct their own processes and tool usage. The model decides what to do next, which tool to call, and when the task is finished. This is far more flexible, but also far less predictable.

Both approaches have their place, so the trade-offs between them are worth keeping in mind.

| | Workflows | Pure Agents |
| --- | --- | --- |
| How it works | LLMs and tools are orchestrated through predefined code paths | The LLM directs its own processes and tool usage while it runs |
| Who decides the steps | You do, in your own code | The model does, while it runs |
| Output | Predictable and consistent | Flexible and adaptive for open ended problems |
| Best suited for | Well defined, repeatable tasks | Tasks whose steps cannot be predicted in advance, in trusted environments |
| Debugging and testing | Easier to debug and test | Harder to debug and control, and needs extensive testing and guardrails |
| Cost and latency | Lower | Higher |
| Main drawback | Inflexible, cannot adapt to unforeseen subtasks, and the task has to be broken down upfront | Errors from one step can compound into the next |

Many developers want to start with a fully autonomous agent, because it is the most exciting option. But for well defined tasks a workflow usually gives you better predictability and consistency at a lower cost, which matches what enterprise applications need most, namely reliability and maintainability.

## The Autonomous Agent You Have Already Built

You may not have noticed it, but you have already run an autonomous agent. The tool calling loop from the tool calling section is the simplest form of one. The model receives a set of tools, calls them, sees the results, and decides what to do next, until it considers the task complete. Add RAG and a handful of tools to that loop, and you have an assistant that finds its own way through a request.

So the loop itself is not the hard part. Two questions decide whether such a system is useful in production. What can the model reach, and how do you combine model calls for the task at hand? Everything that wraps around the loop and answers those questions is usually called the harness of the agent. A plain model call only becomes a working agent once such a harness surrounds it, and MCP and agentic patterns are its two biggest pieces. The next two sections take one question each.

The **Model Context Protocol (MCP)** answers the first question. Every tool you have written so far is compiled into your own application, so your agent can only reach what you built for it. MCP turns a tool into something you connect instead of something you code, and every MCP compatible application can use the same tool. This is also one reason why agentic AI became popular so quickly. The tool calling loop had been possible for a while, but it only became impressive once agents could reach ticket systems, repositories, wikis, browsers, and databases through one standard protocol. MCP made that reach cheap, and that is when many people saw for the first time how much a simple loop can really do.

**Agentic patterns** answer the second question. They are proven ways to arrange model calls, from a fixed chain of steps that your code controls up to an agent that directs itself, together with the practices that keep such an agent reliable. This is where you decide how much autonomy your own task needs.
