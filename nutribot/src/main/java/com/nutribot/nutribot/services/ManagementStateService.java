package com.nutribot.nutribot.services;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds transient management-flow state per user.
 * Currently used to track when a user is awaiting a supplement number
 * to delete after being shown a numbered list.
 */
@Service
public class ManagementStateService {

    public static final String AWAITING_SUPPLEMENT_DELETE = "AWAITING_SUPPLEMENT_DELETE";

    private final Map<Long, String> states = new ConcurrentHashMap<>();

    public void setState(Long chatId, String state) {
        states.put(chatId, state);
    }

    public String getState(Long chatId) {
        return states.get(chatId);
    }

    public boolean hasState(Long chatId) {
        return states.containsKey(chatId);
    }

    public void clearState(Long chatId) {
        states.remove(chatId);
    }
}
