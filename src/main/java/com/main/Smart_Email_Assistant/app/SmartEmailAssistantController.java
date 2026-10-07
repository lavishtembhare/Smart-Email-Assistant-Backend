package com.main.Smart_Email_Assistant.app;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/email")
@AllArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Email Assistant", description = "Generate email replies and compose new emails with AI")
public class SmartEmailAssistantController {
    private final EmailGeneratorService emailGeneratorService;

    @Operation(
            summary = "Generate a reply to an email",
            description = "Takes the text of a received email (and an optional tone) and returns a drafted reply body as plain text. "
                    + "Transient Groq failures (503 / 429 / connection errors) are retried up to 3 times before an error is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reply drafted successfully",
                    content = @Content(schema = @Schema(type = "string", example = "Hi Priya,\n\nThanks for reaching out. 3 PM works for me.\n\nBest regards,"))),
            @ApiResponse(responseCode = "400", description = "emailContent is missing or blank",
                    content = @Content(schema = @Schema(type = "string", example = "emailContent is required."))),
            @ApiResponse(responseCode = "422", description = "Groq returned no usable content (error, no choices, blocked or empty)",
                    content = @Content(schema = @Schema(type = "string", example = "Groq blocked this content for safety reasons."))),
            @ApiResponse(responseCode = "429", description = "Groq rate limit still hit after retries",
                    content = @Content(schema = @Schema(type = "string", example = "Groq rate limit reached — please try again shortly."))),
            @ApiResponse(responseCode = "502", description = "Groq responded with something that could not be parsed",
                    content = @Content(schema = @Schema(type = "string", example = "Failed to parse Groq response."))),
            @ApiResponse(responseCode = "503", description = "Groq overloaded or unreachable",
                    content = @Content(schema = @Schema(type = "string", example = "Groq is temporarily overloaded — please try again in a moment."))),
            @ApiResponse(responseCode = "504", description = "Groq did not answer within the configured timeout",
                    content = @Content(schema = @Schema(type = "string", example = "Groq did not respond in time — please try again.")))
    })
    @PostMapping("/generate")
    public ResponseEntity<String> generateEmail(@RequestBody EmailRequest emailRequest){
        String response = emailGeneratorService.generateEmailReply(emailRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Health check", description = "Returns the plain text `OK` when the service is up.")
    @ApiResponse(responseCode = "200", description = "Service is up",
            content = @Content(schema = @Schema(type = "string", example = "OK")))
    @GetMapping("/health")
    public String health() {
//        System.out.println("Ok");
        return "OK";
    }

    @Operation(
            summary = "Compose a new email",
            description = "Writes a brand-new email body from a subject, with optional recipient, tone and extra context. "
                    + "The closing is a generic sign-off; no sender name is invented.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email drafted successfully",
                    content = @Content(schema = @Schema(type = "string", example = "Dear Hiring Team,\n\nI would like to request two days of leave next week.\n\nBest regards,"))),
            @ApiResponse(responseCode = "400", description = "subject is missing or blank",
                    content = @Content(schema = @Schema(type = "string", example = "subject is required."))),
            @ApiResponse(responseCode = "422", description = "Groq returned no usable content (error, no choices, blocked or empty)",
                    content = @Content(schema = @Schema(type = "string", example = "Groq returned no choices for this request."))),
            @ApiResponse(responseCode = "429", description = "Groq rate limit still hit after retries",
                    content = @Content(schema = @Schema(type = "string", example = "Groq rate limit reached — please try again shortly."))),
            @ApiResponse(responseCode = "502", description = "Groq responded with something that could not be parsed",
                    content = @Content(schema = @Schema(type = "string", example = "Failed to parse Groq response."))),
            @ApiResponse(responseCode = "503", description = "Groq overloaded or unreachable",
                    content = @Content(schema = @Schema(type = "string", example = "Could not reach Groq — check network connectivity and groq.api.url."))),
            @ApiResponse(responseCode = "504", description = "Groq did not answer within the configured timeout",
                    content = @Content(schema = @Schema(type = "string", example = "Groq did not respond in time — please try again.")))
    })
    @PostMapping("/compose")
    public ResponseEntity<String> composeEmail(@RequestBody EmailComposeRequest composeRequest){
        String response = emailGeneratorService.generateNewEmail(composeRequest);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleServiceUnavailable(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ex.getReason());
    }
}