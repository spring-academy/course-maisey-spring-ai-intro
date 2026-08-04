## The Workflow Patterns

The fundamentals section explained the difference between workflows, where your code decides the steps, and agents, where the model decides. On the workflow side, a small set of recurring shapes covers most multi-step tasks.

**Chain** breaks a complex task into sequential steps and feeds the output of each model call into the next one. It is slower but more accurate. Use it when a task is too big for a single prompt but splits cleanly into steps, for example extract, then classify, then summarize.

![A chain of model calls where the output of each call becomes the input of the next one, with a gate in between that can stop the chain early](https://raw.githubusercontent.com/spring-academy/course-maisey-spring-ai-intro/refs/heads/main/metadata/lms/05-agentic-ai/05-agentic-patterns-fundamentals/assets/chain.svg)

**Parallelization** runs several independent model calls at the same time and combines the results. It is ideal when you need to analyze many items, or one item from several perspectives. For example, you can evaluate the impact of a change on customers, employees, and investors at the same time and then combine the answers.

![Several independent model calls that run at the same time and whose results are combined by an aggregator](https://raw.githubusercontent.com/spring-academy/course-maisey-spring-ai-intro/refs/heads/main/metadata/lms/05-agentic-ai/05-agentic-patterns-fundamentals/assets/parallelization.svg)

**Routing** uses a first model call to classify the input and then sends it to a specialized prompt, pipeline or model. A support assistant might send a "charged twice" message to a billing specialist prompt and a "build won't compile" message to a technical one. Each message is handled better than a single generic prompt could handle it.

![A router model call that classifies the input and sends it to one of several specialized prompts](https://raw.githubusercontent.com/spring-academy/course-maisey-spring-ai-intro/refs/heads/main/metadata/lms/05-agentic-ai/05-agentic-patterns-fundamentals/assets/routing.svg)

**Orchestrator-Workers** handles tasks whose subtasks cannot be predicted upfront. An orchestrator call splits the problem into subtasks at runtime, workers handle them, often in parallel, and a final call combines the results. This is more dynamic than a fixed chain because the model decides how to split the work.

![An orchestrator model call that splits a task into subtasks at runtime, workers that handle them, and a synthesizer that combines the results](https://raw.githubusercontent.com/spring-academy/course-maisey-spring-ai-intro/refs/heads/main/metadata/lms/05-agentic-ai/05-agentic-patterns-fundamentals/assets/orchestrator-workers.svg)

**Evaluator-Optimizer** introduces a feedback loop. One call generates a response, and another call evaluates it against your criteria. If the response is not good enough, the feedback is used for an improved retry. This repeats until the response passes or a limit is reached. It is the evaluation idea from the testing section turned into an improvement loop at runtime.

![A generator model call and an evaluator model call, where a rejected response goes back to the generator together with the feedback](https://raw.githubusercontent.com/spring-academy/course-maisey-spring-ai-intro/refs/heads/main/metadata/lms/05-agentic-ai/05-agentic-patterns-fundamentals/assets/evaluator-optimizer.svg)

All of these are composition patterns. Pick the simplest one that fits, and move to the next one only when the task demands it. Beyond them sits the fully autonomous agent, which is the tool-calling loop directing itself.

## Patterns for a Single Agent

The workflow patterns describe how you combine several model calls. A second group of patterns describes how you shape one agent. They became popular because an autonomous agent runs into the same few problems in almost every project. The context window fills up, the model gets confused by too many choices, and nobody wants a system that takes risky actions without asking. Each pattern below is an answer to one of those problems.

**Persona** gives the agent a role, a tone, and a set of rules in the system prompt. The problem it solves is that a model without a role produces generic answers and behaves a little differently on every request. A prompt like "You are a support agent for an online shop. You are friendly, you never promise a refund, and you always ask for the order number first" makes the answers consistent and keeps the agent inside the boundaries of its job. A persona is the cheapest way to control behaviour, so try it before you reach for anything more complex.

**Agent Skills** are reusable instruction packages that the agent loads only when it needs them. The problem they solve is that an agent often has to know a lot. How your company formats invoices, how a refund is approved, how a report should look. If you put all of that into the system prompt, you waste context on every request and the model has trouble finding the part that matters. With skills, each package is a small file with a name and a short description. Only the names and descriptions are loaded at the start, and the full instructions are loaded when the task matches. This idea is called progressive disclosure. The agent knows what it could learn, and learns the details on demand.

**Tool Search** applies the same idea to tools instead of instructions. The problem it solves is that an agent is only as capable as the tools it can reach, but sending hundreds of tool definitions with every request costs a lot of tokens and makes the model pick the wrong tool more often. Instead of loading everything upfront, the agent gets one search tool and looks up the others when it needs them. 

**Human-in-the-Loop** puts a person into the agent's decision path. There are two common forms. The agent can ask a question when the input is ambiguous, instead of guessing, and the agent can ask for approval before an action that is expensive or hard to undo, like sending an email or deleting a record. The problem it solves is that autonomy and risk grow together. A confident wrong guess is worse than a short question, and an approval step lets you give an agent real permissions without giving up control.

**Least Privilege and Guardrails** limit what the agent can do at all. A human approval step protects against the actions you thought about. Guardrails protect against the ones you did not. Give the agent read only tools where reading is enough, restrict a tool to the current user's own data, validate the arguments a tool receives, and check the output before it reaches the user. The problem this solves is that the model is not a trusted component. Its instructions can come from a document it just read or a website it just visited, so the safe design assumes the agent will at some point be talked into doing the wrong thing.

**Plan and Execute** asks the model to write down a list of steps before it starts working, and then to work through the list and mark the steps as done. The problem it solves is that agents lose track during long tasks. They forget a subtask, repeat one they already finished, or stop too early. A visible plan also helps you, because you can see what the agent intends to do before it does it.

**Context Management** keeps the conversation inside the context window during long runs. Chat memory alone is not enough, because a long agent run collects tool results, errors, and intermediate output. Common techniques are summarizing older messages into a short recap, dropping large tool results once they have been used, and storing the details outside the context in a file or a vector store that the agent can read again when needed. The problem it solves is that quality drops and cost rises as the context grows, long before the hard limit is reached.

**Subagents** move a part of the work into a separate agent with its own context, its own tools, and its own prompt. The main agent gives it a task and receives only the result. The problem this solves is twofold. A large research step would otherwise fill the main context with material that is only needed once, and one agent with tools for many different jobs is harder to control than several small agents that each do one job well. This is the orchestrator-workers pattern from above, applied to agents instead of single model calls. When those agents live in different systems, a protocol like Agent-to-Agent (A2A) lets them find and call each other, in the same way MCP lets one agent reach external tools.

Just like the workflow patterns, none of these are required from the start. Add a pattern when you feel the problem it solves. The next section looks at what Spring AI provides for some of them, starting with Tool Search.
