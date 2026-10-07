package com.main.Smart_Email_Assistant.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("OpenAPI / Swagger documentation")
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/v3/api-docs is served as JSON with the API title")
    void apiDocsAreServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Smart Email Assistant API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    @DisplayName("every endpoint is listed with the right HTTP verb")
    void allEndpointsAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/email/generate'].post.summary").value("Generate a reply to an email"))
                .andExpect(jsonPath("$.paths['/api/email/compose'].post.summary").value("Compose a new email"))
                .andExpect(jsonPath("$.paths['/api/email/health'].get.summary").value("Health check"))
                .andExpect(jsonPath("$.paths['/api/email/generate'].post.tags").value(hasItem("Email Assistant")));
    }

    @ParameterizedTest(name = "generate and compose both document the {0} response")
    @ValueSource(strings = {"200", "400", "422", "429", "502", "503", "504"})
    void generateAndComposeDocumentTheirResponses(String statusCode) throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/email/generate'].post.responses['" + statusCode + "']").exists())
                .andExpect(jsonPath("$.paths['/api/email/compose'].post.responses['" + statusCode + "']").exists());
    }

    @Test
    @DisplayName("request schemas expose their fields and mark the mandatory ones as required")
    void requestSchemasAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.EmailRequest.properties.emailContent").exists())
                .andExpect(jsonPath("$.components.schemas.EmailRequest.properties.tone").exists())
                .andExpect(jsonPath("$.components.schemas.EmailRequest.required").value(hasItem("emailContent")))
                .andExpect(jsonPath("$.components.schemas.EmailComposeRequest.properties.recipientEmail").exists())
                .andExpect(jsonPath("$.components.schemas.EmailComposeRequest.properties.subject").exists())
                .andExpect(jsonPath("$.components.schemas.EmailComposeRequest.properties.tone").exists())
                .andExpect(jsonPath("$.components.schemas.EmailComposeRequest.properties.additionalContext").exists())
                .andExpect(jsonPath("$.components.schemas.EmailComposeRequest.required").value(hasItem("subject")));
    }

    @Test
    @DisplayName("/swagger-ui.html redirects to the Swagger UI")
    void swaggerUiEntryPointRedirects() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("/swagger-ui/index.html")));
    }

    @Test
    @DisplayName("the Swagger UI page itself loads")
    void swaggerUiLoads() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
    }
}