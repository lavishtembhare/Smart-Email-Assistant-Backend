package com.main.Smart_Email_Assistant.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "EmailRequest", description = "Request body for generating a reply to an email you received")
public class EmailRequest {

    @Schema(description = "Full text of the email you want to reply to",
            example = "Hi, could we move tomorrow's meeting to 3 PM?",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String emailContent;

    @Schema(description = "Optional tone for the reply, free text (e.g. friendly, formal, apologetic)",
            example = "friendly",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String tone;
}