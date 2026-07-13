---
title: Advisors
---

In the article you met the **advisor**, an interceptor that wraps a `ChatClient` call. It runs *before* the request reaches the model and *after* the response comes back, and several advisors form a chain. 

## Write a Custom Logging Advisor

A blocking advisor implements the `CallAdvisor` interface. There is one method to fill in, `adviseCall`, plus a name and an order. Inside `adviseCall` you do your *before* work, call `chain.nextCall(request)` to pass control down the chain toward the model, and then do your *after* work with the response.

Create the advisor class:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
description: Create a custom logging advisor
text: |
  package com.example.support_assistant;

  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.ai.chat.client.ChatClientRequest;
  import org.springframework.ai.chat.client.ChatClientResponse;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
  import org.springframework.core.Ordered;

  class LoggingAdvisor implements CallAdvisor {

      private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

      @Override
      public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
          log.info("Request to model: {}", request.prompt().getContents());   // before
          ChatClientResponse response = chain.nextCall(request);              // delegate down the chain
          log.info("Response from model: {}",
                  response.chatResponse().getResult().getOutput().getText()); // after
          return response;
      }

      @Override
      public String getName() {
          return "LoggingAdvisor";
      }

      @Override
      public int getOrder() {
          return Ordered.LOWEST_PRECEDENCE;
      }
  }
```

Three things to notice. The `request.prompt()` gives you the full `Prompt` on the way in, so you can inspect or even change it before the model sees it. The call to `chain.nextCall(request)` is what invokes the rest of the chain and, eventually, the model. And `getOrder()` decides where this advisor sits in the chain, where a lower value runs earlier on the way in. We return `Ordered.LOWEST_PRECEDENCE` so the logger runs last on the way in and logs the final request, after any other advisor has changed it.

## Register the Advisor

An advisor does nothing until it is on a `ChatClient`. Add it as a default on the client bean, next to the default system prompt, so every call through that client passes through it.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultSystem(systemPrompt)"
before: 0
after: 0
description: Register the logging advisor on the ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(new LoggingAdvisor())
```

Call the endpoint again:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

Now check the application logs in the second terminal. You'll see two lines from `LoggingAdvisor`, one with the request sent to the model and one with the response that came back. The advisor ran around the call without any change to the service or controller code.

## Swap in the Built-in `SimpleLoggerAdvisor`

Logging a request and response is such a common need that Spring AI already ships an advisor for it, the **`SimpleLoggerAdvisor`**. It does exactly what your custom advisor does, so there is no reason to maintain your own. Replace your advisor with the built-in one.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultAdvisors(new LoggingAdvisor())"
before: 0
after: 0
description: Use the built-in SimpleLoggerAdvisor
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
text: |2
                  .defaultAdvisors(new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE))
```

Add the import for it:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "import org.springframework.ai.chat.client.ChatClient;"
before: 0
after: 0
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
  import org.springframework.core.Ordered;
```

The `SimpleLoggerAdvisor` logs at `DEBUG` level, so it stays quiet in production by default. Turn on `DEBUG` for the advisor package so you can see its output:

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: Enable DEBUG logging for the advisor package
text: |
  logging.level.org.springframework.ai.chat.client.advisor=DEBUG
```

Your custom class is no longer used, so remove it to keep the project clean:
```terminal:execute
command: rm ~/sample-app/src/main/java/com/example/support_assistant/LoggingAdvisor.java
description: Delete the custom logging advisor
```

Call the endpoint one more time:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

Check the second terminal again. This time the `request:` and `response:` lines come from `SimpleLoggerAdvisor`, with the full request and response formatted for you. Same behavior, no custom code to maintain.

## Add Conversation Memory

Each call so far has been stateless. The model has no idea what was asked before, so it cannot follow up on an earlier answer. The built-in **`MessageChatMemoryAdvisor`** fixes that. It stores the messages of a conversation and adds the earlier turns back into the prompt on the next call, so the model can see the history.

The advisor needs somewhere to keep the messages, a `ChatMemory`. Spring AI auto-configures an in-memory `ChatMemory` bean for you, so there is nothing to set up. Inject it into the configuration and register the advisor next to the logger.

Inject the `ChatMemory` bean into the method:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: 'import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;'
before: 0
after: 0
description: Inject the ChatMemory bean
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |
  import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
  import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
  import org.springframework.ai.chat.memory.ChatMemory;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: '@Value("classpath:/prompts/system-prompt.st") Resource systemPrompt) {'
before: 0
after: 0
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
              @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt,
              ChatMemory chatMemory) {
```

Add the memory advisor to the chain:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultAdvisors(new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE))"
before: 0
after: 0
description: Register the MessageChatMemoryAdvisor
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
                  .defaultAdvisors(
                          new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),
                          MessageChatMemoryAdvisor.builder(chatMemory).build())
```

Because the logger uses `Ordered.LOWEST_PRECEDENCE`, it runs last on the way in, after the memory advisor has added the history. That means the logged request shows the full conversation, which is handy for confirming memory works.

## Tell the Advisor Which Conversation

Memory only makes sense per conversation. The advisor reads a **conversation id** from the request, so it knows whose history to load and where to store the new messages. You pass that id per call as an advisor parameter, using the `ChatMemory.CONVERSATION_ID` key.

Update the service so it takes a conversation id and sets it on the call:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "import org.springframework.ai.chat.client.ChatClient;"
before: 0
after: 0
description: Pass the conversation id to generateResponse
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
cascade: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.memory.ChatMemory;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "SupportResponse generateResponse(String query) {"
before: 0
after: 7
cascade: true
hidden: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query, String conversationId) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                  .call()
                  .entity(SupportResponse.class);
      }
```

## Provide or Generate a Conversation Id

The conversation id comes from the client. Let the endpoint read it from an optional `X-Conversation-Id` request header. When the client sends one, you reuse it and continue that conversation. When it does not, you generate a fresh id. Either way you return the id in the `X-Conversation-Id` response header, so the client knows which value to send back on the next request. The same header name carries the id in both directions.

Update the chat endpoint:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "import org.springframework.web.bind.annotation.RestController;"
before: 0
after: 0
description: Read or generate the conversation id on the chat endpoint
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
cascade: true
text: |
  import org.springframework.web.bind.annotation.RestController;
  import org.springframework.web.bind.annotation.RequestHeader;
  import org.springframework.http.ResponseEntity;
  import java.util.UUID;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: "SupportResponse chat(@RequestParam String query) {"
before: 0
after: 2
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      ResponseEntity<SupportResponse> chat(@RequestParam String query,
              @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId) {
          String id = (conversationId != null) ? conversationId : UUID.randomUUID().toString();
          SupportResponse response = service.generateResponse(query, id);
          return ResponseEntity.ok()
                  .header("X-Conversation-Id", id)
                  .body(response);
      }
```

## See Memory in Action

First call the endpoint without a conversation id and pass `-i` so `curl` prints the response headers:
```execute
curl -i -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

Notice the generated `X-Conversation-Id` header in the response. That is the id the client would send back to continue the conversation.

Now run a two turn conversation, sending the same id in the `X-Conversation-Id` header on both calls. First an opening question:
```execute
curl -G "http://localhost:8080/api/v1/chat" -H "X-Conversation-Id: 123e4567-e89b-12d3-a456-426614174000" --data-urlencode "query=Tell me about Spring AI"
```

Then a follow up in the same conversation:
```execute
curl -G "http://localhost:8080/api/v1/chat" -H "X-Conversation-Id: 123e4567-e89b-12d3-a456-426614174000" --data-urlencode "query=What did I just ask you about?"
```

Now look at the application logs in the second terminal. On the second call the `request:` line logged by `SimpleLoggerAdvisor` contains the earlier question and answer as well as the new question. The memory advisor added that history, which is exactly what lets the model follow up.

