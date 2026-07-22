package com.main.Smart_Email_Assistant.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import reactor.util.retry.Retry;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class EmailGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(EmailGeneratorService.class);

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private final WebClient webClient;
    // Shared, thread-safe — building a new one per request is wasted work.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // Defaulted so no application.properties change is required to pick this up.
    @Value("${gemini.api.timeout-seconds:20}")
    private int timeoutSeconds;

    public EmailGeneratorService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public String generateEmailReply(EmailRequest emailRequest) {
        validate(emailRequest);
        String prompt = buildReplyPrompt(emailRequest);
        return callGemini(prompt);
    }

    public String generateNewEmail(EmailComposeRequest composeRequest) {
        validate(composeRequest);
        String prompt = buildComposePrompt(composeRequest);
        return callGemini(prompt);
    }

    // Shared Gemini call: request body, timeout, retry/backoff, and error handling
    // used by both reply generation and new-email composition.
    private String callGemini(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "contents", new Object[]{
                        Map.of("parts", new Object[]{
                                Map.of("text", prompt)
                        })
                }
        );

        String response = webClient.post()
                .uri(geminiApiUrl)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", geminiApiKey) // key stays out of URL/query logs
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
                        .filter(EmailGeneratorService::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(WebClientResponseException.class, ex -> {
                    int code = ex.getStatusCode().value();
                    log.warn("Gemini API returned status {} ({})", code, ex.getStatusText());
                    log.debug("Gemini raw error body: {}", ex.getResponseBodyAsString());
                    String message = switch (code) {
                        case 503 -> "Gemini is temporarily overloaded — please try again in a moment.";
                        case 429 -> "Gemini rate limit reached — please try again shortly.";
                        case 401, 403 -> "Gemini API key was rejected — check GEMINI_KEY.";
                        default -> "Gemini request failed with status " + code;
                    };
                    return Mono.error(new ResponseStatusException(ex.getStatusCode(), message));
                })
                .onErrorResume(WebClientRequestException.class, ex -> {
                    // Thrown for DNS/connection/TLS failures — never a WebClientResponseException,
                    // so it must be handled separately or it slips through unhandled.
                    log.error("Could not reach Gemini API at {}", geminiApiUrl, ex);
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Could not reach Gemini — check network connectivity and GEMINI_URL."));
                })
                .onErrorResume(TimeoutException.class, ex -> {
                    log.warn("Gemini request timed out after {}s", timeoutSeconds);
                    return Mono.error(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                            "Gemini did not respond in time — please try again."));
                })
                .onErrorResume(ex -> !(ex instanceof ResponseStatusException), ex -> {
                    // Safety net so nothing unexpected leaks a raw stack trace to the client.
                    log.error("Unexpected error calling Gemini", ex);
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Unexpected error while generating the email."));
                })
                .block();

        return extractResponseContent(response);
    }

    private static boolean isRetryable(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            return code == 503 || code == 429;
        }
        // Transient connectivity blips are worth a retry too.
        return ex instanceof WebClientRequestException;
    }

    private String extractResponseContent(String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode candidates = rootNode.path("candidates");

            if (candidates.isEmpty()) {
                // Empty candidates usually means the prompt was blocked by safety filters
                // rather than a real server/parse error, so surface that distinctly.
                String blockReason = rootNode.path("promptFeedback").path("blockReason").asString(null);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        blockReason != null
                                ? "Gemini blocked this content: " + blockReason
                                : "Gemini returned no candidates for this request.");
            }

            JsonNode candidate = candidates.get(0);
            JsonNode parts = candidate.path("content").path("parts");

            if (parts.isMissingNode() || parts.isEmpty()) {
                // A finishReason like SAFETY/RECITATION/MAX_TOKENS can produce a candidate
                // with no parts at all — indexing into it directly would NPE.
                String finishReason = candidate.path("finishReason").asString("UNKNOWN");
                String message = switch (finishReason) {
                    case "SAFETY" -> "Gemini blocked this content for safety reasons.";
                    case "RECITATION" -> "Gemini blocked this content due to potential copyright recitation.";
                    case "MAX_TOKENS" -> "Gemini's response was cut off before returning any content — try a shorter input.";
                    default -> "Gemini returned an empty response (finishReason: " + finishReason + ").";
                };
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
            }

            String text = parts.get(0).path("text").asString(null);
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an empty reply.");
            }
            return text;

        } catch (ResponseStatusException e) {
            throw e; // let the controller's existing handler format this one
        } catch (Exception e) {
            // Don't leak internal exception details to the client — log them instead.
            log.error("Failed to parse Gemini response", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to parse Gemini response.");
        }
    }

    private void validate(EmailRequest emailRequest) {
        if (emailRequest.getEmailContent() == null || emailRequest.getEmailContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emailContent is required.");
        }
    }

    private void validate(EmailComposeRequest request) {
        if (request.getRecipientEmail() == null || request.getRecipientEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recipientEmail is required.");
        }
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is required.");
        }
    }

    private String buildReplyPrompt(EmailRequest emailRequest) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a professional email reply for the following email content. please don't generate the subject line ");
        if (emailRequest.getTone() != null && !emailRequest.getTone().isEmpty()) {
            prompt.append("Use a ").append(emailRequest.getTone()).append(" tone. ");
        }
        prompt.append("\nOriginal email: \n").append(emailRequest.getEmailContent());
        return prompt.toString();
    }

    private String buildComposePrompt(EmailComposeRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Compose a new, professional email from scratch based on the subject below. ");
        prompt.append("This is not a reply — there is no prior message to reference. ");
        prompt.append("Write only the email body (no subject line repeated, and don't invent a sender name in the closing — end with a generic professional sign-off like 'Best regards,'). ");

        if (request.getTone() != null && !request.getTone().isEmpty()) {
            prompt.append("Use a ").append(request.getTone()).append(" tone. ");
        }

        prompt.append("\nRecipient: ").append(request.getRecipientEmail());
        prompt.append("\nSubject: ").append(request.getSubject());

        if (request.getAdditionalContext() != null && !request.getAdditionalContext().isBlank()) {
            prompt.append("\nAdditional context to include: ").append(request.getAdditionalContext());
        }

        return prompt.toString();
    }
}