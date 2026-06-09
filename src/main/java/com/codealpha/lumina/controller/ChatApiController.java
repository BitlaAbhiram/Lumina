package com.codealpha.lumina.controller;

import com.codealpha.lumina.model.ChatRequest;
import com.codealpha.lumina.service.GeminiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ChatApiController
 * ─────────────────
 * REST endpoints consumed by the frontend JavaScript.
 *
 * POST /api/chat        — send a message, get a reply
 * POST /api/chat/clear  — reset conversation history
 */
@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final GeminiService geminiService;

    public ChatApiController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    /**
     * Main chat endpoint.
     * Accepts { "message": "..." } and returns the AI reply as plain text.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("reply", "Message cannot be empty."));
        }
        String reply = geminiService.getResponse(request.getMessage());
        return ResponseEntity.ok(Map.of("reply", reply));
    }

    /**
     * Clears the conversation history so the user can start fresh.
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, String>> clearHistory() {
        geminiService.clearHistory();
        return ResponseEntity.ok(Map.of("status", "Conversation cleared."));
    }
}