package com.nutribot.nutribot.bot;

import com.nutribot.nutribot.dto.NutritionResponse;
import com.nutribot.nutribot.enums.LogSource;
import com.nutribot.nutribot.services.*;
import com.nutribot.nutribot.services.ManagementService;
import com.nutribot.nutribot.services.ManagementStateService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.reactions.MessageReactionUpdated;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class NutriBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient            telegramClient;
    private final GeminiService             geminiService;
    private final FoodLogService            foodLogService;
    private final OnboardingService         onboardingService;
    private final TelegramMessageSender     messageSender;
    private final PendingConfirmationService pendingConfirmationService;
    private final SupplementService         supplementService;
    private final UserService               userService;
    private final ManagementService         managementService;
    private final ManagementStateService    managementStateService;

    @Value("${telegram.bot.token}")
    private String botToken;

    public NutriBot(TelegramClient telegramClient,
                    GeminiService geminiService,
                    FoodLogService foodLogService,
                    OnboardingService onboardingService,
                    TelegramMessageSender messageSender,
                    PendingConfirmationService pendingConfirmationService,
                    SupplementService supplementService,
                    UserService userService,
                    ManagementService managementService,
                    ManagementStateService managementStateService) {
        this.telegramClient             = telegramClient;
        this.geminiService              = geminiService;
        this.foodLogService             = foodLogService;
        this.onboardingService          = onboardingService;
        this.messageSender              = messageSender;
        this.pendingConfirmationService = pendingConfirmationService;
        this.supplementService          = supplementService;
        this.userService                = userService;
        this.managementService          = managementService;
        this.managementStateService     = managementStateService;
    }

    @PostConstruct
    public void registerCommands() {
        List<BotCommand> commands = List.of(
                BotCommand.builder().command("start").description("Start or restart profile setup").build(),
                BotCommand.builder().command("log").description("Log a meal (or just type what you ate)").build(),
                BotCommand.builder().command("today").description("View today's food log").build(),
                BotCommand.builder().command("history").description("7-day nutrition history").build(),
                BotCommand.builder().command("suggest").description("Get meal suggestions based on remaining goals").build(),
                BotCommand.builder().command("supplements").description("View my supplements").build(),
                BotCommand.builder().command("goals").description("View my daily nutrition goals").build(),
                BotCommand.builder().command("manage").description("Manage data & profile").build()
        );
        try {
            telegramClient.execute(SetMyCommands.builder().commands(commands).build());
            log.info("Bot commands registered successfully");
        } catch (TelegramApiException e) {
            log.error("Failed to register bot commands: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
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

    private static String normalize(String text) {
        return text.trim().replaceAll("[.!?,;]+$", "").trim().toLowerCase();
    }

    // ── Update entry point ─────────────────────────────────────────────────────

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

        // Feature 1: emoji reactions as confirmation (Bot API 7.0).
        // Requires "message_reaction" in allowed_updates — configure via
        // DefaultGetUpdatesGenerator or telegramUrl in TelegramBotsLongPollingApplication.
        if (update.getMessageReaction() != null) {
            handleMessageReaction(update.getMessageReaction());
            return;
        }

        if (!update.hasMessage()) return;

        Message message = update.getMessage();
        Long chatId = message.getChatId();

        log.info("UPDATE: chatId={} hasText={} hasPhoto={} hasVoice={}",
                chatId, message.hasText(), message.hasPhoto(), message.hasVoice());

        if (message.hasText()) {
            String languageCode = message.getFrom() != null ? message.getFrom().getLanguageCode() : null;
            handleTextMessage(chatId, message.getText(), languageCode);
        } else if (message.hasPhoto()) {
            handlePhotoMessage(chatId, message.getPhoto(), message.getCaption());
        } else if (message.hasVoice()) {
            handleVoiceMessage(chatId, message.getVoice());
        }
    }

    // ── Feature 1: message-reaction confirmation ───────────────────────────────

    private void handleMessageReaction(MessageReactionUpdated reaction) {
        if (reaction.getNewReaction() == null || reaction.getNewReaction().isEmpty()) return;

        Long chatId   = reaction.getChat().getId();
        Integer msgId = reaction.getMessageId();

        if (!pendingConfirmationService.hasPending(chatId)) return;
        PendingConfirmationService.PendingLog p = pendingConfirmationService.getPending(chatId);

        if (p.getConfirmationMessageId() == null || !p.getConfirmationMessageId().equals(msgId)) return;

        pendingConfirmationService.clear(chatId);
        try {
            String summary = foodLogService.confirmSave(chatId,
                    p.getNutrition(), p.getLogSource(), p.getDescription());
            messageSender.sendMessage(chatId, summary);
        } catch (RuntimeException e) {
            messageSender.sendMessage(chatId, e.getMessage());
        }
    }

    // ── Text handler ───────────────────────────────────────────────────────────

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

        if (text.equals("/manage")) {
            String lang = foodLogService.getUserLanguage(chatId);
            messageSender.sendAndGetId(buildManageMenu(chatId, lang));
            return;
        }

        if (text.equals("/today")) {
            try {
                messageSender.sendMessage(chatId, foodLogService.getRecentLogs(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (text.equals("/history")) {
            try {
                messageSender.sendMessage(chatId, foodLogService.getHistory(chatId, 7));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (text.equals("/suggest")) {
            try {
                messageSender.sendMessage(chatId, foodLogService.getSuggestions(chatId, "suggest a healthy meal for me"));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (text.equals("/supplements")) {
            try {
                messageSender.sendMessage(chatId, managementService.viewSupplements(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (text.equals("/goals")) {
            try {
                messageSender.sendMessage(chatId, managementService.viewGoals(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (text.equals("/log")) {
            String lang = foodLogService.getUserLanguage(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Опиши, что ты съел, и я запишу. Например: «200г куриной грудки с рисом»."
                    : "Describe what you ate and I'll log it. For example: \"200g chicken breast with rice\".");
            return;
        }

        // Management state: user is replying with a supplement number to delete
        if (managementStateService.hasState(chatId) &&
                ManagementStateService.AWAITING_SUPPLEMENT_DELETE.equals(managementStateService.getState(chatId))) {
            try {
                messageSender.sendMessage(chatId, managementService.processDeleteSupplementByNumber(chatId, text));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }
            return;
        }

        if (pendingConfirmationService.hasPending(chatId)) {
            handlePendingResponse(chatId, text);
            return;
        }

        String lang   = foodLogService.getUserLanguage(chatId);
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
            case "EDIT_LOG" -> {
                try {
                    messageSender.sendMessage(chatId, foodLogService.editLastLog(chatId, text));
                } catch (RuntimeException e) {
                    messageSender.sendMessage(chatId, e.getMessage());
                }
            }
            case "HISTORY" -> {
                try {
                    messageSender.sendMessage(chatId, foodLogService.getHistory(chatId, 7));
                } catch (RuntimeException e) {
                    messageSender.sendMessage(chatId, e.getMessage());
                }
            }
            case "SUPPLEMENT" -> {
                try {
                    messageSender.sendMessage(chatId, supplementService.handleMessage(chatId, text, lang));
                } catch (RuntimeException e) {
                    messageSender.sendMessage(chatId, e.getMessage());
                }
            }
            case "PROFILE_UPDATE" -> {
                try {
                    messageSender.sendMessage(chatId, userService.handleProfileUpdate(chatId, text, lang));
                } catch (RuntimeException e) {
                    messageSender.sendMessage(chatId, e.getMessage());
                }
            }
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

    private void handlePhotoMessage(Long chatId, List<PhotoSize> photos, String caption) {
        if (onboardingService.isOnboarding(chatId) || onboardingService.needsOnboarding(chatId)) {
            messageSender.sendMessage(chatId,
                    "Please complete your profile setup with /start before logging food photos.");
            return;
        }

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
            NutritionResponse nutrition = foodLogService.parsePhoto(chatId, imageBytes, caption);
            String lang = foodLogService.getUserLanguage(chatId);
            String description = (caption != null && !caption.isBlank()) ? caption : "photo";
            pendingConfirmationService.store(chatId, nutrition, LogSource.AI_DECOMPOSED, description);
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
            log.info("VOICE transcribed='{}'", transcribed);

            if (transcribed == null || transcribed.isBlank()) {
                String lang = foodLogService.getUserLanguage(chatId);
                messageSender.sendMessage(chatId, "RU".equals(lang)
                        ? "Не удалось распознать голосовое сообщение. Попробуйте говорить чётче или напишите текстом."
                        : "Couldn't understand the voice message. Try speaking more clearly or send a text message.");
                return;
            }

            if (pendingConfirmationService.hasPending(chatId)) {
                handlePendingResponse(chatId, transcribed);
                return;
            }

            handleTextMessage(chatId, transcribed, null);

        } catch (Exception e) {
            log.error("[handleVoiceMessage] chatId={} error: {}", chatId, e.getMessage(), e);
            String lang = foodLogService.getUserLanguage(chatId);
            messageSender.sendMessage(chatId, "RU".equals(lang)
                    ? "Не удалось обработать голосовое сообщение. Попробуйте ещё раз."
                    : "Sorry, couldn't process that voice message. Please try again.");
        }
    }

    // ── Shared analysis helper ─────────────────────────────────────────────────

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

        AnswerCallbackQuery ack = AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .build();
        try { telegramClient.execute(ack); } catch (TelegramApiException ignored) {}

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

        } else if ("mgmt_del_last".equals(data)) {
            try {
                messageSender.sendMessage(chatId, managementService.deleteLastFoodLog(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("mgmt_clear_today".equals(data)) {
            try {
                messageSender.sendMessage(chatId, managementService.clearTodayFoodLog(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("mgmt_view_supps".equals(data)) {
            try {
                messageSender.sendMessage(chatId, managementService.viewSupplements(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("mgmt_del_supp".equals(data)) {
            try {
                messageSender.sendMessage(chatId, managementService.startDeleteSupplement(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("mgmt_reset_profile".equals(data)) {
            // Clear any in-flight states before wiping the user
            pendingConfirmationService.clear(chatId);
            managementStateService.clearState(chatId);
            String languageCode = null; // language will be re-detected during onboarding
            try {
                messageSender.sendMessage(chatId, managementService.resetProfile(chatId, languageCode));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if ("mgmt_view_goals".equals(data)) {
            try {
                messageSender.sendMessage(chatId, managementService.viewGoals(chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if (data != null && data.startsWith("supp_taken:")) {
            try {
                long supplementId = Long.parseLong(data.substring("supp_taken:".length()));
                messageSender.sendMessage(chatId, supplementService.logTaken(supplementId, chatId));
            } catch (RuntimeException e) {
                messageSender.sendMessage(chatId, e.getMessage());
            }

        } else if (data != null && data.startsWith("supp_skip:")) {
            messageSender.sendMessage(chatId, "RU".equals(lang) ? "Хорошо, пропущено." : "OK, skipped.");
        }
    }

    // ── /manage menu builder ───────────────────────────────────────────────────

    private static SendMessage buildManageMenu(Long chatId, String lang) {
        boolean ru = "RU".equals(lang);

        InlineKeyboardButton delLastBtn = InlineKeyboardButton.builder()
                .text(ru ? "Удалить последнюю запись" : "Delete last food log entry")
                .callbackData("mgmt_del_last").build();

        InlineKeyboardButton clearTodayBtn = InlineKeyboardButton.builder()
                .text(ru ? "Очистить лог за сегодня" : "Clear today's food log")
                .callbackData("mgmt_clear_today").build();

        InlineKeyboardButton viewSuppsBtn = InlineKeyboardButton.builder()
                .text(ru ? "Мои добавки" : "View my supplements")
                .callbackData("mgmt_view_supps").build();

        InlineKeyboardButton delSuppBtn = InlineKeyboardButton.builder()
                .text(ru ? "Удалить добавку" : "Delete a supplement")
                .callbackData("mgmt_del_supp").build();

        InlineKeyboardButton resetBtn = InlineKeyboardButton.builder()
                .text(ru ? "Сбросить профиль" : "Reset my profile")
                .callbackData("mgmt_reset_profile").build();

        InlineKeyboardButton goalsBtn = InlineKeyboardButton.builder()
                .text(ru ? "Мои цели" : "View my current goals")
                .callbackData("mgmt_view_goals").build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(delLastBtn, clearTodayBtn))
                .keyboardRow(new InlineKeyboardRow(viewSuppsBtn, delSuppBtn))
                .keyboardRow(new InlineKeyboardRow(resetBtn, goalsBtn))
                .build();

        return SendMessage.builder()
                .chatId(chatId)
                .text(ru ? "Управление данными:" : "Data management:")
                .replyMarkup(keyboard)
                .build();
    }

    // ── Confirmation message builders ──────────────────────────────────────────

    private static SendMessage buildConfirmationSendMessage(Long chatId, String lang, NutritionResponse n) {
        InlineKeyboardButton yesBtn = InlineKeyboardButton.builder()
                .text("RU".equals(lang) ? "Да, верно" : "Yes, correct")
                .callbackData("confirm")
                .build();

        InlineKeyboardButton noBtn = InlineKeyboardButton.builder()
                .text("RU".equals(lang) ? "Нет, отмена" : "No, cancel")
                .callbackData("cancel")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(yesBtn, noBtn))
                .build();

        return SendMessage.builder()
                .chatId(chatId)
                .text(buildConfirmationText(lang, n))
                .replyMarkup(keyboard)
                .build();
    }

    private static String buildConfirmationText(String lang, NutritionResponse n) {
        double grams    = n.getGrams()    != null ? n.getGrams()    : 0;
        double calories = n.getCalories() != null ? n.getCalories() : 0;
        double protein  = n.getProtein()  != null ? n.getProtein()  : 0;
        double carbs    = n.getCarbs()    != null ? n.getCarbs()    : 0;
        double fat      = n.getFat()      != null ? n.getFat()      : 0;
        double fiber    = n.getFiber()    != null ? n.getFiber()    : 0;
        String name     = n.getFoodName() != null ? n.getFoodName() : "Unknown";

        if ("RU".equals(lang)) {
            return String.format(
                    "Я нашёл: %s (%.0fг)%nКалории: %.0f ккал | Белки: %.0fг | Углеводы: %.0fг | Жиры: %.0fг | Клетчатка: %.0fг",
                    name, grams, calories, protein, carbs, fat, fiber);
        } else {
            return String.format(
                    "Found: %s (%.0fg)%nCalories: %.0f kcal | Protein: %.0fg | Carbs: %.0fg | Fat: %.0fg | Fiber: %.0fg",
                    name, grams, calories, protein, carbs, fat, fiber);
        }
    }

    // ── File download helper ───────────────────────────────────────────────────

    private byte[] downloadTelegramFile(String fileId) throws TelegramApiException, IOException {
        GetFile getFileMethod = GetFile.builder().fileId(fileId).build();
        File telegramFile = telegramClient.execute(getFileMethod);
        try (InputStream stream = telegramClient.downloadFileAsStream(telegramFile)) {
            return stream.readAllBytes();
        }
    }
}
