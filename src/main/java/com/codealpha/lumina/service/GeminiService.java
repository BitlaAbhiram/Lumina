package com.codealpha.lumina.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GeminiService ───────────── Calls the Google Gemini 2.0 Flash API and
 * maintains per-session conversation history so the model has context across
 * turns.
 *
 * Fixes over the original: • Correct model name: gemini-2.0-flash • Proper JSON
 * parsing via Jackson (not brittle string indexOf) • Conversation history sent
 * on every request (multi-turn context) • Markdown preserved from the response
 * • Friendly error messages instead of raw stack traces • System instruction
 * sets Lumina's persona
 */
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    private static final String GEMINI_URL
            = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private static final String SYSTEM_INSTRUCTION
            = "You are Lumina, a friendly, professional, and highly capable AI assistant. "
            + "You are helpful, concise, and accurate. When responding, use markdown formatting "
            + "where appropriate (bold, italics, bullet points, code blocks). "
            + "Always be warm but professional.";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // Conversation history: list of {role, parts} objects
    private final List<Map<String, Object>> conversationHistory = new ArrayList<>();

    public GeminiService() {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends the user's prompt to Gemini with full conversation history. Adds
     * both the user turn and the assistant reply to history.
     */
    public String getResponse(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "Please type a message first.";
        }

        try {
            // Append user turn to history
            conversationHistory.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userPrompt))
            ));

            // Build request payload with system instruction + full history
            Map<String, Object> requestBody = Map.of(
                    "system_instruction", Map.of(
                            "parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))
                    ),
                    "contents", conversationHistory
            );

            String requestJson = objectMapper.writeValueAsString(requestBody);

            String rawResponse = webClient.post()
                    .uri(GEMINI_URL + "?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            String reply = extractText(rawResponse);

            // Append model turn to history so next call has full context
            conversationHistory.add(Map.of(
                    "role", "model",
                    "parts", List.of(Map.of("text", reply))
            ));

            // Keep history bounded to last 40 turns (20 exchanges) to avoid
            // exceeding token limits on long conversations
            if (conversationHistory.size() > 40) {
                conversationHistory.subList(0, 2).clear();
            }

            return reply;

        } catch (WebClientResponseException e) {

            System.out.println("STATUS = " + e.getStatusCode());
            System.out.println("BODY = " + e.getResponseBodyAsString());
            // HTTP error from the Gemini API
            int status = e.getStatusCode().value();
            if (status == 400) {
                return "⚠️ Invalid request. Please check your message.";
            }
            if (status == 401 || status == 403) {
                return "⚠️ API key is invalid or missing. Please set a valid Gemini API key in application.properties.";
            }
            if (status == 429) {
                return "⚠️ Gemini quota temporarily unavailable. Please try again later.";
            }
            return "⚠️ Gemini API error (" + status + "): " + e.getResponseBodyAsString();
        } catch (Exception e) {
            e.printStackTrace();
            return "⚠️ An unexpected error occurred: " + e.getMessage();
        }
    }

    /**
     * Clears the conversation history (used by the "New Chat" feature).
     */
    public void clearHistory() {
        conversationHistory.clear();
    }

    /**
     * Properly extracts the text from Gemini's JSON response using Jackson.
     * Handles the full response including newlines, quotes, and special chars.
     *
     * Response structure: { "candidates": [ { "content": { "parts": [ { "text":
     * "..." } ] } } ] }
     */
    private String extractText(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return "No response received from Gemini.";
        }
        try {
            JsonNode root = objectMapper.readTree(responseJson);

            // Check for API-level error object
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("Unknown error");
                return "⚠️ Gemini Error: " + errorMsg;
            }

            JsonNode text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text");

            if (text.isMissingNode() || text.isNull()) {
                // Check for safety block
                String finishReason = root
                        .path("candidates").get(0)
                        .path("finishReason").asText("");
                if ("SAFETY".equals(finishReason)) {
                    return "⚠️ Response blocked due to safety filters. Please rephrase your question.";
                }
                return "Lumina couldn't generate a response. Please try again.";
            }

            return text.asText();

        } catch (Exception e) {
            e.printStackTrace();
            return "Unable to parse Gemini response. Raw: " + responseJson.substring(0, Math.min(200, responseJson.length()));
        }
    }
}
