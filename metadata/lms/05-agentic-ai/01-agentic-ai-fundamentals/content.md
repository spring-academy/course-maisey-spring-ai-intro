Every technique so far has been a single step. You send a prompt and get an answer. RAG enriched that step with context, and tools let it reach outside, but it was still one model call wrapped in your code. Real problems are rarely one step. "Triage this support ticket, research the customer's history, draft a reply, and check it's accurate before sending" is a sequence of decisions, and the system should make some of them on its own.

Systems that use a model to do multi-step work are called **agentic**. The word covers a wide range of designs, and the most important skill in this space is not building the most advanced agent. It is knowing how much agency a task actually needs. Spring AI follows the advice from Anthropic's "Building Effective Agents" guide. Start simple, and add autonomy only when it provides clear value.

## Two Approaches to Orchestrate LLMs and Tools

This advice is based on an important distinction.

- **Workflows** are systems where LLMs and tools are orchestrated through predefined code paths. You decide the steps, and the model provides the intelligence at each one. The control flow lives in your Java code, so it is predictable, testable, and repeatable.
- **Pure Agents** are systems where LLMs dynamically direct their own processes and tool usage. The model decides what to do next, which tool to call, and whether it is finished. This is more flexible, but less predictable.

The trade-offs between the two are worth keeping in mind.

| | Workflows | Pure Agents |
| --- | --- | --- |
| How it works | LLMs and tools are orchestrated through predefined code paths | The LLM dynamically directs its own processes and tool usage |
| Who decides the steps | You do, in your own code | The model does, while it runs |
| Output | Predictable and consistent | Flexible and adaptive for open-ended problems |
| Best suited for | Well-defined, repeatable tasks | Tasks where the steps cannot be predicted in advance, in trusted environments |
| Debugging and testing | Easier to debug and test | Harder to debug and control, and needs extensive testing and guardrails |
| Cost and latency | Lower | Higher |
| Main drawback | Inflexible, cannot adapt to unforeseen subtasks, and the task has to be broken down upfront | Errors from one step can compound into the next |

Many developers want to start with a fully autonomous agent. But, while fully autonomous agents might seem appealing, workflows often provide better predictability and consistency for well-defined tasks. This aligns perfectly with enterprise requirements where reliability and maintainability are crucial.

## The Autonomous Agent You've Already Built

You may not have noticed it, but you have already run an autonomous agent. The tool-calling loop from the tool calling section is the minimal agent. The model is given tools, calls them, sees the results, and decides what to do next, until it judges the task complete. Give that loop RAG and a handful of tools, and it becomes a capable assistant that plans its own path through a request.

Two things turn this minimal agent into one that is ready for production. It needs to reach capabilities outside your application, and it needs to combine model calls in the right way for the task. The next sections about the **Model Context Protocol (MCP)**, and **agentic patterns** cover both.