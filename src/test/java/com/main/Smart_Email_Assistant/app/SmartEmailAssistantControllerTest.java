package com.main.Smart_Email_Assistant.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SmartEmailAssistantController.class)
@DisplayName("SmartEmailAssistantController")
class SmartEmailAssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailGeneratorService emailGeneratorService;

    @Nested
    @DisplayName("GET /api/email/health")
    class Health {

        @Test
        @DisplayName("answers 200 OK")
        void returnsOk() throws Exception {
            mockMvc.perform(get("/api/email/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("OK"));
        }
    }

    @Nested
    @DisplayName("POST /api/email/generate")
    class Generate {

        @Test
        @DisplayName("returns the generated reply as the response body")
        void returnsGeneratedReply() throws Exception {
            given(emailGeneratorService.generateEmailReply(any(EmailRequest.class))).willReturn("Thanks, see you at 3.");

            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"emailContent":"Can we meet at 3?","tone":"friendly"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string("Thanks, see you at 3."));
        }

        @Test
        @DisplayName("passes emailContent and tone from the JSON body to the service")
        void bindsJsonBodyToRequest() throws Exception {
            given(emailGeneratorService.generateEmailReply(any(EmailRequest.class))).willReturn("ok");

            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"emailContent":"Can we meet at 3?","tone":"formal"}"""))
                    .andExpect(status().isOk());

            ArgumentCaptor<EmailRequest> captor = ArgumentCaptor.forClass(EmailRequest.class);
            verify(emailGeneratorService).generateEmailReply(captor.capture());
            assertThat(captor.getValue().getEmailContent()).isEqualTo("Can we meet at 3?");
            assertThat(captor.getValue().getTone()).isEqualTo("formal");
        }

        @Test
        @DisplayName("tone is optional and arrives as null when omitted")
        void toneIsOptional() throws Exception {
            given(emailGeneratorService.generateEmailReply(any(EmailRequest.class))).willReturn("ok");

            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"emailContent":"Hello"}"""))
                    .andExpect(status().isOk());

            ArgumentCaptor<EmailRequest> captor = ArgumentCaptor.forClass(EmailRequest.class);
            verify(emailGeneratorService).generateEmailReply(captor.capture());
            assertThat(captor.getValue().getTone()).isNull();
        }

        @ParameterizedTest(name = "service failure with status {0} is returned to the client as {0} with the plain message")
        @CsvSource({
                "400, emailContent is required.",
                "422, Groq returned no choices for this request.",
                "429, Groq rate limit reached.",
                "502, Failed to parse Groq response.",
                "503, Groq is temporarily overloaded.",
                "504, Groq did not respond in time."
        })
        void serviceFailuresBecomeStatusPlusMessage(int status, String message) throws Exception {
            given(emailGeneratorService.generateEmailReply(any(EmailRequest.class)))
                    .willThrow(new ResponseStatusException(HttpStatusCode.valueOf(status), message));

            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"emailContent":"Hello"}"""))
                    .andExpect(status().is(status))
                    .andExpect(content().string(message));
        }

        @Test
        @DisplayName("malformed JSON -> 400 and the service is never called")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not valid json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(emailGeneratorService);
        }

        @Test
        @DisplayName("missing body -> 400 and the service is never called")
        void missingBody() throws Exception {
            mockMvc.perform(post("/api/email/generate").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(emailGeneratorService);
        }

        @Test
        @DisplayName("non-JSON content type -> 415")
        void unsupportedContentType() throws Exception {
            mockMvc.perform(post("/api/email/generate")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Can we meet at 3?"))
                    .andExpect(status().isUnsupportedMediaType());

            verifyNoInteractions(emailGeneratorService);
        }

        @Test
        @DisplayName("GET is not allowed -> 405")
        void getIsNotAllowed() throws Exception {
            mockMvc.perform(get("/api/email/generate"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("POST /api/email/compose")
    class Compose {

        @Test
        @DisplayName("returns the composed email as the response body")
        void returnsComposedEmail() throws Exception {
            given(emailGeneratorService.generateNewEmail(any(EmailComposeRequest.class))).willReturn("Dear team, ...");

            mockMvc.perform(post("/api/email/compose")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subject":"Weekly update"}"""))
                    .andExpect(status().isOk())
                    .andExpect(content().string("Dear team, ..."));
        }

        @Test
        @DisplayName("passes recipientEmail, subject, tone and additionalContext to the service")
        void bindsAllFields() throws Exception {
            given(emailGeneratorService.generateNewEmail(any(EmailComposeRequest.class))).willReturn("ok");

            mockMvc.perform(post("/api/email/compose")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"recipientEmail":"hr@example.com","subject":"Leave request",
                                     "tone":"formal","additionalContext":"Two days next week"}"""))
                    .andExpect(status().isOk());

            ArgumentCaptor<EmailComposeRequest> captor = ArgumentCaptor.forClass(EmailComposeRequest.class);
            verify(emailGeneratorService).generateNewEmail(captor.capture());
            EmailComposeRequest bound = captor.getValue();
            assertThat(bound.getRecipientEmail()).isEqualTo("hr@example.com");
            assertThat(bound.getSubject()).isEqualTo("Leave request");
            assertThat(bound.getTone()).isEqualTo("formal");
            assertThat(bound.getAdditionalContext()).isEqualTo("Two days next week");
        }

        @ParameterizedTest(name = "service failure with status {0} is returned to the client as {0} with the plain message")
        @CsvSource({
                "400, subject is required.",
                "422, Groq blocked this content.",
                "503, Could not reach Groq.",
                "504, Groq did not respond in time."
        })
        void serviceFailuresBecomeStatusPlusMessage(int status, String message) throws Exception {
            given(emailGeneratorService.generateNewEmail(any(EmailComposeRequest.class)))
                    .willThrow(new ResponseStatusException(HttpStatusCode.valueOf(status), message));

            mockMvc.perform(post("/api/email/compose")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subject":"Hello"}"""))
                    .andExpect(status().is(status))
                    .andExpect(content().string(message));
        }

        @Test
        @DisplayName("malformed JSON -> 400 and the service is never called")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/email/compose")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[1, 2"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(emailGeneratorService);
        }
    }

    @Nested
    @DisplayName("CORS (the React frontend is served from another origin)")
    class Cors {

        @Test
        @DisplayName("preflight from any origin is allowed for POST")
        void preflightIsAllowed() throws Exception {
            mockMvc.perform(options("/api/email/generate")
                            .header("Origin", "https://my-frontend.netlify.app")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        }
    }
}