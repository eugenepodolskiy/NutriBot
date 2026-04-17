package com.nutribot.nutribot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nutribot.nutribot.models.Supplement;
import com.nutribot.nutribot.models.SupplementLog;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.SupplementLogRepository;
import com.nutribot.nutribot.repositories.SupplementRepository;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplementService {

    private final SupplementRepository    supplementRepository;
    private final SupplementLogRepository supplementLogRepository;
    private final UserRepository          userRepository;
    private final GeminiService           geminiService;
    private final ObjectMapper            objectMapper = new ObjectMapper();

    // ── Main dispatch ──────────────────────────────────────────────────────────

    @Transactional
    public String handleMessage(Long telegramId, String text, String lang) {
        User user = getUser(telegramId);
        String action = geminiService.classifySupplementAction(text);
        return switch (action) {
            case "ADD"        -> addSupplementFromText(user, text, lang);
            case "DELETE"     -> deleteSupplementFromText(user, text, lang);
            case "MARK_TAKEN" -> markTakenFromText(user, text, lang);
            default           -> listSupplements(user, lang);
        };
    }

    // ── List ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String listSupplements(Long telegramId, String lang) {
        User user = getUser(telegramId);
        return listSupplements(user, lang);
    }

    private String listSupplements(User user, String lang) {
        List<Supplement> supplements = supplementRepository.findByUserIdAndActiveTrue(user.getId());
        if (supplements.isEmpty()) {
            return isRU(lang) ? "У вас пока нет добавленных добавок." : "You have no supplements added yet.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(isRU(lang) ? "Ваши добавки:\n" : "Your supplements:\n");
        for (int i = 0; i < supplements.size(); i++) {
            Supplement s = supplements.get(i);
            String doseStr = (s.getDose() != null && s.getUnit() != null)
                    ? String.format(" — %.0f %s", s.getDose(), s.getUnit())
                    : (s.getDose() != null ? String.format(" — %.0f", s.getDose()) : "");
            String times = (s.getReminderTimes() != null && !s.getReminderTimes().isBlank())
                    ? (isRU(lang) ? " (напоминание: " : " (reminder: ") + s.getReminderTimes() + ")"
                    : "";
            sb.append(String.format("%d. %s%s%s\n", i + 1, s.getName(), doseStr, times));
        }
        return sb.toString().trim();
    }

    // ── Add ────────────────────────────────────────────────────────────────────

    private String addSupplementFromText(User user, String text, String lang) {
        String json = geminiService.extractSupplementDetails(text);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String name    = (String) parsed.get("name");
            Object doseRaw = parsed.get("dose");
            Double dose    = doseRaw instanceof Number ? ((Number) doseRaw).doubleValue() : null;
            String unit    = (String) parsed.getOrDefault("unit", "");
            String times   = (String) parsed.getOrDefault("reminderTimes", "");

            if (name == null || name.isBlank()) {
                return isRU(lang)
                        ? "Не удалось распознать название добавки. Попробуйте: «Добавь витамин D 1000 IU в 8:00»"
                        : "Could not parse supplement name. Try: \"Add vitamin D 1000 IU at 8:00\"";
            }

            Supplement s = new Supplement();
            s.setUser(user);
            s.setName(name.trim());
            s.setDose(dose);
            s.setUnit(unit != null && !unit.isBlank() ? unit.trim() : null);
            s.setReminderTimes(times != null && !times.isBlank() ? times.trim() : null);
            s.setActive(true);
            supplementRepository.save(s);

            String doseStr  = (dose != null) ? String.format(" %.0f%s", dose, unit != null && !unit.isBlank() ? " " + unit : "") : "";
            String timesStr = (times != null && !times.isBlank())
                    ? (isRU(lang) ? ", напоминание в " : ", reminder at ") + times
                    : "";

            return isRU(lang)
                    ? String.format("Добавка «%s»%s добавлена%s.", s.getName(), doseStr, timesStr)
                    : String.format("Supplement \"%s\"%s added%s.", s.getName(), doseStr, timesStr);

        } catch (Exception e) {
            log.error("[SupplementService] addSupplementFromText error: {}", e.getMessage(), e);
            return isRU(lang)
                    ? "Не удалось добавить добавку. Попробуйте ещё раз."
                    : "Could not add supplement. Please try again.";
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    private String deleteSupplementFromText(User user, String text, String lang) {
        String json = geminiService.extractSupplementDetails(text);
        String name = null;
        try {
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            name = (String) parsed.get("name");
        } catch (Exception ignored) {}

        if (name == null || name.isBlank()) {
            return isRU(lang)
                    ? "Укажите название добавки, которую нужно удалить."
                    : "Please specify the supplement name to delete.";
        }
        final String nameFinal = name.trim();
        return supplementRepository
                .findFirstByUserIdAndNameIgnoreCaseAndActiveTrue(user.getId(), nameFinal)
                .map(s -> {
                    s.setActive(false);
                    supplementRepository.save(s);
                    return isRU(lang)
                            ? "Добавка «" + s.getName() + "» удалена."
                            : "Supplement \"" + s.getName() + "\" removed.";
                })
                .orElseGet(() -> isRU(lang)
                        ? "Добавка «" + nameFinal + "» не найдена."
                        : "Supplement \"" + nameFinal + "\" not found.");
    }

    // ── Mark taken (natural text path) ─────────────────────────────────────────

    private String markTakenFromText(User user, String text, String lang) {
        String json = geminiService.extractSupplementDetails(text);
        String name = null;
        try {
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            name = (String) parsed.get("name");
        } catch (Exception ignored) {}

        if (name == null || name.isBlank()) {
            // Mark all active supplements as taken
            List<Supplement> all = supplementRepository.findByUserIdAndActiveTrue(user.getId());
            LocalDateTime now = LocalDateTime.now();
            for (Supplement s : all) {
                logSupplementTaken(s, user, now);
            }
            return isRU(lang) ? "Все добавки отмечены как принятые!" : "All supplements marked as taken!";
        }

        String n = name.trim();
        Optional<Supplement> opt = supplementRepository.findFirstByUserIdAndNameIgnoreCaseAndActiveTrue(user.getId(), n);
        if (opt.isEmpty()) {
            return isRU(lang)
                    ? "Добавка «" + n + "» не найдена."
                    : "Supplement \"" + n + "\" not found.";
        }
        logSupplementTaken(opt.get(), user, LocalDateTime.now());
        return isRU(lang)
                ? "Отлично! " + opt.get().getName() + " отмечен(а) как принятый(ая)!"
                : "Great! " + opt.get().getName() + " marked as taken!";
    }

    // ── Log taken (called from callback button handler in NutriBot) ────────────

    @Transactional
    public String logTaken(Long supplementId, Long telegramId) {
        User user = getUser(telegramId);
        String lang = user.getLanguage();
        Supplement s = supplementRepository.findById(supplementId)
                .orElse(null);
        if (s == null || !s.getUser().getId().equals(user.getId())) {
            return isRU(lang) ? "Добавка не найдена." : "Supplement not found.";
        }
        logSupplementTaken(s, user, LocalDateTime.now());
        return isRU(lang)
                ? "Отлично! " + s.getName() + " отмечен(а) как принятый(ая)!"
                : "Great! " + s.getName() + " marked as taken!";
    }

    private void logSupplementTaken(Supplement supplement, User user, LocalDateTime at) {
        SupplementLog entry = new SupplementLog();
        entry.setSupplement(supplement);
        entry.setUser(user);
        entry.setTakenAt(at);
        entry.setTaken(true);
        supplementLogRepository.save(entry);
    }

    // ── Today's supplement status (for daily summaries and /goals) ─────────────

    @Transactional(readOnly = true)
    public String getTodaySupplementStatus(User user, String lang) {
        List<Supplement> supplements = supplementRepository.findByUserIdAndActiveTrue(user.getId());
        if (supplements.isEmpty()) return "";

        ZoneId zone = getZone(user.getTimezone());
        LocalDateTime startUtc = LocalDate.now(zone).atStartOfDay(zone)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = startUtc.plusDays(1);

        StringBuilder sb = new StringBuilder();
        sb.append(isRU(lang) ? "\nДобавки сегодня:\n" : "\nSupplements today:\n");
        for (Supplement s : supplements) {
            boolean taken = supplementLogRepository.existsBySupplementIdAndUserIdAndTakenAtBetween(
                    s.getId(), user.getId(), startUtc, endUtc);
            sb.append(taken ? "✅ " : "⬜ ").append(s.getName()).append("\n");
        }
        return sb.toString().trim();
    }

    // ── Scheduled reminder helper (called from ReminderService) ───────────────

    public List<Supplement> findAllActiveSupplements() {
        return supplementRepository.findAllByActiveTrue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User getUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("User not found."));
    }

    public static ZoneId getZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneOffset.UTC;
        try { return ZoneId.of(timezone); }
        catch (Exception e) { return ZoneOffset.UTC; }
    }

    private static boolean isRU(String lang) {
        return "RU".equals(lang);
    }
}
