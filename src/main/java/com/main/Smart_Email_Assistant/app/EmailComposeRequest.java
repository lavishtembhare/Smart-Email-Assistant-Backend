package com.main.Smart_Email_Assistant.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "EmailComposeRequest", description = "Request body for composing a brand-new email")
public class EmailComposeRequest {

    @Schema(description = "Who the email is addressed to (optional, only used to give the model context)",
            example = "hr@example.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String recipientEmail;

    @Schema(description = "Subject of the email; the body is written from this",
            example = "Leave request for next week",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String subject;

    @Schema(description = "Optional tone, free text (e.g. formal, friendly, persuasive)",
            example = "formal",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String tone;

    @Schema(description = "Optional extra details the email should mention",
            example = "I need two days off, Monday and Tuesday.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String additionalContext;
}