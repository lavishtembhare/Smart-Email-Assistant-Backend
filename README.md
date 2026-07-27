# Smart Email Assistant — Backend

AI-powered email drafting API built with Spring Boot, using Google's Gemini API to generate
context-aware replies and compose new emails from scratch. Powers both the companion React web
app and the Gmail Chrome extension.

## Features

- Generate a reply to an existing email thread, with an adjustable tone
- Compose a brand-new email from just a subject line (recipient and extra context optional)
- Resilient Gemini integration: automatic retry with backoff on transient failures, request
  timeout, and specific error messages for rate limits, invalid API keys, safety blocks, and
  truncated responses
- Plain-text responses — no client-side JSON unwrapping needed

## Tech stack

- Java, Spring Boot 4.1.0 (`spring-boot-starter-webmvc`, `spring-boot-starter-webflux`,
  `spring-boot-starter-webclient`)
- Spring `WebClient` — reactive, non-blocking calls to the Gemini API
- Project Reactor `Retry` (exponential backoff on 429/503 and connectivity failures)
- Jackson 3.x (`tools.jackson`) for response parsing
- Lombok

## Prerequisites

- JDK 17 or newer
- Maven, or the bundled wrapper (`./mvnw`)
- A Google Gemini API key — get one at [Google AI Studio](https://aistudio.google.com/)

## Setup

1. Clone the repo and move into this backend folder.
2. Set two required environment variables:

   | Variable | Description | Example |
      |---|---|---|
   | `GEMINI_URL` | Full Gemini `generateContent` endpoint | `https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent` |
   | `GEMINI_KEY` | Your Gemini API key | `AIza...` |

   Set them however fits your workflow:
    - **IntelliJ**: Run → Edit Configurations → Environment variables
    - **Shell**: `export GEMINI_URL=... GEMINI_KEY=...` before running
    - **Render / hosted platforms**: set them in the service's environment variable settings

   `application.properties` reads them as:
   ```properties
   gemini.api.url=${GEMINI_URL}
   gemini.api.key=${GEMINI_KEY}
   ```

3. Run it:
   ```bash
   ./mvnw spring-boot:run
   ```
   The API starts on `http://localhost:8080` by default.

## API reference

### `POST /api/email/generate` — reply to an email

```json
{
  "emailContent": "hey can you send me the info asap, kinda swamped rn",
  "tone": "professional"
}
```

`emailContent` is required. `tone` is optional (`formal`, `informal`, `friendly`,
`professional`, or omitted). Returns the generated reply as **plain text**.

### `POST /api/email/compose` — write a new email from scratch

```json
{
  "recipientEmail": "priya.sharma@example.com",
  "subject": "Follow-up on our meeting next week",
  "tone": "formal",
  "additionalContext": "Mention that the deadline moved to Friday."
}
```

Only `subject` is required — `recipientEmail`, `tone`, and `additionalContext` are all
optional. Returns the generated email body as **plain text**.

### Error responses

Failures come back as a plain-text body with an appropriate HTTP status:

| Status | Meaning |
|---|---|
| `400` | Missing a required field (`emailContent` / `subject`) |
| `401` / `403` | Gemini rejected the API key — check `GEMINI_KEY` |
| `422` | Content blocked by Gemini's safety filters |
| `429` / `503` | Gemini rate-limited or temporarily overloaded (already retried automatically before this is returned) |
| `502` | Gemini returned an empty or unparseable response |
| `504` | Gemini didn't respond in time |

## CORS

Currently configured with `@CrossOrigin(origins = "*")` for ease of development across the web
app, browser extension, and local testing. **Tighten this before shipping publicly** — restrict
to your actual frontend origin(s) and `https://mail.google.com`.

## Project structure

```
src/main/java/com/main/Smart_Email_Assistant/
├── SmartEmailAssistantApplication.java
└── app/
    ├── SmartEmailAssistantController.java   # REST endpoints
    ├── EmailGeneratorService.java           # Gemini integration, retry/error handling
    ├── EmailRequest.java                    # DTO: reply mode
    └── EmailComposeRequest.java             # DTO: compose mode
```

## Deployment

Runs as a standard Spring Boot web service — tested on Render.com. Set the two environment
variables in your host's dashboard and point it at this repo/build.