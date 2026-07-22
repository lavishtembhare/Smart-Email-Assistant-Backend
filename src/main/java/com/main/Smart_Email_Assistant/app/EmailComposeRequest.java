package com.main.Smart_Email_Assistant.app;

import lombok.Data;

@Data
public class EmailComposeRequest {
    private String recipientEmail;
    private String subject;
    private String tone;
    private String additionalContext;
}