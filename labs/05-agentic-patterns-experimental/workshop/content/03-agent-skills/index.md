---
title: Agent Skills
---

Your assistant answers security questions from what the model is trained on. That knowledge is out of date the moment a new advisory is published, and the model has no way to know it.

An **Agent Skill** can fix that by giving the model a folder of instructions plus the files those instructions need. In this section you write a skill that looks up known vulnerabilities for a Maven artifact, and you give the assistant the shell access the skill needs to run its own script.

## Write the Skill

A skill is a folder with a `SKILL.md` file in it, and anything else the skill needs next to it. Create the folder `skills/cve-lookup` with the instructions.

```terminal:execute
command:  mkdir -p ~/sample-app/src/main/resources/skills/cve-lookup
cascade: true
description: Create the cve-lookup skill
```
````editor:append-lines-to-file
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
hidden: true
text: |
  ---
  name: cve-lookup
  description: Use when a user asks whether their Spring version is affected by a security vulnerability, mentions a CVE, asks whether an upgrade is security relevant, or reports that a scanner flagged a Spring dependency.
  allowed-tools: Bash
  ---

  # Vulnerability lookup

  Look up the exact artifact and version the user names. Never answer a security question from memory, because advisories are published continuously and your knowledge is out of date by definition.

  ```bash
  scripts/cve-lookup.sh org.springframework.boot:spring-boot 3.2.4
  ```

  Common coordinates are org.springframework.boot:spring-boot for the framework itself, org.springframework:spring-web and org.springframework:spring-core for the core modules, and org.springframework.security:spring-security-web for Spring Security. Run the script once per artifact when the user names several.

  ## Reading the output

  CLEAN means the database holds no advisory for that exact version, so say that no known vulnerability affects it rather than claiming it is secure. 
  AFFECTED lists one advisory per line with its identifier, its severity, the versions in which it was fixed and a short summary. UNREACHABLE means the lookup failed, so tell the user you could not verify anything and do not fall back to your own knowledge.

  ## Before you answer

  If the user did not name a version, ask for it rather than assuming, because the answer is different for every patch release.

  When advisories are found, call the release tool to check which versions are currently available, so your upgrade recommendation names a version that actually exists.

  ## Answering

  Name the number of advisories, the highest severity among them and the nearest version that contains all the fixes. Keep it to three sentences and offer the details or a support case rather than listing everything at once.

  Do not speculate about exploitability, do not judge how urgent an upgrade is for the user's specific setup, and do not claim a version is safe. Advisories describe what is known, not what is absent.
````

When the application starts, only the block at the top between the two `---` lines is read. It is called the front matter. The model sees these few lines and nothing else, so they decide whether the skill is ever opened.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "name: cve-lookup"
description: What the model sees before it opens the skill
after: 1
```

Write the `description` as a list of situations that should trigger the skill, and not as a summary of what it does. The model has nothing else to go on when it decides whether to open this skill, so "Use when a user asks whether their Spring version is affected" works, while "Looks up CVEs" does not.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "allowed-tools: Bash"
description: What the skill is allowed to use
```

This limits the skill to the tools it really needs. A skill that only reads files should not be able to run shell commands.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "scripts/cve-lookup.sh org.springframework.boot:spring-boot 3.2.4"
description: The skill ships its own script
```

This is the part that makes a skill more than a longer system prompt. The instructions come with an executable file, and the path is relative to the skill folder. The model reads how to call it and then calls it, which is why the lookup returns today's data instead of the training data of the model.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "CLEAN means the database holds no advisory for that exact version"
description: Teach the model to read the output
after: 1
```

The script returns three states, and the skill spells out what each of them means and how to word it. `UNREACHABLE` is the important one, because it tells the model to admit that it could not verify anything instead of falling back on its own knowledge.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "When advisories are found, call the release tool to check which versions are currently available"
description: A skill can lean on the tools you already have
```

The skill does not carry this tool. It points at the `fetchReleasesInfo` tool that the Spring Releases MCP server already provides, so the instructions and the tools work together in one answer.

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/SKILL.md
text: "Do not speculate about exploitability"
description: The limits of the answer
```

The last paragraphs are guardrails. They are worth as much as the lookup itself, because a confident wrong answer to a security question is worse than no answer.

## Add the Script

The skill is useless without the file it points at. This is plain bash with no AI in it, and it asks the public [OSV.dev](https://osv.dev/) database.

```terminal:execute
command:  mkdir -p ~/sample-app/src/main/resources/skills/cve-lookup/scripts
cascade: true
description: Create the lookup script
```
```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/skills/cve-lookup/scripts/cve-lookup.sh
hidden: true
text: |
  #!/usr/bin/env bash
  # Looks up known vulnerabilities for a Maven artifact and version.
  # Usage: cve-lookup.sh <groupId:artifactId> <version>

  set -uo pipefail

  PACKAGE="${1:?package required, for example org.springframework.boot:spring-boot}"
  VERSION="${2:?version required}"

  RESPONSE=$(curl -s -m 10 https://api.osv.dev/v1/query \
    -H 'Content-Type: application/json' \
    -d "{\"package\":{\"name\":\"$PACKAGE\",\"ecosystem\":\"Maven\"},\"version\":\"$VERSION\"}")

  if [ -z "$RESPONSE" ]; then
    echo "state=UNREACHABLE"
    exit 2
  fi

  if ! command -v jq >/dev/null 2>&1; then
    printf '%s' "$RESPONSE" | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | sort -u
    exit 0
  fi

  COUNT=$(printf '%s' "$RESPONSE" | jq '.vulns | length // 0')

  if [ "$COUNT" = "0" ]; then
    echo "state=CLEAN package=$PACKAGE version=$VERSION"
    exit 0
  fi

  echo "state=AFFECTED package=$PACKAGE version=$VERSION count=$COUNT"

  printf '%s' "$RESPONSE" | jq -r '
    .vulns[] |
    "  id=" + (.aliases // [.id] | map(select(startswith("CVE"))) | first // .id) +
    " severity=" + (.database_specific.severity // "UNKNOWN") +
    " fixed=" + ([.affected[].ranges[]?.events[]?.fixed] | unique | join(",") | if . == "" then "none" else . end) +
    " summary=" + ((.summary // "no summary") | .[0:90])
  '
```

```editor:select-matching-text
file: ~/sample-app/src/main/resources/skills/cve-lookup/scripts/cve-lookup.sh
text: "RESPONSE=$(curl -s -m 10 https://api.osv.dev/v1/query"
description: Where the data comes from
after: 2
```

One HTTP call with a timeout. Everything after it turns the JSON into the three short states that `SKILL.md` describes, because a compact line is cheaper in the context than the full advisory JSON and easier for the model to get right.

The model runs this file, so it has to be executable.

```terminal:execute
command: chmod +x ~/sample-app/src/main/resources/skills/cve-lookup/scripts/cve-lookup.sh
session: 1
```

## Register the Skill

Two tools go on the `ChatClient`. `SkillsTool` makes the skills discoverable, and `ShellTools` lets the model actually run the script.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
line: 3
description: Register the skills with the ChatClient
cascade: true
text: |2
  import org.springaicommunity.agent.tools.ShellTools;
  import org.springaicommunity.agent.tools.SkillsTool;
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "public ChatClient chatClient(ChatClient.Builder builder,"
before: 1
after: 14
cascade: true
hidden: true
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
                                   SelfRefineEvaluationAdvisor selfRefineEvaluationAdvisor,
                                   @Value("classpath:skills") Resource skillsResource) {
          return builder
                  .defaultSystem(systemPrompt)
                  .defaultAdvisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                  .defaultAdvisors(
                          new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE),
                          MessageChatMemoryAdvisor.builder(chatMemory).build(),
                          selfRefineEvaluationAdvisor)
                  .defaultTools(
                          tools,
                          SkillsTool.builder().addSkillsResource(skillsResource).build(),
                          ShellTools.builder().build())
                  .build();
      }
```

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "@Value(\"classpath:skills\") Resource skillsResource) {"
description: Point at the skills folder
```

The resource is the folder, not a single skill. Every subfolder with a `SKILL.md` in it is picked up, so you add a second skill by adding a second folder and nothing changes here.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "SkillsTool.builder().addSkillsResource(skillsResource).build(),"
description: One tool for all skills
```

This is a single tool for the model, no matter how many skills you have. It offers the names and the descriptions, and it returns the full `SKILL.md` of the skill the model asks for.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "ShellTools.builder().build())"
description: The tool that runs the script
```

Without this the model could read the instructions but never carry them out. Note what you are handing over here. `ShellTools` runs commands on the machine the application runs on, so in a real system you restrict what it may run and you never point a skills folder at a place a user can write to.

{{< note >}}
The skills tool and the shell tools are sent with every request, because the Tool Search advisor is turned off in this lab. Turn it back on and they are discovered on demand like every other tool, which is how you would run this with a larger set of skills.
{{< /note >}}

## Run the Support Assistant

The assistant is still running from the previous section. DevTools restarts it when your classes change, but the new files under `src/main/resources` only reach the classpath with a fresh build, and the executable bit only travels with them from there. So stop it first.

```terminal:interrupt
session: 2
```

Then start it again.

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

## Test It

Ask a security question that names an artifact and a version.

```terminal:execute
command: |
  curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=Our scanner flagged spring-boot 3.2.4 in our application. Is that version affected by any known vulnerability, and what should we upgrade to?"
session: 1
```

Follow it in the logs of the assistant and you see the whole chain.

1. The model matches the question against the skill descriptions it received and calls the skills tool for `cve-lookup`.
2. It now has the full instructions of the skill.
3. It runs `scripts/cve-lookup.sh org.springframework.boot:spring-boot 3.2.4` through the shell tools.
4. Because the lookup found advisories, it calls `fetchReleasesInfo` on the MCP server for the versions that exist.
5. It answers with the count, the highest severity, and a version that is actually released.

Now ask something that has nothing to do with security.

```terminal:execute
command: curl -G "http://localhost:8080/api/v1/chat" --data-urlencode "query=What are the key features of VMware Tanzu Spring?"
session: 1
```

The skill is never opened. Only its name and its description were ever in the context, and the instructions, the output format, and the guardrails stayed on disk. That is what makes it cheap to keep many skills around.
