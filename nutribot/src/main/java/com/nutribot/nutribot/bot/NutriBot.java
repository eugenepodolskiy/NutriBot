package com.nutribot.nutribot.bot;

import com.nutribot.nutribot.services.FoodLogService;
import com.nutribot.nutribot.services.GeminiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
public class NutriBot extends TelegramLongPollingBot {

    private final GeminiService geminiService;
    private final FoodLogService foodLogService;

    public NutriBot(@Value("${telegram.bot.token}") String token,
                    GeminiService geminiService, FoodLogService foodLogService) {
        super(token);
        this.geminiService = geminiService;
        this.foodLogService = foodLogService;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();

            if (text.equals("/start")) {
                sendMessage(chatId, "Welcome to NutriBot! Let's set up your profile.\nWhat's your name?");
                return;
            }

            try {
                String response = foodLogService.logFood(chatId, text);
                sendMessage(chatId, response);
            } catch (RuntimeException e) {
                sendMessage(chatId, e.getMessage());
            }
        }

    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return System.getenv("TELEGRAM_BOT_USERNAME");
    }
}
