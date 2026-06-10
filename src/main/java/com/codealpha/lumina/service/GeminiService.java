package com.codealpha.lumina.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GeminiService
 * ─────────────
 * Calls the Google Gemini API and maintains per-session
 * conversation history so the model has context across turns.
 *
 * Model: gemini-2.5-flash-lite  (most generous free tier: 15 RPM, 1000 RPD)
 * API:   v1beta / generateContent (REST)
 */
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    // gemini-2.5-flash-lite: fastest, most generous free quota (15 RPM / 1000 RPD)
    private static final String MODEL = "gemini-2.5-flash-lite";

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String SYSTEM_INSTRUCTION =
            "You are Lumina, a friendly, professional, and highly capable AI assistant. " +
            "You are helpful, concise, and accurate. When responding, use markdown formatting " +
            "where appropriate — bold, italics, bullet points, numbered lists, code blocks. " +
            "Always be warm but professional. Never mention that you are built on Gemini or Google's technology.";

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    // Conversation history: list of {role, parts} turn objects
    private final List<Map<String, Object>> history = new ArrayList<>();

    public GeminiService() {
        this.webClient    = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)) // 4 MB buffer
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends the user's prompt to Gemini with full conversation history.
     */
    public String getResponse(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "Please type a message first.";
        }

        try {
            // Append user turn to history
            history.add(Map.of(
                    "role",  "user",
                    "parts", List.of(Map.of("text", userPrompt))
            ));

            // Build full request body
            Map<String, Object> requestBody = Map.of(
                    "system_instruction", Map.of(
                            "parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))
                    ),
                    "contents", history,
                    "generationConfig", Map.of(
                            "temperature",     0.7,
                            "maxOutputTokens", 2048
                    )
            );

            String requestJson = objectMapper.writeValueAsString(requestBody);
            String url         = BASE_URL + MODEL + ":generateContent?key=" + apiKey;

            // Log request for debugging
            System.out.println("[GeminiService] POST → " + BASE_URL + MODEL + ":generateContent");

            String rawResponse = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            System.out.println("[GeminiService] Raw response (first 300 chars): "
                    + (rawResponse != null ? rawResponse.substring(0, Math.min(300, rawResponse.length())) : "null"));

            String reply = extractText(rawResponse);

            // Append model reply to history (only if it's a real reply, not an error message)
            if (!reply.startsWith("⚠️")) {
                history.add(Map.of(
                        "role",  "model",
                        "parts", List.of(Map.of("text", reply))
                ));
            } else {
                // Remove the user turn we just added — don't pollute history with failed turns
                history.remove(history.size() - 1);
            }

            // Keep history bounded to last 40 turns to avoid token overflow
            while (history.size() > 40) {
                history.remove(0);
            }

            return reply;

        } catch (WebClientResponseException e) {
            // Remove the user turn we just added so history stays clean
            if (!history.isEmpty()) history.remove(history.size() - 1);

            int    status  = e.getStatusCode().value();
            String body    = e.getResponseBodyAsString();

            // Log the actual error body so it's visible in Spring Boot console
            System.err.println("[GeminiService] HTTP " + status + " from Gemini API:");
            System.err.println(body);

            // Try to extract the real error message from Gemini's JSON body
            String geminiError = parseGeminiError(body);

            return switch (status) {
                case 400 -> "⚠️ Bad request: " + geminiError +
                            "\n\nThis usually means the model name is wrong or the request format is invalid.";
                case 401, 403 -> "⚠️ Authentication failed. Please check your Gemini API key in `application.properties`.";
                case 404 -> "⚠️ Model not found: **" + MODEL + "**. " + geminiError;
                case 429 -> "⚠️ Rate limit reached on the free tier (" + MODEL + "). " +
                            "You're limited to 15 requests/minute and 1,000 requests/day. " +
                            "Please wait a moment and try again.";
                case 500, 503 -> "⚠️ Gemini service is temporarily unavailable. Please try again in a moment.";
                default  -> "⚠️ API error (HTTP " + status + "): " + geminiError;
            };

        } catch (Exception e) {
            if (!history.isEmpty()) history.remove(history.size() - 1);
            System.err.println("[GeminiService] Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return "⚠️ Unexpected error: " + e.getMessage();
        }
    }

    /**
     * Clears conversation history — called by the New Chat button.
     */
    public void clearHistory() {
        history.clear();
        System.out.println("[GeminiService] Conversation history cleared.");
    }

    /**
     * Parses the text out of Gemini's response JSON:
     * { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
     */
    private String extractText(String json) {
        if (json == null || json.isBlank()) {
            return "⚠️ Empty response from Gemini API.";
        }
        try {
            JsonNode root = objectMapper.readTree(json);

            // Check for top-level error object
            if (root.has("error")) {
                String msg    = root.path("error").path("message").asText("Unknown error");
                int    code   = root.path("error").path("code").asInt(0);
                System.err.println("[GeminiService] API error object: " + code + " — " + msg);
                if (code == 429) {
                    return "⚠️ Rate limit reached. Please wait a moment and try again.";
                }
                return "⚠️ Gemini error " + code + ": " + msg;
            }

            // Normal path: candidates[0].content.parts[0].text
            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) {
                // Could be a promptFeedback block (safety filter on the input)
                String blocked = root.path("promptFeedback").path("blockReason").asText("");
                if (!blocked.isBlank()) {
                    return "⚠️ Your message was blocked by Gemini's safety filter (" + blocked + "). Please rephrase.";
                }
                return "⚠️ No candidates returned by Gemini. Raw: " + json.substring(0, Math.min(300, json.length()));
            }

            JsonNode candidate  = candidates.get(0);
            String   finishReason = candidate.path("finishReason").asText("");

            if ("SAFETY".equals(finishReason)) {
                return "⚠️ Response blocked by Gemini's safety filters. Please rephrase your question.";
            }

            JsonNode text = candidate.path("content").path("parts").get(0).path("text");

            if (text.isMissingNode() || text.isNull()) {
                return "⚠️ Gemini returned an empty response (finishReason: " + finishReason + ").";
            }

            return text.asText();

        } catch (Exception e) {
            System.err.println("[GeminiService] Failed to parse response: " + e.getMessage());
            return "⚠️ Failed to parse Gemini response. Check the server console for details.";
        }
    }

    /**
     * Tries to pull the message from a Gemini error JSON body.
     */
    private String parseGeminiError(String body) {
        if (body == null || body.isBlank()) return "(no details)";
        try {
            JsonNode root = objectMapper.readTree(body);
            String msg = root.path("error").path("message").asText("");
            return msg.isBlank() ? body.substring(0, Math.min(200, body.length())) : msg;
        } catch (Exception e) {
            return body.substring(0, Math.min(200, body.length()));
        }
    }
}