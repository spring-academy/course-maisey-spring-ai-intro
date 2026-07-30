You now know what MCP is and how it works as a protocol.

Spring AI supports both sides of the protocol, so your application can be a client, a server, or both at once. Underneath it builds on the official MCP Java SDK, and Spring Boot starters plus auto configuration hide most of the plumbing.

It is worth knowing where that SDK comes from. The Spring AI team is one of its main contributors and develops it together with Anthropic, so the official Java implementation of the protocol and the Spring integration on top of it are built by the same people. For you this means Java support arrives early rather than late, and the Spring layer fits the SDK closely instead of working around it.

One thing to keep in mind while you read this section is which revision of the specification you are working with. Spring AI speaks the `2025-06-18` revision, so everything below describes that behaviour. The new `2026-07-28` revision has been released, but neither the MCP Java SDK nor Spring AI supports it yet, and the same is true for most other implementations across languages.

### Consuming Another Server as a Client

To call tools that live in someone else's server you add the client starter.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

This one starter covers stdio, Streamable HTTP, and the older SSE transport. If you prefer a reactive stack there is a `spring-ai-starter-mcp-client-webflux` variant.

You then declare the servers you want to reach. Each connection gets a name, and that name is yours to choose.

```properties
spring.ai.mcp.client.streamable-http.connections.server1.url=http://localhost:8090
```

You can list as many connections as you like, and you can mix transports. A local server that should be launched as a child process is configured with a command and its arguments instead of a URL, and you can pass environment variables to it.

```properties
spring.ai.mcp.client.stdio.connections.server2.command=npx
spring.ai.mcp.client.stdio.connections.server2.args=-y,@modelcontextprotocol/server-filesystem,/tmp
```

If you already have a server list in the JSON format that Claude Desktop uses, you can point Spring AI at that file with `spring.ai.mcp.client.stdio.servers-configuration` instead of repeating everything in properties. One small thing to remember is that starting a command on Windows sometimes needs a `cmd.exe /c` wrapper, which the reference documentation explains in more detail.

The auto configuration now does the work. It creates one client per named connection, performs the handshake, discovers the remote tools, and gathers all of them into a single **`ToolCallbackProvider`** bean. You only have to hand that bean to your `ChatClient`.

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider tools) {
    return builder
            .defaultTools(tools)
            .build();
}
```

From here on the remote tools behave exactly like the local ones you wrote with `@Tool`. The model sees them in the same list, the same `ToolCallingAdvisor` runs the loop, and your prompting code does not change at all. Local and remote tools can be combined freely, because default tools on the bean and per call tools are merged.

A handful of common settings apply to all connections at once.

```properties
spring.ai.mcp.client.name=support-assistant
spring.ai.mcp.client.version=1.0.0
spring.ai.mcp.client.request-timeout=30s
spring.ai.mcp.client.type=SYNC # The default
```

The `name` and `version` are what your application reports to every server during the handshake. The `request-timeout` defaults to twenty seconds, which is often too short for a tool that does real work, so it is worth reviewing. The `type` chooses between the blocking and the reactive programming model, and it applies to all clients together, because mixing synchronous and asynchronous clients in one application is not supported. The default `SYNC` type suits an ordinary blocking application, while `ASYNC` suits a reactive application with non-blocking operations. The type also decides which handler methods get registered, because a `SYNC` client registers only the synchronous MCP annotated methods and ignores the asynchronous ones, and an `ASYNC` client does the opposite. So a handler must match the client type, otherwise it is skipped. Setting `spring.ai.mcp.client.enabled` to `false` turns the whole integration off, which is handy for tests.

Two details about tool names are useful to know. Spring AI makes sure the names it hands to the model stay unique, so a tool keeps its original name when nothing else claims it and only gets a generated prefix when a second server offers the same name. Characters that are not allowed in a tool name are replaced as well. You can take over this naming completely with your own `McpToolNamePrefixGenerator` bean. You can also decide which remote tools reach the model at all by contributing an `McpToolFilter` bean, which is a good idea when a server exposes far more tools than your use case needs.

Beyond tools, the client side can answer the requests a server may send back to it, and Spring AI exposes these through annotations on any bean.

```java
@Component
class McpClientHandlers {

    @McpSampling(clients = "server1")
    CreateMessageResult handleSampling(CreateMessageRequest request) {
        var answer = chatClient.prompt().user(request.toString()).call().content();
        return CreateMessageResult.builder(Role.ASSISTANT, answer, "gpt-5").build();
    }

    @McpElicitation(clients = "server1")
    ElicitResult handleElicitation(ElicitRequest request) {
        return ElicitResult.builder(ElicitResult.Action.ACCEPT).content(Map.of("message", request.message())).build();
    }
}
```

There are matching annotations for the other callbacks, namely `@McpLogging` for log messages from a server, `@McpProgress` for progress updates during a long running call, and `@McpToolListChanged`, `@McpResourceListChanged` and `@McpPromptListChanged` for the notifications a server sends when its offering changes. The `clients` attribute limits a handler to a named connection, and each handler can also return a `Mono` if you use the asynchronous client type.

Spring AI also allows you to configure various aspects of the MCP client behavior, from request timeouts to event handling and message processing, via `McpClientCustomizer`s.

### Exposing Your Own Server

To offer your own capabilities you add a server starter. Which one you pick decides the transport.

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

The plain `spring-ai-starter-mcp-server` starter gives you a stdio server that a host launches locally, and `spring-ai-starter-mcp-server-webflux` is the reactive counterpart of the WebMVC one. Notice that a server needs no model provider starter at all, because it only exposes capabilities and never calls a model itself.

A little configuration names your server and selects the protocol mode.

```properties
spring.ai.mcp.server.name=spring-releases
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.protocol=STREAMABLE
```

The `protocol` property is worth understanding. `STREAMABLE` is the normal choice for a remote server. `SSE` is the older mode and is deprecated. `STATELESS` keeps no session state between requests, which makes the server easier to scale behind a load balancer, and it is the direction the protocol itself is heading.

Just like the client, the server has a `type` that picks the programming model. The default `SYNC` server is built on `McpSyncServer` and suits straightforward request and response work, while the `ASYNC` server is built on `McpAsyncServer` and is optimized for non-blocking operations with Project Reactor. You set it with `spring.ai.mcp.server.type=SYNC` or `ASYNC`. Similar as a client, does a `SYNC` server only register the synchronous MCP annotated methods and an `ASYNC` server only the asynchronous ones, and any method that does not match the server type is quietly ignored.

Writing the capabilities is annotation driven and will feel familiar after the tool calling section. You mark a method with `@McpTool`, describe the parameters with `@McpToolParam`, and Spring AI generates the JSON schema and registers the tool with the protocol.

```java
@Service
class SpringReleasesInfoService {

    @McpTool(description = "Get all releases for a Spring project, including version and support status.")
    List<SpringRelease> fetchReleasesInfo(
            @McpToolParam(description = "The project slug, for example 'spring-boot'") String projectSlug) {
        // Call the Spring API and return the releases
    }
}
```

That is the whole server. There is no transport code, no JSON-RPC handling, and no schema written by hand. An annotation scanner runs by default and looks through your beans for the MCP annotations, so any bean that carries `@McpTool`, `@McpResource`, `@McpPrompt` or `@McpComplete` is picked up on its own. For each one Spring AI reads the method signature, generates the JSON schema, and registers the capability with the protocol, and tools that share a name are de-duplicated so the first one wins. You never build a registry by hand. This is very close to the server you will build in the lab.

The other primitives follow the same pattern. `@McpResource` exposes read only context, and the value in the annotation is the URI template a client uses to fetch it. A template can carry a variable in curly braces, and Spring AI binds it to a method parameter of the same name, so one method can serve a whole family of resources.

```java
@McpResource(uri = "config://{key}", name = "Configuration")
String configuration(String key) {
    return configRepository.get(key);
}
```

`@McpPrompt` exposes a reusable template that a client can offer to its user, and `@McpComplete` provides autocompletion suggestions for prompt arguments. Everything exists in a synchronous and an asynchronous form, and if you ever need full control you can use the MCP Java SDK directly.

One distinction is worth making explicit, because the names are similar. The `@Tool` annotation from the tool calling section exposes a method to the model **inside your own application**. The `@McpTool` annotation exposes a method **over the protocol to other applications**. They are independent, and a method can be published through both if you want it available in both places.

A `@Tool` method can still be served over MCP, it just needs a little wiring. Besides scanning for the MCP annotations, the auto configuration also collects any `ToolCallback` and `ToolCallbackProvider` beans in your context and converts them into MCP tools automatically. So you wrap your `@Tool` object in a `MethodToolCallbackProvider` and expose it as a bean, and the server publishes it just like an `@McpTool`.

```java
@Bean
ToolCallbackProvider releaseTools(SpringReleasesInfoService service) {
    return MethodToolCallbackProvider.builder().toolObjects(service).build();
}
```

This was in fact the only way to build a Spring AI MCP server before the dedicated MCP annotations arrived, and you will still see it in older code and examples. The `@McpTool` family is the newer and more direct approach, because it registers a method with the protocol without the extra provider bean and gives you MCP specific details such as per parameter descriptions. Reach for the `ToolCallbackProvider` route mainly when you want to serve tools you already wrote with `@Tool` without duplicating them.

## Authorization (Experimental)

You already saw how authorization works in MCP as a protocol. Once a server is reachable over the network it plays the role of an OAuth resource server, a client discovers where to authenticate from a first failed request, and every following request carries a bearer token that is bound to that one server.

The Spring AI ecosystem addresses this with an MCP security module built on top of Spring Security, so the pieces are the ones you already know. The module is really three separate pieces, and you add only the ones your application needs. The **server security module** turns your MCP server into an OAuth 2.0 resource server that validates the incoming token, and it also offers a simpler API key option for machine to machine calls. The **client security module** gives your MCP client the ability to obtain a token and attach it to every outgoing request, with a choice of OAuth flows for whose permissions should apply. The **authorization server module** is for the case where you want to issue the tokens yourself, and it extends Spring Authorization Server with the extra pieces the MCP specification expects, such as dynamic client registration and resource indicators. The rest of this section walks through each piece in turn.

On the server side you add the server security module and configure your application as an OAuth 2.0 resource server, which means every incoming request must carry a valid JWT.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>mcp-server-security-spring-boot</artifactId>
</dependency>
```

The configuration is a normal `SecurityFilterChain` with one MCP specific customizer.

```java
@Bean
SecurityFilterChain mcpServerSecurity(HttpSecurity http) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> {
                mcp.authorizationServer("https://auth.example.com");
                mcp.validateAudienceClaim(true);
            })
            .build();
}
```

The `validateAudienceClaim` setting deserves attention. It requires that the token's audience claim names your server, so a token that was issued for a different service cannot simply be replayed against yours. The customizer also publishes the metadata endpoint that the specification expects, which is how a client discovers where it has to authenticate.

Because this is ordinary Spring Security, everything downstream works the way it always has. The authenticated user is available through the usual `SecurityContextHolder`, and you can protect a single tool exactly like a service method.

```java
@McpTool(description = "Create a support ticket for a customer.")
@PreAuthorize("hasAuthority('SCOPE_tickets.write')")
String createTicket(@McpToolParam(description = "The problem description") String description) {
    var user = SecurityContextHolder.getContext().getAuthentication().getName();
    return ticketService.create(user, description);
}
```

For simpler machine to machine cases there is also API key support, where you register a repository of valid keys and choose the header name that carries them.

On the client side you add the client security module, which can obtain and refresh tokens for you when you connect to a protected server. You express this by contributing a customizer that attaches the right token to every outgoing request.

```java
@Bean
McpSyncHttpClientRequestCustomizer requestCustomizer(OAuth2AuthorizedClientManager manager) {
    return new OAuth2ClientCredentialsSyncHttpRequestCustomizer(manager, "ticket-server");
}
```

Which flow you pick depends on whose permissions should apply. The client credentials flow shown above suits background work where no person is involved and your application acts as itself. The authorization code flow suits the case where every call should happen with the permissions of the signed in user, so a user can only reach the tickets they are allowed to see, and you use `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` for it. There is also a hybrid option for applications that need both, for example discovering tools at startup as the application and calling them later as the user. If you do not already run an identity provider, the authorization server module mentioned earlier lets your own application issue the tokens instead.

This is where the honest part comes in. **This security module is experimental and should be treated as work in progress.** It is yet an officially endorsed part of Spring AI itself, and its documentation states plainly that the APIs may still change. The main reason is the protocol rather than the Spring code. MCP only finalized its authorization specification recently, and it is still moving, so the libraries built on top of it cannot be stable before the specification underneath them is. Some concrete limits follow from that. Today the server side supports WebMVC but not WebFlux, it accepts JWT tokens but not opaque ones, and it does not support the deprecated SSE transport. There is also friction between user based authentication and clients that Spring Boot creates at application startup from properties, because at startup there is no user yet, so those cases need programmatic configuration instead.

None of this means you should avoid MCP. It means you should plan for change, keep your security configuration in one place so it is easy to adjust, and be careful about which tools you expose to which callers. It is also worth remembering the general advice from the tool calling section. The model decides which tool to call and with what arguments, and it can be influenced by user input, so treat every tool call as untrusted input, validate the arguments, and give each tool the narrowest permissions it needs.

