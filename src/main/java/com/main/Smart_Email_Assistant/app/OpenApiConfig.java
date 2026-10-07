package com.main.Smart_Email_Assistant.app;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartEmailAssistantOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Smart Email Assistant API")
                .version("v1")
                .description("""
                        Generates professional email replies and composes new emails with an LLM \
                        (Groq). Responses are plain text containing only the email body.\
                        """));
    }
}