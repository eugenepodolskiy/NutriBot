package com.nutribot.nutribot.bot;

import com.nutribot.nutribot.dto.NutritionResponse;
import com.nutribot.nutribot.enums.LogSource;
import com.nutribot.nutribot.services.FoodLogService;
import com.nutribot.nutribot.services.GeminiService;
import com.nutribot.nutribot.services.OnboardingService;
import com.nutribot.nutribot.services.PendingConfirmationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class NutriBot extends TelegramLongPollingBot {

    private final GeminiService geminiService;
    private final FoodLogService foodLogService;
    private final OnboardingService onboardingService;
    private final TelegramMessageSender messageSender;
    private final PendingConfirmationService pendingConfirmationService;

    @Value("${telegram.bot.username}")
    private String botUsername;

    public NutriBot(@Value("${telegram.bot.token}") String token,
                    GeminiService geminiService,
                    FoodLogService foodLogService,
                    OnboardingService onboardingService,
                    TelegramMessageSender messageSender,
                    PendingConfirmationService pendingConfirmationService) {
        super(token);
        this.geminiService = geminiService;
        this.foodLogService = foodLogService;
        this.onboardingService = onboardingService;
        this.messageSender = messageSender;
        this.pendingConfirmationService = pendingConfirmationService;
    }

    @PostConstruct
    public void register() {
        messageSender.register(this);
    }

    // ── Confirmation keyword sets ──────────────────────────────────────────────

    private static final Set<String> YES_WORDS = Set.of(
            "yes", "да", "correct", "верно", "ok", "ок", "yep", "sure",
            "подтверждаю", "confirm", "right", "точно", "именно",
            "всё верно", "все верно"
    );
    private static final Set<String> NO_WORDS = Set.of(
            "no", "нет", "cancel", "отмена",
            "wrong", "неверно", "incorrect", "не то", "не правильно"
    );

    /** Strips trailing punctuation then lowercases — handles voice transcription artefacts. */
    private static String normalize(String text) {
        return text.trim().replaceAll("[.!?,;]+$", "").trim().toLowerCase();
    }
    // ── Update entry point ─────────────────────────────────────────────────────

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

        // TODO: message reactions require telegrambots 7.x (Bot API 7.0).
        // Upgrade telegrambots-spring-boot-starter to 7.x and add:
        //   if (update.hasMessageReaction()) { handleMessageReaction(...); return; }

        if (!update.hasMessage()) return;

        Message message = update.getMessage();
        Long chatId = message.getChatId();

        log.info("UPDATE: chatId={} hasText={} hasPhoto={} hasVoice={}",
                chatId, message.hasText(), message.hasPhoto(), message.hasVoice());

        if (message.hasText()) {
            String languageCode = message.getFrom() != null ? message.getFrom().getLanguageCode() : null;
            handleTextMessage(chatId, message.getText(), languageCode);
        } else if (message.hasPhoto()) {
            handlePhotoMessage(chatId, message.getPhoto());
        } else if (message.hasVoice()) {
            handleVoiceMessage(chatId, message.getVoice());
        }
    }

    // ── Text handler (also called after voice transcription) ──────────────────

    private void handleTextMessage(Long chatId, String text, String languageCode) {
        if (text.equals("/start")) {
            if (onboardingService.needsOnboarding(chatId)) {
                messageSender.sendMessage(chatId, onboardingService.start(chatId, languageCode));
            } else {
                String lang = foodLogService.getUserLanguage(chatId);
                messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "С возвращением! Просто напиши, что ты съел, и я запишу."
                        : "Welcome back! Just tell me what you ate and I'll log it for you.");
            }
            return;
        }

        if (onboardingService.isOnboarding(chatId)) {
            String response = onboardingService.handle(chatId, text);
            if (response != null) messageSender.sendMessage(chatId, response);
            return;
        }

        if (onboardingService.needsOnboarding(chatId)) {
            messageSender.sendMessage(chatId, "Please send /start to set up your profile first.");
            return;
        }

        // ── Confirmation check (before intent detection) ───────────────────────
        if (pendingConfirmationService.hasPending(chatId)) {
            handlePendingResponse(chatId, text);
            return;
        }

        String lang = foodLogService.getUserLanguage(chatId);
        String intent = geminiService.detectIntent(text, lang);

        switch (intent) {
            case "SUGGESTION" -> {
                try {
                    messageSender.sendMessage(chatId, foodLogService.getSuggestions(chatId, text));
                } catch (RuntimeException e) {
                    messageSender.sendMessage(chatId, e.getMessage());
                }
            }
            case "CORRECTION" -> messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Нечего исправлять. Сначала опиши, что ты съел."
                    : "Nothing to correct. First tell me what you ate.");
            case "UNCLEAR" -> messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Я помогаю отслеживать питание. Напиши, что ты съел, или спроси, что поесть."
                    : "I help track nutrition. Tell me what you ate, or ask for meal suggestions.");
            default -> analyzeAndStorePending(chatId, text); // FOOD_LOG
        }
    }

    // ── Pending-confirmation response handler ──────────────────────────────────

    private void handlePendingResponse(Long chatId, String text) {
        String lower = normalize(text);
        String lang  = foodLogService.getUserLanguage(chatId);

        if (YES_WORDS.contains(lower)) {
            PendingConfirmationService.PendingLog p = pendingConfirmationService.getPending(chatId);
            pendingConfirmationService.clear(chatId);
            try {
                String summary = foodLogService.confirmSave(chatId,
                        p.getNutrition(), p.getLogSource(), p.getDescription());
                messageSender.sendMessage(chatId, summary);
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if (NO_WORDS.contains(lower)) {
            pendingConfirmationService.clear(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Отменено. Опиши еду по-другому, и я попробую снова."
                    : "Cancelled. Describe the food differently and I'll try again.");

        } else {
            // Treat as a correction — re-analyse with the new description
            try {
                NutritionResponse corrected = foodLogService.parseText(chatId, text);
                pendingConfirmationService.store(chatId, corrected, LogSource.AI_DECOMPOSED, text);
                Integer msgId = messageSender.sendAndGetId(buildConfirmationSendMessage(chatId, lang, corrected));
                pendingConfirmationService.getPending(chatId).setConfirmationMessageId(msgId);
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
        }
    }

    // ── Photo handler ──────────────────────────────────────────────────────────

    private void handlePhotoMessage(Long chatId, List<PhotoSize> photos) {
        if (onboardingService.isOnboarding(chatId) || onboardingService.needsOnboarding(chatId)) {
            messageSender.sendMessage(chatId,
                    "Please complete your profile setup with /start before logging food photos.");
            return;
        }

        // Telegram sends multiple sizes; last entry is highest resolution
        PhotoSize photo = photos.get(photos.size() - 1);
        byte[] imageBytes;
        try {
            imageBytes = downloadTelegramFile(photo.getFileId());
        } catch (Exception e) {
            String lang = foodLogService.getUserLanguage(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Не удалось загрузить фото. Попробуйте ещё раз."
                    : "Sorry, couldn't download that photo. Please try again.");
            return;
        }

        try {
            NutritionResponse nutrition = foodLogService.parsePhoto(chatId, imageBytes);
            String lang = foodLogService.getUserLanguage(chatId);
            pendingConfirmationService.store(chatId, nutrition, LogSource.AI_DECOMPOSED, "photo");
            Integer msgId = messageSender.sendAndGetId(buildConfirmationSendMessage(chatId, lang, nutrition));
            pendingConfirmationService.getPending(chatId).setConfirmationMessageId(msgId);
        } catch (RuntimeException e) {
            messageSender.sendMessage(chatId, e.getMessage());
        }
    }

    // ── Voice handler ──────────────────────────────────────────────────────────

    private void handleVoiceMessage(Long chatId, Voice voice) {
        log.info("VOICE: chatId={}", chatId);
        try {
            byte[] audioBytes = downloadTelegramFile(voice.getFileId());
            String transcribed = geminiService.transcribeAudio(audioBytes);
            log.info("VOICE: transcribed='{}'", transcribed);

            if (transcribed == null || transcribed.isBlank()) {
                String lang = foodLogService.getUserLanguage(chatId);
                messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "Не удалось распознать голосовое сообщение. Попробуйте говорить чётче или напишите текстом."
                        : "Couldn't understand the voice message. Try speaking more clearly or send a text message.");
                return;
            }

            boolean hasPending = pendingConfirmationService.hasPending(chatId);
            log.info("VOICE: hasPending={}", hasPending);
            if (hasPending) {
                log.info("VOICE: routing to handlePendingResponse");
                handlePendingResponse(chatId, transcribed);
                return;
            }

            if (onboardingService.isOnboarding(chatId) || onboardingService.needsOnboarding(chatId)) {
                handleTextMessage(chatId, transcribed, null);
                return;
            }

            String lang = foodLogService.getUserLanguage(chatId);
            String intent = geminiService.detectIntent(transcribed, lang);
            log.info("VOICE: intent={}", intent);
            switch (intent) {
                case "SUGGESTION" -> {
                    try {
                        messageSender.sendMessage(chatId, foodLogService.getSuggestions(chatId, transcribed));
                    } catch (RuntimeException e) {
                        messageSender.sendMessage(chatId, e.getMessage());
                    }
                }
                case "CORRECTION" -> messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "Нечего исправлять. Сначала опиши, что ты съел."
                        : "Nothing to correct. First tell me what you ate.");
                case "UNCLEAR" -> messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "Я помогаю отслеживать питание. Напиши, что ты съел, или спроси, что поесть."
                        : "I help track nutrition. Tell me what you ate, or ask for meal suggestions.");
                default -> analyzeAndStorePending(chatId, transcribed); // FOOD_LOG
            }
        } catch (Exception e) {
            log.error("[handleVoiceMessage] chatId={} error: {}", chatId, e.getMessage(), e);
            String lang = foodLogService.getUserLanguage(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Не удалось обработать голосовое сообщение. Попробуйте ещё раз."
                    : "Sorry, couldn't process that voice message. Please try again.");
        }
    }

    // ── Shared analysis helper ─────────────────────────────────────────────────

    /**
     * Parses a food description, stores the result as pending, and sends the
     * confirmation message. Used by both the text and voice (via handleTextMessage)
     * paths.
     */
    private void analyzeAndStorePending(Long chatId, String description) {
        try {
            NutritionResponse nutrition = foodLogService.parseText(chatId, description);
            String lang = foodLogService.getUserLanguage(chatId);
            pendingConfirmationService.store(chatId, nutrition, LogSource.AI_DECOMPOSED, description);
            Integer msgId = messageSender.sendAndGetId(buildConfirmationSendMessage(chatId, lang, nutrition));
            pendingConfirmationService.getPending(chatId).setConfirmationMessageId(msgId);
        } catch (RuntimeException e) {
            messageSender.sendMessage(chatId, e.getMessage());
        }
    }

    // ── Callback query handler ─────────────────────────────────────────────────

    private void handleCallbackQuery(CallbackQuery query) {
        Long chatId = query.getMessage().getChatId();
        String data  = query.getData();
        String lang  = foodLogService.getUserLanguage(chatId);

        // Acknowledge the button press so Telegram removes the loading spinner
        AnswerCallbackQuery ack = new AnswerCallbackQuery();
        ack.setCallbackQueryId(query.getId());
        try { execute(ack); } catch (TelegramApiException ignored) {}

        if ("confirm".equals(data)) {
            if (!pendingConfirmationService.hasPending(chatId)) {
                messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "Нет ожидающих записей."
                        : "Nothing pending to confirm.");
                return;
            }
            PendingConfirmationService.PendingLog p = pendingConfirmationService.getPending(chatId);
            pendingConfirmationService.clear(chatId);
            try {
                String summary = foodLogService.confirmSave(chatId,
                        p.getNutrition(), p.getLogSource(), p.getDescription());
                messageSender.sendMessage(chatId, summary);
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("cancel".equals(data)) {
            pendingConfirmationService.clear(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Отменено. Опиши еду по-другому, и я попробую снова."
                    : "Cancelled. Describe the food differently and I'll try again.");
        }
    }

    // ── Confirmation message builders ──────────────────────────────────────────

    /** Builds a SendMessage with the nutrition summary and confirm/cancel inline keyboard. */
    private static SendMessage buildConfirmationSendMessage(Long chatId, String lang, NutritionResponse n) {
        InlineKeyboardButton yesBtn = new InlineKeyboardButton();
        yesBtn.setText("RU".equals(lang) ? "Да, верно" : "Yes, correct");
        yesBtn.setCallbackData("confirm");

        InlineKeyboardButton noBtn = new InlineKeyboardButton();
        noBtn.setText("RU".equals(lang) ? "Нет, отмена" : "No, cancel");
        noBtn.setCallbackData("cancel");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(List.of(yesBtn, noBtn)));

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(buildConfirmationText(lang, n));
        msg.setReplyMarkup(keyboard);
        return msg;
    }

    /** Nutrition summary text without any yes/no instruction — the keyboard buttons replace that. */
    private static String buildConfirmationText(String lang, NutritionResponse n) {
        double grams    = n.getGrams()    != null ? n.getGrams()    : 0;
        double calories = n.getCalories() != null ? n.getCalories() : 0;
        double protein  = n.getProtein()  != null ? n.getProtein()  : 0;
        double carbs    = n.getCarbs()    != null ? n.getCarbs()    : 0;
        double fat      = n.getFat()      != null ? n.getFat()      : 0;
        String name     = n.getFoodName() != null ? n.getFoodName() : "Unknown";

        if ("RU".equals(lang)) {
            return String.format(
                    "Я нашёл: %s (%.0fг)%nКалории: %.0f ккал | Белки: %.0fг | Углеводы: %.0fг | Жиры: %.0fг",
                    name, grams, calories, protein, carbs, fat);
        } else {
            return String.format(
                    "Found: %s (%.0fg)%nCalories: %.0f kcal | Protein: %.0fg | Carbs: %.0fg | Fat: %.0fg",
                    name, grams, calories, protein, carbs, fat);
        }
    }

    // ── File download helper ───────────────────────────────────────────────────

    private byte[] downloadTelegramFile(String fileId) throws TelegramApiException, java.io.IOException {
        File telegramFile = execute(new GetFile(fileId));
        try (InputStream stream = downloadFileAsStream(telegramFile)) {
            return stream.readAllBytes();
        }
    }

    // ── Bot identity ───────────────────────────────────────────────────────────

    @Override
    public String getBotUsername() {
        return botUsername;
    }
}
