package com.example.support_assistant.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@Component
class MockOpenAiServer implements SmartLifecycle {

    private final WireMockServer server;

    MockOpenAiServer() {
        this.server = new WireMockServer(options().port(8081).usingFilesUnderClasspath("mock"));
    }

    @Override
    public void start() {
        if (!server.isRunning()) {
            server.start();
        }
    }

    @Override
    public void stop() {
        if (server.isRunning()) {
            server.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return server.isRunning();
    }
}
