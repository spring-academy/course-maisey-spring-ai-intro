## The Workflow Patterns

The fundamentals section explained the difference between workflows, where your code decides the steps, and agents, where the model decides. On the workflow side, a small set of recurring shapes covers most multi-step tasks. You do not need a library for them. Each one is a small amount of code around the `ChatClient`.

**Chain** breaks a complex task into sequential steps and feeds the output of each model call into the next one. It is slower but more accurate. Use it when a task is too big for a single prompt but splits cleanly into steps, for example extract, then classify, then summarize.

**Parallelization** runs several independent model calls at the same time and combines the results. It is ideal when you need to analyze many items, or one item from several perspectives. For example, you can evaluate the impact of a change on customers, employees, and investors at the same time and then combine the answers.

**Routing** uses a first model call to classify the input and then sends it to a specialized prompt or pipeline. A support assistant might send a "charged twice" message to a billing specialist prompt and a "build won't compile" message to a technical one. Each message is handled better than a single generic prompt could handle it.

**Orchestrator-Workers** handles tasks whose subtasks cannot be predicted upfront. An orchestrator call splits the problem into subtasks at runtime, workers handle them, often in parallel, and a final call combines the results. This is more dynamic than a fixed chain because the model decides how to split the work.

**Evaluator-Optimizer** introduces a feedback loop. One call generates a response, and another call evaluates it against your criteria. If the response is not good enough, the feedback is used for an improved retry. This repeats until the response passes or a limit is reached. It is the evaluation idea from the testing section turned into an improvement loop at runtime. Spring AI's structured output works well here, because the evaluator can return a typed verdict like `record EvaluationResponse(int rating, String feedback)` that your loop can branch on.

All of these are composition patterns, not products. Pick the simplest one that fits, and move to the next one only when the task demands it. Beyond them sits the fully autonomous agent, which is the tool-calling loop directing itself. The next section looks at what Spring AI adds on top of these patterns, starting with the problem such an agent runs into once you give it many tools.
