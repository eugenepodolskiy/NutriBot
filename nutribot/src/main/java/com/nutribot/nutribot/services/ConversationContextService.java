package com.nutribot.nutribot.services;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a short-term, in-memory rolling log of the last 5 food items a user
 * described via text. This is passed to GeminiService so Gemini can resolve
 * references like "the same yogurt as before" without a database lookup.
 */
@Service
public class ConversationContextService {

    private static final int MAX_ENTRIES = 5;

    private final Map<Long, Deque<String>> contexts = new ConcurrentHashMap<>();

    /**
     * Returns the user's recent food entries, oldest first, as an unmodifiable snapshot.
     * Returns an empty list if the user has no context yet.
     */
    public List<String> getContext(Long telegramId) {
        Deque<String> deque = contexts.get(telegramId);
        if (deque == null || deque.isEmpty()) return List.of();
        return new ArrayList<>(deque);
    }

    /**
     * Pushes a new entry to the user's context deque. If the deque already has
     * MAX_ENTRIES items, the oldest one is evicted first.
     */
    public void push(Long telegramId, String entry) {
        Deque<String> deque = contexts.computeIfAbsent(telegramId, k -> new ArrayDeque<>());
        deque.addLast(entry);
        while (deque.size() > MAX_ENTRIES) deque.pollFirst();
    }
}
