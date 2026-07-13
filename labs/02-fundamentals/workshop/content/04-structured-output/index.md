---
title: Structured Output
---

The model only ever returns text. To get reliable, structured data out of it, you need to get two things right, how you ask and how you read the answer. First you do both by hand. Then you let Spring AI take over.

## Prompt Engineering and Few-Shot Prompting

Prompt engineering is the practice of shaping the prompt so the model returns what you need. One of the most effective techniques is few-shot prompting, where you show the model a few examples of the exact output you want and it follows the pattern.

Now you make the model return JSON yourself. You write the format rules and two examples in the system prompt, and you send the question as the user message. The prompt has `{` and `}` characters in it. Spring AI normally reads `{...}` as a template variable, so you use the plain `.system(String)` method. This way the braces stay as normal text. Update `generateResponse`.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 9
description: Use a hand-written few-shot prompt
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      String generateResponse(String query) {
          var chatResponse = chatClient.prompt()
                  .system("""
                    You are a Spring support classifier.
                    Reply only with JSON in this form:
                    {"category":"...","answer":"..."}
                    The category must be one of: TECHNICAL, BILLING, SECURITY, GENERAL.
                    Examples:
                    - "Why was I billed twice?"     -> {"category":"BILLING","answer":"..."}
                    - "How do I rotate my API key?" -> {"category":"SECURITY","answer":"..."}
                    """)
                  .user(query)
                  .call()
                  .chatResponse();
          log.info("Chat Response {}", chatResponse);
          return chatResponse.getResult().getOutput().getText();
      }
```

Call the endpoint with a general question:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

The model replies with JSON, even though the method still returns a plain `String`. The few-shot examples did the steering.

This works, but it is brittle. The result is text you still have to parse yourself, the model can drift from the format on harder questions, and you carry the example prompt around by hand. Spring AI can do all of this for you, both the prompting and the parsing, returning a real Java object.

## Let Spring AI Do the Work  

Instead of free-form text, ask the model to return a Java type and let Spring AI handle both the prompting and the deserialization. Behind the scenes, Spring AI instructs the model to respond in a matching schema and deserializes the result for you.

### Define the Response Type

First, create an enum for the category of the support request:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportCategory.java
description: Create SupportCategory enum
text: |
  package com.example.support_assistant;

  enum SupportCategory {
      TECHNICAL,
      BILLING,
      SECURITY,
      GENERAL
  }
```

Then a record for the structured answer. The `@JsonPropertyDescription` annotations are passed to the model as part of the generated schema, guiding what each field should contain:

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportResponse.java
description: Create SupportResponse record
text: |
  package com.example.support_assistant;

  import com.fasterxml.jackson.annotation.JsonPropertyDescription;

  record SupportResponse(
          @JsonPropertyDescription("The category of the support question: TECHNICAL, BILLING, SECURITY, or GENERAL")
          SupportCategory category,

          @JsonPropertyDescription("The helpful answer to the customer's question")
          String answer
  ) { }
```

### Return the Record

Change `generateResponse` to return the record via `.entity(...)`:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
text: "String generateResponse(String query) {"
before: 0
after: 16
description: Return a SupportResponse entity
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantService.java
hidden: true
text: |2
      SupportResponse generateResponse(String query) {
          return chatClient.prompt()
                  .user(u -> u
                          .text("Answer the following question with a short, well-structured explanation: {question}")
                          .param("question", query))
                  .call()
                  .entity(SupportResponse.class);
      }
```

And change the controller method to match:
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
text: '@GetMapping(path = "/api/v{version}/chat")'
before: 0
after: 3
description: Return SupportResponse from the controller
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantController.java
hidden: true
text: |2
      @GetMapping(path = "/api/v{version}/chat") 
      SupportResponse chat(@RequestParam String query) {
          return service.generateResponse(query);
      }
```

Rerun the curl again:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```

### Enable Native Structured Output

The `.entity(...)` call so far is **prompt-based**. Spring AI adds the format instructions to the prompt and trusts the model to follow them. Many providers, OpenAI among them, can do better and enforce the shape at the API level, a feature called **native structured output**. You turn it on once on the `ChatClient` bean, and every `.entity(...)` call then uses it.

Update the bean in `SupportAssistantConfiguration`:

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultSystem(systemPrompt)"
before: 0
after: 0
description: Enable native structured output on the ChatClient
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
cascade: true
text: |2
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "import org.springframework.ai.chat.client.ChatClient;"
before: 0
after: 0
hidden: true
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.AdvisorParams;
```

Note that `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT` is not an advisor. It is a `Consumer<ChatClient.AdvisorSpec>` that sets an advisor parameter which switches native output on.

Rerun the `curl` to verify it still works:
```execute
curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Tell me about Spring AI"
```