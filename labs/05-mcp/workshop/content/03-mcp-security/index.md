---
title: Securing an MCP Server With OAuth 2.0
---

Your MCP server is open. Anyone who can reach port 8090 can list its tools and call them, which is fine on your machine but not on a network.

The MCP specification answers this with OAuth 2.0. The server becomes an **OAuth 2.0 resource server**, so every request must carry a valid access token, and the client obtains that token from an **authorization server**.

In this lab you add all three pieces. You run [Dex](https://dexidp.io) as the authorization server, you turn the Spring Releases MCP server into a resource server, and you give the support assistant the ability to fetch a token and attach it to every MCP call.

The Spring AI ecosystem provides this through the experimental [MCP Security](https://github.com/spring-ai-community/mcp-security) project. It's build on Spring Security, so the building blocks are the ones you already know.

## Run Dex as the Authorization Server

Dex is a small identity service that speaks OpenID Connect. It ships as a single container image, so you can run it with Docker Compose, the same way you ran the observability stack in an earlier lab. This time the Compose file belongs to the MCP server project rather than the support assistant, because the MCP server interacts with the authorization server while it starts up and will not come up without it.

First create the Dex configuration file.

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/dex-config.yaml
description: "Create the Dex configuration"
text: |
  issuer: http://localhost:5556/dex

  storage:
    type: memory

  web:
    http: 0.0.0.0:5556

  oauth2:
    skipApprovalScreen: true

  staticClients:
  - id: support-assistant
    name: 'Support Assistant'
    secret: support-assistant-secret
    redirectURIs:
    - 'http://localhost:8080/authorize/oauth2/code/dex'

  connectors:
  - type: mockCallback
    id: mock
    name: Example
```

The `issuer` is the identity of the authorization server. Every token Dex signs carries it in the `iss` claim, and both applications use it to find Dex. The `staticClients` block registers the support assistant as an OAuth 2.0 client with an id, a secret, and the one URL Dex may send the user back to after a login. 

The `mockCallback` connector is a test connector. It signs everybody in as a fixed user called `kilgore@kilgore.trout` without showing a login form, which lets you drive the whole flow from the terminal. Together with `skipApprovalScreen` it also means no consent screen appears. In a real setup you replace it with an LDAP, GitHub, or OIDC connector.

Now create the Compose file that runs Dex.

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/compose.yaml
description: "Create compose.yaml with the Dex service"
text: |
  services:
    authorization-server:
      image: dexidp/dex:v2.45.1
      container_name: spring-releases-mcp-server-dex
      command: ["dex", "serve", "/etc/dex/config.yaml"]
      volumes:
        - ./dex-config.yaml:/etc/dex/config.yaml:ro
      ports:
        - "5556:5556"
```

Add the `spring-boot-docker-compose` dependency.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/pom.xml
text: "<artifactId>spring-boot-devtools</artifactId>"
description: Add the Spring Boot Docker Compose dependency
before: 2
after: 3
cascade: true
```

```editor:replace-text-selection
file: ~/spring-releases-mcp-server/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-devtools</artifactId>
  			<scope>runtime</scope>
  			<optional>true</optional>
  		</dependency>
  		<dependency>
  			<groupId>org.springframework.boot</groupId>
  			<artifactId>spring-boot-docker-compose</artifactId>
  			<scope>runtime</scope>
  			<optional>true</optional>
  		</dependency>
```

Restart the MCP server, so it will start the Dex container via Docker Compose.

```terminal:interrupt
session: 3
```

```terminal:execute
command: ./mvnw spring-boot:run
session: 3
```

Every OpenID Connect provider publishes a discovery document. Read it to see the endpoints Spring Security will use.

```terminal:execute
command: curl -sS http://localhost:5556/dex/.well-known/openid-configuration
session: 1
```

You get back the authorization endpoint, the token endpoint, and the `jwks_uri`. The last one is the URL where Dex publishes its public keys. The MCP server downloads those keys and uses them to verify the signature of every token it receives, so the two never have to share a secret.

## Turn the MCP Server Into a Resource Server

The server side needs a single dependency. `mcp-server-security-spring-boot` is the Spring Boot module of the MCP Security project. It pulls in Spring Security and the OAuth 2.0 resource server support, and it adds the auto configuration that wires them together for MCP.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/pom.xml
text: "<artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>"
description: "Add the MCP server security dependency"
before: 2
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/spring-releases-mcp-server/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
  		</dependency>

  		<dependency>
  			<groupId>org.springaicommunity</groupId>
  			<artifactId>mcp-server-security-spring-boot</artifactId>
  			<version>0.1.13</version>
  		</dependency>
```

The MCP Security modules are not part of the Spring AI release train yet, so they carry their own version number instead of coming from the Spring AI BOM.

### Point the Server at Dex

```editor:append-lines-to-file
file: ~/spring-releases-mcp-server/src/main/resources/application.properties
description: "Configure the resource server"
text: |2

  spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:5556/dex
  spring.security.oauth2.resourceserver.jwt.principal-claim-name=email
```

The `issuer-uri` is the only value the server needs. At startup Spring Security reads the discovery document you just looked at, finds the `jwks_uri`, and builds a decoder that validates the signature, the issuer, and the expiry of every incoming token.

By default the name of the authenticated user comes from the `sub` claim, which Dex fills with an internal identifier. With `principal-claim-name` you tell Spring Security to use the `email` claim instead, so the user shows up with a readable name.

### Watch the Server Reject Anonymous Calls

Repeat the `initialize` call from the first section, without a token.

```terminal:execute
description: "Call the MCP server without a token"
command: |-
  curl -sS -i -X POST http://localhost:8090/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": { "name": "curl", "version": "1" }
          }
        }'
session: 1
```

You get `401 Unauthorized` with this response header.

```
WWW-Authenticate: Bearer resource_metadata=http://localhost:8090/.well-known/oauth-protected-resource/mcp
```

That header is the heart of MCP authorization. The failed request tells the client where to look next. Follow the URL yourself.

```terminal:execute
command: curl -sS http://localhost:8090/.well-known/oauth-protected-resource/mcp
session: 1
```

The document names the resource, `http://localhost:8090/mcp`, and the authorization server that protects it, `http://localhost:5556/dex`. A client that has never seen this server before can now find Dex on its own.

### Call the Tool With a Token

Play the role of the client once by hand. First run the authorization code flow against Dex. Because the mock connector signs you in without a form, `curl` can follow the redirects all the way to the end.

```terminal:execute
description: "Get an authorization code from Dex"
command: |-
  CODE=$(curl -sS -L -o /dev/null -w '%{url_effective}' \
    "http://localhost:5556/dex/auth?client_id=support-assistant&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauthorize%2Foauth2%2Fcode%2Fdex&response_type=code&scope=openid+profile+email&state=demo" \
    | sed -n 's/.*code=\([^&]*\).*/\1/p')

  echo "Code: $CODE"
session: 1
```

Exchange that one time code for an access token.

```terminal:execute
description: "Exchange the code for an access token"
command: |-
  TOKEN=$(curl -sS -X POST http://localhost:5556/dex/token \
    -u "support-assistant:support-assistant-secret" \
    -d grant_type=authorization_code \
    -d "code=$CODE" \
    -d "redirect_uri=http://localhost:8080/authorize/oauth2/code/dex" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

  echo "Token: $TOKEN"
session: 1
```

The token is a signed JWT with three parts separated by dots. Ask Dex what is inside it.

```terminal:execute
command: |-
  curl -sS -H "Authorization: Bearer $TOKEN" http://localhost:5556/dex/userinfo
session: 1
```

You see the issuer, the expiry, and the `email` claim of the signed in user. The MCP server reads the same claims after it has verified the signature. Now repeat the `initialize` call with that token.

```terminal:execute
description: "Call the MCP server with a token"
command: |-
  curl -sS -i -X POST http://localhost:8090/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": { "name": "curl", "version": "1" }
          }
        }'
session: 1
```

This time you get `200 OK` and a session id.

## Protect a Single Tool

The filter chain already protects the whole endpoint. Because this is ordinary Spring Security, you can also protect a single tool, and you can read the caller inside the tool method.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "@McpTool(description"
description: "Secure the tool and log the caller"
before: 0
after: 3
cascade: true
```

```editor:replace-text-selection
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
hidden: true
cascade: true
text: |2
      @PreAuthorize("isAuthenticated()")
      @McpTool(description = "Get all releases for a Spring project, including version and support status.")
      List<SpringRelease> fetchReleasesInfo(
              @McpToolParam(description = "The project slug, e.g. 'spring-boot', 'spring-framework', 'spring-ai'") String projectSlug) {
          var user = SecurityContextHolder.getContext().getAuthentication().getName();
          log.info("Fetch spring release info for project {} called by {}", projectSlug, user);
```

```editor:insert-lines-before-line
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
hidden: true
line: 8
text: |2
  import org.springframework.security.access.prepost.PreAuthorize;
  import org.springframework.security.core.context.SecurityContextHolder;
```

Two things changed. The first one is a method level rule.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "@PreAuthorize"
```

`@PreAuthorize` works on a tool method like on any other Spring bean method. This rule only asks for an authenticated caller, which the filter chain already guarantees. The interesting version checks what the token allows, for example `@PreAuthorize("hasAuthority('SCOPE_releases.read')")`, but Dex does not put scopes into its access tokens.

The second change reads the caller.

```editor:select-matching-text
file: ~/spring-releases-mcp-server/src/main/java/com/example/spring_releases/SpringReleasesInfoService.java
text: "SecurityContextHolder.getContext()"
```

Inside the tool method you reach the authenticated user through the normal `SecurityContextHolder`. Because you set `principal-claim-name` to `email`, `getName()` returns the address of the signed in user. A production tool would use this to look up what that user is allowed to see, instead of trusting an identifier that the model passed as an argument.

## Give the MCP Client a Token

The support assistant now talks to a server that rejects it. Restart it and watch what happens.

```terminal:interrupt
session: 2
```

```terminal:execute
command: cd ~/sample-app && ./mvnw spring-boot:run
session: 2
```

The application fails to start with an `Authorization error in sendMessage with code 401`. Spring AI creates the MCP client from your properties at startup and immediately connects to the server. At that moment no user is signed in, so there is no token to send. This is the friction that the MCP Security documentation warns about, and you fix it with a property further down.

### Add the Dependencies

```editor:select-matching-text
file: ~/sample-app/pom.xml
text: "<artifactId>spring-ai-starter-mcp-client</artifactId>"
description: "Add the MCP client security dependencies"
before: 2
after: 1
cascade: true
```

```editor:replace-text-selection
file: ~/sample-app/pom.xml
hidden: true
text: |2
  		<dependency>
  			<groupId>org.springframework.ai</groupId>
  			<artifactId>spring-ai-starter-mcp-client</artifactId>
  		</dependency>
  		<dependency>
  			<groupId>org.springaicommunity</groupId>
  			<artifactId>mcp-client-security-spring-boot</artifactId>
  			<version>0.1.13</version>
  		</dependency>
```

The `mcp-client-security-spring-boot` module brings auto configuration with it. When it finds exactly one OAuth 2.0 client registration, it wires the MCP transport so that every outgoing request carries the token of the current user.

### Let the Client Register Itself

```editor:append-lines-to-file
file: ~/sample-app/src/main/resources/application.properties
description: "Configure the OAuth 2.0 client"
text: |2

  spring.ai.mcp.client.initialized=false

  spring.ai.mcp.client.authorization.dynamic-client-registration.enabled=true
  # For development purposes, explicitly allows HTTP for loopback addresses (MCP Security enforces HTTPS for all URLs involved in the Dynamic Client Registration flow)
  spring.ai.mcp.client.authorization.dynamic-client-registration.allow-loopback-addresses=true
  # spring.security.oauth2.client.registration.dex.scope=openid,profile,email
```

`spring.ai.mcp.client.initialized=false` is the fix for the startup failure. The MCP client no longer connects while the application starts. It connects on the first request instead, and by then a user is signed in and there is a token to send.

The other two keys turn on **dynamic client registration**. You never write down a client id or a client secret. The assistant creates them for itself the first time it needs a token, and it takes the same steps you took by hand a moment ago. The `401` points to the protected resource metadata. That document names the authorization server. The assistant then asks that server to register it, and it keeps the client id and the secret it gets back. The MCP specification prefers this way, because a host can use a new server without any manual setup.

MCP Security allows only HTTPS for the URLs in this exchange, and `localhost` uses plain HTTP. The `allow-loopback-addresses` key removes that restriction for local addresses. Use it in development only.

The registration asks for the `authorization_code` grant. This means the assistant acts **on behalf of the signed in user**, which is the flow the MCP specification describes, and it is the reason the user identity reaches the tool. For background work with no user you would pick `client_credentials` instead, so the application acts as itself.

### Write the Security Configuration

Configure the `SecurityFilterChain` with the provided `McpClientOAuth2Configurer`.

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
description: Configure the SecurityFilterChain
cascade: true
line: 19
text: |2
  @EnableWebSecurity
```


```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
cascade: true
hidden: true
line: 43
text: |2

      @Bean
      SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
          return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .with(McpClientOAuth2Configurer.mcpClientOAuth2(), Customizer.withDefaults())
                .csrf(CsrfConfigurer::disable)
                .build();
      } 
```

```editor:insert-lines-before-line
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
hidden: true
line: 18
text: |2
  import org.springframework.security.web.SecurityFilterChain;
  import org.springframework.security.config.annotation.web.builders.HttpSecurity;
  import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
  import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
  import org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer;
```

The endpoints of the assistant itself stay open, so nobody has to sign in to use the support assistant without the tools provided by the MCP server.

```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "auth.anyRequest().permitAll()"
```

That single line does all the OAuth 2.0 wiring.
```editor:select-matching-text
file: ~/sample-app/src/main/java/com/example/support_assistant/SupportAssistantConfiguration.java
text: "McpClientOAuth2Configurer.mcpClientOAuth2()"
```

That single line does all the OAuth 2.0 wiring. Underneath it switches on the normal `oauth2Client` support of Spring Security for MCP, which runs the authorization code flow and keeps the token in the user session. When a request needs a token and there is none yet, Spring Security sends the user to Dex, remembers the original request, and repeats it after the login.

On top of that the configurer adds the MCP specific part. It puts a `resource` parameter into the authorization request and into the token request, and that parameter names the MCP server the token is for. 

### Test the Whole Chain

The assistant now runs a browser flow on your behalf, so `curl` needs to keep cookies and follow redirects. `-c` and `-b` share a cookie file and `-L` follows every redirect.

```terminal:execute
command: |-
  curl -sS -c /tmp/cookies -b /tmp/cookies -L -G "http://localhost:8080/api/v1/chat" \
    --data-urlencode "query=What is the latest stable release of Spring AI? Please also open a high-priority ticket to request access to Spring Application Advisor to accelerate upgrading our application to that version."
session: 1
```

You get the answer as before. What happened in between is the interesting part. The chat request needed the remote tool, the MCP client had no token, Spring Security sent `curl` to Dex, Dex signed in the mock user and sent it back with a code, the assistant exchanged the code for a token, and then the original chat request ran again with that token attached.

Now look at the terminal where the MCP server runs. You find a line like this one.

```
Fetch spring release info for project spring-boot called by kilgore@kilgore.trout
```

The identity of the end user travelled from Dex, through the support assistant, into the MCP server, and all the way into the tool method.

## Before You Move On

You do not want to run Dex on every start from now on. The applications you begin the next lab with therefore keep everything from this section behind a Spring profile called `mcp-security`, in the same way the observability stack sits behind `local-observability`.

Without that profile the MCP server stays open, the support assistant sends no token, and no Dex container has to run.


