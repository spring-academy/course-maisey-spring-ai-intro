---
title: Evaluator Optimizer
---

Your assistant answers in one shot today. Whatever the model produces on the first try goes straight back to the user, no matter how good it is.

The evaluator optimizer pattern puts a second model in front of that answer. The second model rates the answer against clear criteria, and when the rating is too low the first model gets another turn with that feedback.

As mentioned in the previous section, you build this one yourself from the recursive advisor sample in `spring-ai-examples`. Being an advisor keeps the whole loop inside a single `call()`.

## Create the SelfRefineEvaluationAdvisor

Copy that implementation into a new `advisor` package.

```terminal:execute
command:  mkdir -p ~/sample-app/src/main/java/com/example/support_assistant/advisor
cascade: true
description: Create the SelfRefineEvaluationAdvisor
```

```editor:append-lines-to-file
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
hidden: true
text: |
  package com.example.support_assistant.advisor;

  // Source with small adjustments of the prompt https://github.com/spring-projects/spring-ai-examples/blob/main/advisors/evaluation-recursive-advisor-demo/src/main/java/com/example/advisor/SelfRefineEvaluationAdvisor.java
  /*
   * Copyright 2023-2025 the original author or authors.
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *      https://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */

  import java.util.Map;
  import java.util.function.BiPredicate;
  import java.util.stream.Collectors;

  import com.fasterxml.jackson.annotation.JsonClassDescription;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import reactor.core.publisher.Flux;

  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.ai.chat.client.ChatClientRequest;
  import org.springframework.ai.chat.client.ChatClientResponse;
  import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
  import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
  import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
  import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
  import org.springframework.ai.chat.messages.MessageType;
  import org.springframework.ai.chat.messages.SystemMessage;
  import org.springframework.ai.chat.prompt.Prompt;
  import org.springframework.ai.chat.prompt.PromptTemplate;
  import org.springframework.util.Assert;

  /**
   *
   * An Recursive Advisor that evaluates the LLM responses (e.g. Point-wise
   * Scoring ) based on a predefined evaluation criteria. If the evaluation rating
   * is below a certain threshold, it retries the request by providing feedback to
   * the model on how to improve the response. The evaluation is performed by an
   * inner ChatClient instance, which can be customized with different models and
   * settings. The advisor supports a maximum number of retry attempts to avoid
   * infinite loops.
   *
   * @author Christian Tzolov
   */
  public final class SelfRefineEvaluationAdvisor implements CallAdvisor, StreamAdvisor {

      private static final Logger logger = LoggerFactory.getLogger(SelfRefineEvaluationAdvisor.class);

      private static final PromptTemplate DEFAULT_EVALUATION_PROMPT_TEMPLATE = new PromptTemplate(
              """
                          You will be given a user_question and assistant_answer couple.
                          Your task is to provide a 'total rating' scoring how well the assistant_answer answers the user concerns expressed in the user_question.
                          Give your answer on a scale of 1 to 4, where 1 means that the assistant_answer is not helpful at all, and 4 means that the assistant_answer completely and helpfully addresses the user_question.

                          You do not have visibility into the assistant's execution environment. Tool calls, search queries, file operations and similar background actions are not part of the material you receive, even when they did take place.
                          For this reason you must never assess whether a tool was called, whether a search was performed, or whether a source was actually retrieved.
                          Treat the absence of visible tool activity as a limitation of your own view and not as a fault of the response.

                          The same limit applies to the facts in the answer. Identifiers, version numbers, dates, severities and similar details usually come from live sources that you cannot see and that are newer than your own knowledge.
                          You must therefore never call a detail fabricated, unverified or implausible, never lower the rating because you cannot confirm it, and never lower the rating because a date or a version looks like it lies in the future.
                          Do not ask for extra citations, advisory links or evidence that the user did not ask for.
                          Judge only whether the answer addresses the user_question in a clear, complete and useful way, and treat every concrete detail it states as correct.

                          Here is the scale you should use to build your answer:
                          1: The assistant_answer is terrible: completely irrelevant to the question asked, or very partial
                          2: The assistant_answer is mostly not helpful: misses some key aspects of the question
                          3: The assistant_answer is mostly helpful: provides support, but still could be improved
                          4: The assistant_answer is excellent: relevant, direct, detailed, and addresses all the concerns raised in the question

                          Provide your feedback as follows:

                          \\{
                               "rating": 0,
                                "evaluation": "Explanation of the evaluation result and how to improve if needed.",
                                "feedback": "Constructive and specific feedback on the assistant_answer."
                          \\}

                          Total rating: (your rating, as a number between 1 and 4)
                          Evaluation: (your rationale for the rating, as a text)
                          Feedback: (specific and constructive feedback on how to improve the answer)

                          You MUST provide values for 'Evaluation:' and 'Total rating:' in your answer.

                          Now here are the question and answer.

                          Question: {question}
                          Answer: {answer}

                          Provide your feedback. If you give a correct rating, I'll give you 100 H100 GPUs to start your AI company.

                          Evaluation:
                      """);

      private final PromptTemplate evaluationPromptTemplate;
      private final int successRating;
      private final int advisorOrder;
      private final int maxRepeatAttempts;
      private final ChatClient chatClient;
      private final BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate;

      @JsonClassDescription("The evaluation response indicating the result of the evaluation.")
      public record EvaluationResponse(// @format:off
                                       int rating, String evaluation, String feedback) {// @format:on
      }

      private SelfRefineEvaluationAdvisor(int advisorOrder, int maxRepeatAttempts, ChatClient.Builder chatClientBuilder,
                                          PromptTemplate promptTemplate, int considerSuccessRating,
                                          BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate) {

          this.chatClient = chatClientBuilder.build();
          this.evaluationPromptTemplate = promptTemplate;
          this.advisorOrder = advisorOrder;
          this.maxRepeatAttempts = maxRepeatAttempts;
          this.skipEvaluationPredicate = skipEvaluationPredicate;
          this.successRating = considerSuccessRating;
      }

      @Override
      public String getName() {
          return "Evaluation Advisor";
      }

      @Override
      public int getOrder() {
          return this.advisorOrder;
      }

      @Override
      public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
          Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
          Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

          var request = chatClientRequest;

          ChatClientResponse response;

          // Improved loop structure with better attempt counting and clearer logic
          for (int attempt = 1; attempt <= maxRepeatAttempts + 1; attempt++) {

              // Make the inner call (e.g., to the evaluation LLM model)
              response = callAdvisorChain.copy(this).nextCall(request);

              // Early exit - no evaluation needed (e.g., tool call)
              if (this.skipEvaluationPredicate.test(chatClientRequest, response)) {
                  logger.debug("Skipping evaluation because skipEvaluationPredicate returned true.");
                  return response;
              }

              // Perform evaluation
              EvaluationResponse evaluation = this.evaluate(chatClientRequest, response);

              // If evaluation passes, return the response
              if (evaluation.rating() >= this.successRating) {
                  logger.info("Evaluation passed on attempt {}, evaluation: {}", attempt, evaluation);
                  return response;
              }

              // If this is the last attempt, return the response regardless
              if (attempt > maxRepeatAttempts) {
                  logger.warn(
                          "Maximum attempts ({}) reached. Returning last response despite failed evaluation. Use the following feedback to improve: {}",
                          maxRepeatAttempts, evaluation.feedback());

                  // TODO : Perhaps we should throw an exception here instead of returning the
                  // last response? A pluggable strategy could be useful.
                  return response;
              }

              // Retry with evaluation feedback
              logger.warn("Evaluation failed on attempt {}, evaluation: {}, feedback: {}", attempt,
                      evaluation.evaluation(), evaluation.feedback());

              // TODO: We could consider a pluggable backoff strategy here (e.g., exponential
              // backoff).
              // It would allow to either refine/repeat strategy or return the response with
              // evaluation feedback as metadata.
              request = this.addEvaluationFeedback(chatClientRequest, evaluation);
          }

          // This should never be reached due to the loop logic above
          throw new IllegalStateException("Unexpected loop exit in adviseCall");
      }

      /**
       * Performs the evaluation using the LLM-as-a-Judge and returns the result.
       */
      private EvaluationResponse evaluate(ChatClientRequest request, ChatClientResponse response) {

          var evaluationPrompt = this.evaluationPromptTemplate.render(
                  Map.of("question", this.getPromptQuestion(request), "answer", this.getAssistantAnswer(response)));

          return chatClient.prompt(evaluationPrompt).call().entity(EvaluationResponse.class);
      }

      private String getPromptQuestion(ChatClientRequest chatClientRequest) {
          var messages = chatClientRequest.prompt().getInstructions();

          String conversationHistory = messages.stream()
                  .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                  .map(m -> m.getMessageType() + ":" + m.getText()).collect(Collectors.joining(System.lineSeparator()));

          SystemMessage systemMessage = chatClientRequest.prompt().getSystemMessage();

          return systemMessage.getMessageType() + ":" + systemMessage.getText() + System.lineSeparator()
                  + conversationHistory;
      }

      private String getAssistantAnswer(ChatClientResponse chatClientResponse) {
          return chatClientResponse.chatResponse() != null && chatClientResponse.chatResponse().getResult() != null
                  ? chatClientResponse.chatResponse().getResult().getOutput().getText()
                  : "";
      }

      /**
       * Creates a new request with evaluation feedback for retry.
       */
      private ChatClientRequest addEvaluationFeedback(ChatClientRequest originalRequest,
                                                      EvaluationResponse evaluationResponse) {

          Prompt augmentedPrompt = originalRequest.prompt()
                  .augmentUserMessage(userMessage -> userMessage.mutate().text(String.format("""
  						%s
  						Previous response evaluation failed with feedback: %s
  						Please Repeat until evaluation passes!
  						""", userMessage.getText(), evaluationResponse.feedback())).build());

          return originalRequest.mutate().prompt(augmentedPrompt).build();
      }

      @Override
      public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                   StreamAdvisorChain streamAdvisorChain) {
          return Flux.error(new UnsupportedOperationException(
                  "The Structured Output Validation Advisor does not support streaming."));
      }

      /**
       * Creates a new Builder for EvaluationAdvisor_Improved.
       */
      public static Builder builder() {
          return new Builder();
      }

      /**
       * Builder class for EvaluationAdvisor_Improved.
       */
      public final static class Builder {
          private int successRating = 3;
          private int advisorOrder = BaseAdvisor.LOWEST_PRECEDENCE - 2000;
          private int maxRepeatAttempts = 3;
          private ChatClient.Builder chatClientBuilder;
          private PromptTemplate promptTemplate = DEFAULT_EVALUATION_PROMPT_TEMPLATE;

          BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate = (request,
                                                                                        response) -> response.chatResponse() == null || response.chatResponse().hasToolCalls();

          private Builder() {
          }

          public Builder successRating(int successRating) {
              Assert.isTrue(successRating >= 1 && successRating <= 4, "successRating must be between 1 and 4");
              this.successRating = successRating;
              return this;
          }

          public Builder order(int advisorOrder) {
              Assert.isTrue(advisorOrder > BaseAdvisor.HIGHEST_PRECEDENCE && advisorOrder < BaseAdvisor.LOWEST_PRECEDENCE,
                      "advisorOrder must be between HIGHEST_PRECEDENCE and LOWEST_PRECEDENCE");
              this.advisorOrder = advisorOrder;
              return this;
          }

          public Builder chatClientBuilder(ChatClient.Builder chatClientBuilder) {
              Assert.notNull(chatClientBuilder, "chatClientBuilder must not be null");
              this.chatClientBuilder = chatClientBuilder;
              return this;
          }

          public Builder maxRepeatAttempts(int repeatAttempts) {
              Assert.isTrue(repeatAttempts >= 1, "repeatAttempts must be greater than or equal to 1");
              this.maxRepeatAttempts = repeatAttempts;
              return this;
          }

          public Builder promptTemplate(PromptTemplate promptTemplate) {
              Assert.notNull(promptTemplate, "promptTemplate must not be null");
              this.promptTemplate = promptTemplate;
              return this;
          }

          public Builder skipEvaluationPredicate(
                  BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate) {
              Assert.notNull(skipEvaluationPredicate, "skipEvaluationPredicate must not be null");
              this.skipEvaluationPredicate = skipEvaluationPredicate;
              return this;
          }

          public SelfRefineEvaluationAdvisor build() {
              if (this.chatClientBuilder == null) {
                  throw new IllegalArgumentException("chatClientBuilder must be set");
              }
              return new SelfRefineEvaluationAdvisor(this.advisorOrder, this.maxRepeatAttempts, this.chatClientBuilder,
                      this.promptTemplate, this.successRating, this.skipEvaluationPredicate);
          }
      }
  }
```

That is a lot of code at once, so walk through the parts that matter.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "public final class SelfRefineEvaluationAdvisor implements CallAdvisor, StreamAdvisor {"
description: An ordinary advisor from the outside
```

From the outside this is an ordinary advisor. It implements the same `CallAdvisor` interface as the `SimpleLoggerAdvisor` and the `MessageChatMemoryAdvisor` you already use, and you register it the same way. Everything that is special about it happens inside `adviseCall`.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "private static final PromptTemplate DEFAULT_EVALUATION_PROMPT_TEMPLATE = new PromptTemplate("
description: The prompt of the judge
after: 20
```

This is the default prompt of the judge. It uses point wise scoring, which means the judge does not compare two answers with each other but gives one answer a score on a fixed scale from 1 to 4. A clear description of every step of the scale is what keeps the ratings comparable between runs. The template has two placeholders, `question` and `answer`, which the advisor fills in for every evaluation. It's also possible to provide a custom prompt template.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "@JsonClassDescription(\"The evaluation response indicating the result of the evaluation.\")"
description: The judge answers with structured output
after: 2
```

The judge does not answer in free text. It answers into this record, which the advisor then reads with plain Java. The `rating` decides whether the answer is accepted, and the `feedback` is what the first model receives when it has to try again.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "for (int attempt = 1; attempt <= maxRepeatAttempts + 1; attempt++) {"
description: The retry loop
```

Everything happens inside this loop. One extra pass is added on top of `maxRepeatAttempts`, because the first pass is the original answer and not a retry.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "response = callAdvisorChain.copy(this).nextCall(request);"
description: The recursive part
```

This single line is the recursive part. `nextCall` is what every advisor calls to hand the request to the rest of the chain. An advisor chain can only be walked once, so `copy(this)` creates a fresh copy of the remaining chain, with this advisor included again, for every attempt.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "if (this.skipEvaluationPredicate.test(chatClientRequest, response)) {"
description: When no evaluation happens
```

Not every response is worth evaluating.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate = (request,"
description: The default skip rule
after: 1
```

The default rule skips the evaluation when the model asked for a tool call. This matters a lot for your assistant, because it calls the ticket tools and the remote MCP tool. A tool call request is not an answer to the user, so there is nothing for the judge to rate yet. The evaluation only runs on the final text.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "return chatClient.prompt(evaluationPrompt).call().entity(EvaluationResponse.class);"
description: The call to the judge
```

The judge runs on its own `ChatClient`, which you configure in the next step, and the answer comes back as the `EvaluationResponse` record through the structured output support you already know.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "private String getPromptQuestion(ChatClientRequest chatClientRequest) {"
description: What the judge gets to see
after: 10
```

The judge needs to know what was asked before it can say how well it was answered. This method builds that context out of the request, with the system message first and the user and assistant messages of the conversation after it. Notice that it takes the messages of the request that reaches this advisor, so everything the advisors before it added, like the retrieved documents of the RAG advisor, is part of what the judge sees.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "if (evaluation.rating() >= this.successRating) {"
description: The exit condition
```

A rating at or above the threshold ends the loop and the response goes back to the user unchanged. Nothing is rewritten by the judge, the answer is always the one the original model produced.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "Prompt augmentedPrompt = originalRequest.prompt()"
description: How the feedback reaches the next attempt
after: 5
```

When the rating is too low, the feedback of the judge is appended to the original user message and the loop starts over with that augmented request. This is the optimizer half of the pattern. The model is not told to fix its own text, it answers the question again and now knows what was missing.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "if (attempt > maxRepeatAttempts) {"
description: The safety net
after: 3
```

This is what stops an endless loop. When the attempts run out, the last response is returned even though it never passed, and the failure is only visible in the log. In a real application you would decide here whether returning a weak answer is acceptable or whether an error is the better outcome.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "private int successRating = 3;"
description: The defaults you can tune
after: 2
```

These three defaults are the knobs you turn most often. A response has to reach a rating of 3 to pass, and up to 3 retries are allowed, so one request can cost up to 8 model calls in the worst case. The order places the advisor near the end of the chain, which puts it after chat memory and RAG and around the tool calling, so it wraps the complete work of one turn.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/advisor/SelfRefineEvaluationAdvisor.java
text: "return Flux.error(new UnsupportedOperationException("
description: No streaming support
after: 1
```

Streaming is not supported, and that is not an oversight. You cannot rate an answer that has not been written yet, and you cannot take back tokens that the user has already seen.

## Configure the Judge

The advisor needs a `ChatClient` for the judge. Create it as a bean, together with the advisor itself.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 3
description: Add the judge bean to SupportAssistantConfiguration
cascade: true
text: |2
  import com.example.support_assistant.advisor.SelfRefineEvaluationAdvisor;
  import org.springframework.ai.chat.prompt.ChatOptions;
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 46
hidden: true
text: |2

      @Bean
      SelfRefineEvaluationAdvisor selfRefineEvaluationAdvisor(ChatClient.Builder builder) {
          var evaluationChatClientBuilder = builder
                  .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                  .defaultOptions(ChatOptions.builder().temperature(0.0));
          return SelfRefineEvaluationAdvisor.builder().chatClientBuilder(evaluationChatClientBuilder).build();
      }

```

Three details in those few lines are worth a closer look.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "SelfRefineEvaluationAdvisor selfRefineEvaluationAdvisor(ChatClient.Builder builder) {"
description: A fresh builder for the judge
```

`ChatClient.Builder` is prototype scoped, so this bean gets its own instance and not the one your `chatClient` bean uses. The judge therefore has no system prompt, no RAG advisor, no chat memory, and no tools. It only ever sees the question and the answer that the advisor hands it, which is exactly what you want from a judge.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))"
description: No tool calling for the judge
```

This turns off the automatic registration of the tool calling advisor for this client. The judge has no work to do with tools, it returns a rating and some text, so leaving the tool calling machinery out keeps its calls simple and cheap.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: ".defaultOptions(ChatOptions.builder().temperature(0.0))"
description: Make the rating repeatable
```

A temperature of `0.0` makes the judge as repeatable as it can be. Your assistant runs at `0.7`, so it stays creative while the rating stays steady.

This is also the place where you would use a different model as the judge, with `.defaultOptions(ChatOptions.builder().model("...").temperature(0.0))`. As the article explained, a model rates its own output more generously than the output of another model, so a second model gives you a more honest score. This lab keeps one model to stay simple.

## Add the Advisor to the ChatClient

Now hand the advisor to the assistant.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder,"
description: Register the advisor on the ChatClient
before: 1
after: 12
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
text: |2
      @Bean
      public ChatClient chatClient(ChatClient.Builder builder,
                                   @Value("classpath:/prompts/system-prompt.st") Resource systemPrompt,
                                   ChatMemory chatMemory,
                                   ToolCallbackProvider tools,
                                   SelfRefineEvaluationAdvisor selfRefineEvaluationAdvisor) {
          return builder
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                  .defaultAdvisors(
                          new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),
                          MessageChatMemoryAdvisor.builder(chatMemory).build(),
                          selfRefineEvaluationAdvisor)
                  .defaultTools(tools)
                  .build();
      }
```

Two lines changed. The advisor is injected as a bean and added to the default advisors, next to the logger and the memory advisor.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "MessageChatMemoryAdvisor.builder(chatMemory).build(),"
description: The advisor joins the chain
after: 1
```

That is the whole wiring. `SupportAssistantService` and `SupportAssistantController` stay exactly as they are, and every request that goes through this client is now evaluated before the user sees it.

## Run the Support Assistant

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

{{< note >}}
Wait for "Started SupportAssistantApplication" in the logs before continuing.
{{< /note >}}

## Test It

Ask a question that the knowledge base can answer.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What are the key features of VMware Tanzu Spring?"
session: 1
```

Watch the logs of the assistant. The advisor logs every decision it makes, so you can follow the loop.

Now send a request that makes the assistant use a tool.

```terminal:execute
command: |
  curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Open a high-priority ticket for an auth issue with the Spring Enterprise Repository."
session: 1
```

Here you can see the skip rule at work. The intermediate responses that only ask for a tool call are not evaluated, and the judge is used once at the end on the answer that reports the ticket number.

The cost of this pattern is easy to see in the logs. Every answer needs at least one extra model call, and every failed evaluation adds two more. That is the trade you make for a quality gate in front of the user, so use it where a bad answer is expensive and not on every endpoint.
