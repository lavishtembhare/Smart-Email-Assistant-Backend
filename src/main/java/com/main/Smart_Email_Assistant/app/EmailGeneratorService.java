package com.main.Smart_Email_Assistant.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import reactor.util.retry.Retry;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;

import java.util.Map;

@Service
public class EmailGeneratorService {
    private final WebClient webClient;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    public EmailGeneratorService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public String generateEmailReply(EmailRequest emailRequest) {
        String prompt = buildReplyPrompt(emailRequest);
        return callGemini(prompt);
    }

    public String generateNewEmail(EmailComposeRequest composeRequest) {
        String prompt = buildComposePrompt(composeRequest);
        return callGemini(prompt);
    }

    // Shared Gemini call: request body, retry/backoff, and error handling
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
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(ex -> ex instanceof WebClientResponseException wcre
                                && (wcre.getStatusCode().value() == 503 || wcre.getStatusCode().value() == 429))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(WebClientResponseException.class, ex -> {
                    int code = ex.getStatusCode().value();
                    System.err.println("Gemini raw error body: " + ex.getResponseBodyAsString()); // TEMP — remove after debugging
                    String message = switch (code) {
                        case 503 -> "Gemini is temporarily overloaded — please try again in a moment.";
                        case 429 -> "Gemini rate limit reached — please try again shortly.";
                        case 401, 403 -> "Gemini API key was rejected — check GEMINI_KEY.";
                        default -> "Gemini request failed with status " + code;
                    };
                    return Mono.error(new ResponseStatusException(ex.getStatusCode(), message));
                })
                .block();

        return extractResponseContent(response);
    }

    private String extractResponseContent(String response) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response);
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

            return candidates.get(0).path("content").path("parts").get(0).path("text").asString();
        } catch (ResponseStatusException e) {
            throw e; // let the controller's existing handler format this one
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse Gemini response: " + e.getMessage());
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