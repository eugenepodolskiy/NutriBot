package com.nutribot.nutribot.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Creates the shared TelegramClient bean (OkHttpTelegramClient) used by NutriBot,
 * TelegramMessageSender, and any other component that needs to call the Telegram API.
 * Bot registration with the long-polling mechanism is handled automatically by the
 * telegrambots-spring-boot-starter when it detects a SpringLongPollingBot bean.
 */
@Configuration
public class BotConfig {

    @Bean
    public TelegramClient telegramClient(@Value("${telegram.bot.token}") String token) {
        return new OkHttpTelegramClient(token);
    }
}
