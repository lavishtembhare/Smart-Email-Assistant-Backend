package com.main.Smart_Email_Assistant.app;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Smart Email Assistant - functional behaviour")
class EmailAssistantFunctionalTest {

    // ------------------------------------------------------------ fake Groq

    private record StubbedReply(int status, String body) {}

    private record CapturedRequest(String method, String authorization, String contentType, String body) {
        String model()  { return JsonPath.read(body, "$.model"); }
        String role()   { return JsonPath.read(body, "$.messages[0].role"); }
        String prompt() { return JsonPath.read(body, "$.messages[0].content"); }
    }

    private static final Queue<StubbedReply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final List<CapturedRequest> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer GROQ = startFakeGroq();

    private static HttpServer startFakeGroq() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/openai/v1/chat/completions", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                RECEIVED.add(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(requestBody, StandardCharsets.UTF_8)));

                StubbedReply reply = REPLIES.poll();
                if (reply == null) {
                    reply = new StubbedReply(500, "{\"error\":{\"message\":\"no reply queued in the test\"}}");
                }
                byte[] responseBody = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), responseBody.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(responseBody);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the fake Groq server", e);
        }
    }

    @DynamicPropertySource
    static void pointTheAppAtTheFakeGroq(DynamicPropertyRegistry registry) {
        registry.add("groq.api.url",
                () -> "http://127.0.0.1:" + GROQ.getAddress().getPort() + "/openai/v1/chat/completions");
    }

    @AfterAll
    static void stopFakeGroq() {
        GROQ.stop(0);
    }

    @BeforeEach
    void resetFakeGroq() {
        REPLIES.clear();
        RECEIVED.clear();
    }

    private static void groqWillReply(int status, String body) {
        REPLIES.add(new StubbedReply(status, body));
    }

    /** {@code text} must not contain quotes or newlines. */
    private static String completion(String text) {
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    // ------------------------------------------------------------ driving the API

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ------------------------------------------------------------ scenarios

    @Nested
    @DisplayName("replying to an email")
    class ReplyingToAnEmail {

        @Test
        @DisplayName("sends an authenticated, correctly shaped request to Groq and returns the reply text")
        void happyPath() throws Exception {
            groqWillReply(200, completion("Hi Priya, 3 PM works for me. Best regards,"));

            postJson("/api/email/generate", """
                    {"emailContent":"Could we move tomorrow's meeting to 3 PM?","tone":"friendly"}""")
                    .andExpect(status().isOk())
                    .andExpect(content().string("Hi Priya, 3 PM works for me. Best regards,"));

            assertThat(RECEIVED).hasSize(1);
            CapturedRequest sent = RECEIVED.get(0);
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.authorization()).isEqualTo("Bearer test-key");
            assertThat(sent.contentType()).startsWith("application/json");
            assertThat(sent.model()).isEqualTo("test-model");
            assertThat(sent.role()).isEqualTo("user");
            assertThat(sent.prompt())
                    .contains("Generate a professional email reply")
                    .contains("Could we move tomorrow's meeting to 3 PM?")
                    .contains("Use a friendly tone.");
        }

        @Test
        @DisplayName("leaves the tone instruction out when no tone is given")
        void noToneNoToneInstruction() throws Exception {
            groqWillReply(200, completion("Sure."));

            postJson("/api/email/generate", """
                    {"emailContent":"Are you free on Friday?"}""")
                    .andExpect(status().isOk());

            assertThat(RECEIVED.get(0).prompt())
                    .contains("Are you free on Friday?")
                    .doesNotContain("tone");
        }

        @Test
        @DisplayName("leaves the tone instruction out when the tone is an empty string")
        void emptyToneNoToneInstruction() throws Exception {
            groqWillReply(200, completion("Sure."));

            postJson("/api/email/generate", """
                    {"emailContent":"Are you free on Friday?","tone":""}""")
                    .andExpect(status().isOk());

            assertThat(RECEIVED.get(0).prompt()).doesNotContain("tone");
        }
    }

    @Nested
    @DisplayName("composing a new email")
    class ComposingANewEmail {

        @Test
        @DisplayName("puts recipient, subject, tone and extra context into the prompt")
        void everythingProvided() throws Exception {
            groqWillReply(200, completion("Dear HR team, I would like to request leave."));

            postJson("/api/email/compose", """
                    {"recipientEmail":"hr@example.com","subject":"Leave request","tone":"formal",
                     "additionalContext":"Need two days off next week"}""")
                    .andExpect(status().isOk())
                    .andExpect(content().string("Dear HR team, I would like to request leave."));

            assertThat(RECEIVED).hasSize(1);
            assertThat(RECEIVED.get(0).authorization()).isEqualTo("Bearer test-key");
            assertThat(RECEIVED.get(0).prompt())
                    .contains("Compose a new, professional email from scratch")
                    .contains("Use a formal tone.")
                    .contains("Recipient: hr@example.com")
                    .contains("Subject: Leave request")
                    .contains("Additional context to include: Need two days off next week");
        }

        @Test
        @DisplayName("works with only a subject and omits the optional sections")
        void subjectOnly() throws Exception {
            groqWillReply(200, completion("Hello, here is the update."));

            postJson("/api/email/compose", """
                    {"subject":"Weekly update"}""")
                    .andExpect(status().isOk())
                    .andExpect(content().string("Hello, here is the update."));

            assertThat(RECEIVED.get(0).prompt())
                    .contains("Subject: Weekly update")
                    .doesNotContain("Recipient:")
                    .doesNotContain("Additional context")
                    .doesNotContain("Use a ");
        }
    }

    @Nested
    @DisplayName("invalid requests")
    class InvalidRequests {

        @ParameterizedTest(name = "generate with body {0} -> 400 and Groq is never called")
        @ValueSource(strings = {
                "{}",
                "{\"emailContent\":\"\"}",
                "{\"emailContent\":\"   \"}",
                "{\"tone\":\"formal\"}"
        })
        void generateWithoutEmailContent(String json) throws Exception {
            postJson("/api/email/generate", json)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string("emailContent is required."));

            assertThat(RECEIVED).isEmpty();
        }

        @ParameterizedTest(name = "compose with body {0} -> 400 and Groq is never called")
        @ValueSource(strings = {
                "{}",
                "{\"subject\":\"\"}",
                "{\"subject\":\"   \"}",
                "{\"recipientEmail\":\"hr@example.com\"}"
        })
        void composeWithoutSubject(String json) throws Exception {
            postJson("/api/email/compose", json)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string("subject is required."));

            assertThat(RECEIVED).isEmpty();
        }
    }

    @Nested
    @DisplayName("when Groq misbehaves")
    class WhenGroqMisbehaves {

        @Test
        @DisplayName("a transient 503 is retried transparently - the caller just gets the reply")
        void transientOverloadIsInvisibleToTheCaller() throws Exception {
            groqWillReply(503, "{\"error\":{\"message\":\"overloaded\"}}");
            groqWillReply(200, completion("Recovered reply."));

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isOk())
                    .andExpect(content().string("Recovered reply."));

            assertThat(RECEIVED).hasSize(2);
        }

        @Test
        @DisplayName("a transient 429 is retried transparently too")
        void transientRateLimitIsInvisibleToTheCaller() throws Exception {
            groqWillReply(429, "{\"error\":{\"message\":\"slow down\"}}");
            groqWillReply(200, completion("Recovered reply."));

            postJson("/api/email/compose", """
                    {"subject":"Hello"}""")
                    .andExpect(status().isOk())
                    .andExpect(content().string("Recovered reply."));

            assertThat(RECEIVED).hasSize(2);
        }

        @Test
        @DisplayName("a persistent 503 is retried 3 times (4 calls) and then surfaces as 503")
        void persistentOverload() throws Exception {
            for (int i = 0; i < 4; i++) {
                groqWillReply(503, "{\"error\":{\"message\":\"overloaded\"}}");
            }

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(containsString("temporarily overloaded")));

            assertThat(RECEIVED).hasSize(4);
        }

        @Test
        @DisplayName("a persistent 429 is retried 3 times (4 calls) and then surfaces as 429")
        void persistentRateLimit() throws Exception {
            for (int i = 0; i < 4; i++) {
                groqWillReply(429, "{\"error\":{\"message\":\"slow down\"}}");
            }

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().string(containsString("rate limit reached")));

            assertThat(RECEIVED).hasSize(4);
        }

        @Test
        @DisplayName("a rejected API key fails immediately (no retries) with a clear message")
        void rejectedApiKey() throws Exception {
            groqWillReply(401, "{\"error\":{\"message\":\"Invalid API Key\"}}");

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(containsString("API key was rejected")));

            assertThat(RECEIVED).hasSize(1);
        }

        @Test
        @DisplayName("an unexpected upstream 500 fails immediately (no retries)")
        void upstreamServerError() throws Exception {
            groqWillReply(500, "{\"error\":{\"message\":\"internal\"}}");

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string("Groq request failed with status 500"));

            assertThat(RECEIVED).hasSize(1);
        }

        @Test
        @DisplayName("content blocked by Groq's safety filter -> 422")
        void contentFilter() throws Exception {
            groqWillReply(200, "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"\"}}]}");

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().string(containsString("blocked this content")));
        }

        @Test
        @DisplayName("200 with no choices -> 422")
        void noChoices() throws Exception {
            groqWillReply(200, "{\"choices\":[]}");

            postJson("/api/email/compose", """
                    {"subject":"Hello"}""")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().string("Groq returned no choices for this request."));
        }

        @Test
        @DisplayName("200 with a body that is not JSON -> 502")
        void garbagePayload() throws Exception {
            groqWillReply(200, "<html>upstream proxy error</html>");

            postJson("/api/email/generate", """
                    {"emailContent":"Hello"}""")
                    .andExpect(status().isBadGateway())
                    .andExpect(content().string("Failed to parse Groq response."));
        }
    }
}