package com.nutribot.nutribot.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Thin wrapper around TelegramClient so services (ReminderService, SupplementService, etc.)
 * can send Telegram messages without a direct dependency on NutriBot.
 * TelegramClient is injected as a Spring bean from BotConfig.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageSender {

    private final TelegramClient telegramClient;

    public void sendMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("[TelegramMessageSender] chatId={} error: {}", chatId, e.getMessage(), e);
        }
    }

    public void send(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("[TelegramMessageSender] send error: {}", e.getMessage(), e);
        }
    }

    /**
     * Executes the given SendMessage and returns the sent message's ID,
     * or null if sending failed.
     */
    public Integer sendAndGetId(SendMessage message) {
        try {
            Message sent = telegramClient.execute(message);
            return sent != null ? sent.getMessageId() : null;
        } catch (TelegramApiException e) {
            log.error("[TelegramMessageSender] sendAndGetId error: {}", e.getMessage(), e);
            return null;
        }
    }
}
