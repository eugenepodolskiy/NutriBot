package com.nutribot.nutribot.bot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Thin wrapper around AbsSender so other services (e.g. ReminderService) can send
 * Telegram messages without creating a circular dependency on NutriBot itself.
 * NutriBot registers itself via @PostConstruct after Spring finishes wiring.
 */
@Component
public class TelegramMessageSender {

    private AbsSender bot;

    void register(AbsSender bot) {
        this.bot = bot;
    }

    public void sendMessage(Long chatId, String text) {
        if (bot == null) return;
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Executes the given SendMessage and returns the sent message's ID,
     * or null if sending failed. Use this when the message ID is needed
     * (e.g. to track which message a callback query refers to).
     */
    public Integer sendAndGetId(SendMessage message) {
        if (bot == null) return null;
        try {
            Message sent = bot.execute(message);
            return sent != null ? sent.getMessageId() : null;
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }
}
