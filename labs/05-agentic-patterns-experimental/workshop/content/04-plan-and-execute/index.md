---
title: Plan and Execute
---

The skill from the previous section already needs more than one step. Look up the advisories, check which versions exist, and open a ticket for the upgrade. The model does all of that in a single stretch today, and if it drops a step on the way there is nothing that notices.

The `TodoWriteTool` gives it a place to write the steps down first and to keep that list current while it works.

## Register the Tool

The tool needs no configuration, so it joins the other tools with one line.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 3
description: Register the TodoWriteTool
cascade: true
text: |2
  import org.springaicommunity.agent.tools.TodoWriteTool;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ShellTools.builder().build())"
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
  ShellTools.builder().build(),
                          TodoWriteTool.builder().build())
```

There is no list to define and no steps to write. The plan is the work of the model, and the tool only stores it. The article mentions a `todoEventHandler` on this builder as well, which hands you every update so you can render a live plan in a user interface. This lab reads the plan from the logs instead, so the builder stays empty.

The one thing the tool depends on is already in place.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "MessageChatMemoryAdvisor.builder(chatMemory).build(),"
description: The list lives in the conversation
```

A plan that is forgotten between two model calls is worthless, so the tool needs chat memory. Your assistant has had it since the advisors lab, and the list is stored as tool messages in that same conversation.

## Test It

After DevTools restarted the application with the changes, ask for something that has several steps, and name the tool you want it to plan with.

```terminal:execute
command: |
  curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=We run spring-boot 3.2.4. Use the TodoWrite tool to plan the work, then work through that plan: check whether that version has known vulnerabilities, find out which versions we could upgrade to, and open a high-priority ticket for the upgrade."
session: 1
```

Naming the tool is what makes the difference here. The tool is offered on every request, but this assistant is told to answer questions with a short explanation, so left to itself the model works through the steps in one stretch and never writes anything down. In a real application the instruction to plan belongs in the system prompt, where it applies to every request and the user never has to know that the tool exists.

In the logs you can watch the plan being written and worked off.

1. The model calls `TodoWrite` once with the whole list, every item `pending`.
2. Before each step it calls `TodoWrite` again and flips that one item to `in_progress`.
3. It does the actual work, the skill lookup, the MCP release call, the ticket.
4. After each step another `TodoWrite` marks the item `completed` and starts the next one.

The pattern to look for is that only one item is ever `in_progress`. That is what keeps the model from jumping ahead to the ticket before it knows which version to recommend.

Now ask something that is a single step.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What are the key features of VMware Tanzu Spring?"
session: 1
```

No list is written. The tool describes itself as being for tasks with three or more steps, so the model skips it when there is nothing to plan, and you do not pay for the extra calls on simple questions.
