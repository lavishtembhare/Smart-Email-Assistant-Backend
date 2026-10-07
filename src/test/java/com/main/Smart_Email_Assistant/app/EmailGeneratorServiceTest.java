package com.main.Smart_Email_Assistant.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("EmailGeneratorService")
class EmailGeneratorServiceTest {

    private static final int ATTEMPTS_WHEN_RETRIES_ARE_EXHAUSTED = 4;
    private static final String REPLY_TEXT = "Hi Sam, thanks for your email. 3 PM works for me.";

    // ---------------------------------------------------------------- happy path

    @Nested
    @DisplayName("when Groq answers successfully")
    class SuccessfulCalls {

        @Test
        @DisplayName("generateEmailReply returns the model's text with a single upstream call")
        void replyReturnsModelText() {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, completion(REPLY_TEXT));

            String result = serviceUsing(groq).generateEmailReply(replyRequest("Can we meet at 3?", "friendly"));

            assertThat(result).isEqualTo(REPLY_TEXT);
            assertThat(groq.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("generateNewEmail returns the model's text with a single upstream call")
        void composeReturnsModelText() {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, completion("Dear team, please find the update below."));

            String result = serviceUsing(groq).generateNewEmail(composeRequest("Weekly update"));

            assertThat(result).isEqualTo("Dear team, please find the update below.");
            assertThat(groq.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("uses the first choice when Groq returns several")
        void usesFirstChoice() {
            String body = "{\"choices\":["
                    + "{\"finish_reason\":\"stop\",\"message\":{\"content\":\"first\"}},"
                    + "{\"finish_reason\":\"stop\",\"message\":{\"content\":\"second\"}}]}";
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, body);

            assertThat(serviceUsing(groq).generateEmailReply(replyRequest("hello", null))).isEqualTo("first");
        }
    }

    // ---------------------------------------------------------------- validation

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @ParameterizedTest(name = "reply with emailContent=[{0}] is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\t "})
        void replyRequiresEmailContent(String emailContent) {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, completion(REPLY_TEXT));

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest(emailContent, "formal")));

            assertStatus(thrown, 400, "emailContent is required.");
            assertThat(groq.calls()).as("Groq must not be called for invalid input").isZero();
        }

        @ParameterizedTest(name = "compose with subject=[{0}] is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\t "})
        void composeRequiresSubject(String subject) {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, completion(REPLY_TEXT));

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateNewEmail(composeRequest(subject)));

            assertStatus(thrown, 400, "subject is required.");
            assertThat(groq.calls()).as("Groq must not be called for invalid input").isZero();
        }
    }

    // ---------------------------------------------------------------- retries

    @Nested
    @DisplayName("retry behaviour")
    class Retries {

        @ParameterizedTest(name = "recovers when HTTP {0} is followed by success")
        @ValueSource(ints = {503, 429})
        void transientFailureThenSuccess(int status) {
            ScriptedGroq groq = new ScriptedGroq()
                    .thenReply(status, "{\"error\":{\"message\":\"try later\"}}")
                    .thenReply(200, completion(REPLY_TEXT));

            String result = serviceUsing(groq).generateEmailReply(replyRequest("hello", null));

            assertThat(result).isEqualTo(REPLY_TEXT);
            assertThat(groq.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("keeps retrying through several consecutive 503s")
        void severalFailuresThenSuccess() {
            ScriptedGroq groq = new ScriptedGroq()
                    .thenReply(503, "{}")
                    .thenReply(503, "{}")
                    .thenReply(200, completion(REPLY_TEXT));

            assertThat(serviceUsing(groq).generateEmailReply(replyRequest("hello", null))).isEqualTo(REPLY_TEXT);
            assertThat(groq.calls()).isEqualTo(3);
        }

        @ParameterizedTest(name = "gives up after 3 retries when HTTP {0} never clears")
        @CsvSource({
                "503, temporarily overloaded",
                "429, rate limit reached"
        })
        void retriesAreExhausted(int status, String expectedMessage) {
            ScriptedGroq groq = new ScriptedGroq().thenReply(status, "{}");

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, status, expectedMessage);
            assertThat(groq.calls()).isEqualTo(ATTEMPTS_WHEN_RETRIES_ARE_EXHAUSTED);
        }

        @Test
        @DisplayName("retries connection failures, then reports 503 'could not reach Groq'")
        void connectionFailuresAreRetriedThenReported() {
            ScriptedGroq groq = new ScriptedGroq().thenFail(connectionRefused());

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 503, "Could not reach Groq");
            assertThat(groq.calls()).isEqualTo(ATTEMPTS_WHEN_RETRIES_ARE_EXHAUSTED);
        }

        @Test
        @DisplayName("recovers when a connection failure is followed by success")
        void connectionFailureThenSuccess() {
            ScriptedGroq groq = new ScriptedGroq()
                    .thenFail(connectionRefused())
                    .thenReply(200, completion(REPLY_TEXT));

            assertThat(serviceUsing(groq).generateEmailReply(replyRequest("hello", null))).isEqualTo(REPLY_TEXT);
            assertThat(groq.calls()).isEqualTo(2);
        }

        @ParameterizedTest(name = "HTTP {0} is not retried and is reported as \"{1}\"")
        @CsvSource({
                "400, Groq request failed with status 400",
                "401, Groq API key was rejected",
                "403, Groq API key was rejected",
                "404, Groq request failed with status 404",
                "500, Groq request failed with status 500",
                "502, Groq request failed with status 502"
        })
        void nonRetryableStatusesFailFast(int status, String expectedMessage) {
            ScriptedGroq groq = new ScriptedGroq().thenReply(status, "{\"error\":{\"message\":\"nope\"}}");

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, status, expectedMessage);
            assertThat(groq.calls()).as("non-retryable errors must not be retried").isEqualTo(1);
        }

        @Test
        @DisplayName("a hung request times out with 504 and is not retried")
        void timeoutIsReportedAndNotRetried() {
            ScriptedGroq groq = new ScriptedGroq().thenHang();

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 504, "did not respond in time");
            assertThat(groq.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unexpected exception becomes a generic 500 and is not retried")
        void unexpectedFailureBecomesInternalServerError() {
            ScriptedGroq groq = new ScriptedGroq().thenFail(new IllegalStateException("boom"));

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 500, "Unexpected error while generating the email.");
            assertThat(groq.calls()).isEqualTo(1);
        }
    }


    @Nested
    @DisplayName("when Groq answers 200 but the payload is unusable")
    class UnusablePayloads {

        @Test
        @DisplayName("empty choices -> 422 'no choices'")
        void emptyChoices() {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, "{\"choices\":[]}");

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 422, "Groq returned no choices for this request.");
        }

        @Test
        @DisplayName("no choices but an error object -> 422 carrying Groq's message")
        void errorObjectInsteadOfChoices() {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, "{\"error\":{\"message\":\"The model does not exist\"}}");

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 422, "Groq returned an error: The model does not exist");
        }

        @ParameterizedTest(name = "finish_reason={0} with blank content -> \"{1}\"")
        @CsvSource({
                "content_filter, blocked this content for safety",
                "length, cut off before returning any content",
                "stop, finishReason: stop"
        })
        void blankContentExplainsWhy(String finishReason, String expectedMessage) {
            String body = "{\"choices\":[{\"finish_reason\":\"" + finishReason
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, body);

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 422, expectedMessage);
        }

        @ParameterizedTest(name = "content={0} is treated as empty")
        @ValueSource(strings = {"\"\"", "\"   \"", "null"})
        void blankOrNullContentIsRejected(String contentLiteral) {
            String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" + contentLiteral + "}}]}";
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, body);

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 422, "empty response (finishReason: stop)");
        }

        @Test
        @DisplayName("a body that is not JSON -> 502 'failed to parse'")
        void malformedJson() {
            ScriptedGroq groq = new ScriptedGroq().thenReply(200, "<html>Bad gateway</html>");

            Throwable thrown = catchThrowable(() -> serviceUsing(groq).generateEmailReply(replyRequest("hello", null)));

            assertStatus(thrown, 502, "Failed to parse Groq response.");
        }
    }


    private static EmailGeneratorService serviceUsing(ExchangeFunction fakeGroq) {
        EmailGeneratorService service = new EmailGeneratorService(WebClient.builder().exchangeFunction(fakeGroq));
        // @Value fields are not injected without a Spring context, so set them the way the properties would.
        ReflectionTestUtils.setField(service, "groqApiUrl", "http://groq.test/openai/v1/chat/completions");
        ReflectionTestUtils.setField(service, "groqApiKey", "test-key");
        ReflectionTestUtils.setField(service, "groqModel", "test-model");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 10L);
        return service;
    }

    private static EmailRequest replyRequest(String emailContent, String tone) {
        EmailRequest request = new EmailRequest();
        request.setEmailContent(emailContent);
        request.setTone(tone);
        return request;
    }

    private static EmailComposeRequest composeRequest(String subject) {
        EmailComposeRequest request = new EmailComposeRequest();
        request.setSubject(subject);
        return request;
    }

    /** A minimal OpenAI-style chat completion. {@code text} must not contain quotes or newlines. */
    private static String completion(String text) {
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    private static WebClientRequestException connectionRefused() {
        return new WebClientRequestException(
                new ConnectException("Connection refused"),
                HttpMethod.POST,
                URI.create("http://groq.test/openai/v1/chat/completions"),
                new HttpHeaders());
    }

    private static void assertStatus(Throwable thrown, int expectedStatus, String expectedReasonFragment) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(ex.getReason()).contains(expectedReasonFragment);
    }

    /**
     * A fake Groq that plays back scripted behaviour, one step per call. The last step repeats
     * forever, so "always 503" is a single {@code thenReply(503, ...)}. Counts every call it receives.
     */
    private static final class ScriptedGroq implements ExchangeFunction {

        private final Deque<Supplier<Mono<ClientResponse>>> script = new ConcurrentLinkedDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedGroq thenReply(int status, String jsonBody) {
            script.add(() -> Mono.just(ClientResponse.create(HttpStatusCode.valueOf(status))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonBody)
                    .build()));
            return this;
        }

        ScriptedGroq thenFail(Throwable failure) {
            script.add(() -> Mono.error(failure));
            return this;
        }

        ScriptedGroq thenHang() {
            script.add(Mono::never);
            return this;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            calls.incrementAndGet();
            Supplier<Mono<ClientResponse>> step = script.size() > 1 ? script.poll() : script.peek();
            if (step == null) {
                return Mono.error(new IllegalStateException("ScriptedGroq has no scripted behaviour"));
            }
            return step.get();
        }
    }
}