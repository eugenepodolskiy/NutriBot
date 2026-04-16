package com.nutribot.nutribot.services;

import com.nutribot.nutribot.enums.ActivityLevel;
import com.nutribot.nutribot.enums.GoalType;
import com.nutribot.nutribot.enums.Sex;
import com.nutribot.nutribot.models.User;
import com.nutribot.nutribot.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private enum Step {
        NAME, AGE, WEIGHT, HEIGHT, SEX, ACTIVITY, GOAL
    }

    private enum Language {
        EN, RU
    }

    private static class State {
        Step     step         = Step.NAME;
        Language language     = Language.EN;
        String   name;
        int      age;
        double   weightKg;
        double   heightCm;
        Sex      sex;
        ActivityLevel activityLevel;
    }

    private final Map<Long, State> sessions = new ConcurrentHashMap<>();
    private final UserRepository  userRepository;
    private final GeminiService   geminiService;

    public boolean needsOnboarding(Long telegramId) {
        return userRepository.findByTelegramId(telegramId).isEmpty();
    }

    public boolean isOnboarding(Long telegramId) {
        return sessions.containsKey(telegramId);
    }

    public String start(Long telegramId, String telegramLanguageCode) {
        State state = new State();
        if (telegramLanguageCode != null && telegramLanguageCode.toLowerCase().startsWith("ru")) {
            state.language = Language.RU;
        }
        sessions.put(telegramId, state);
        return msg(state.language,
                "Welcome to NutriBot! Let's set up your profile.\n\nWhat's your name?",
                "Добро пожаловать в NutriBot! Давайте настроим ваш профиль.\n\nКак вас зовут?");
    }

    @Transactional
    public String handle(Long telegramId, String text) {
        State state = sessions.get(telegramId);
        if (state == null) return null;

        return switch (state.step) {
            case NAME -> {
                state.name = text.trim();
                state.language = hasCyrillic(state.name) ? Language.RU : Language.EN;
                state.step = Step.AGE;
                yield msg(state.language,
                        "Nice to meet you, " + state.name + "! How old are you?",
                        "Приятно познакомиться, " + state.name + "! Сколько вам лет?");
            }
            case AGE -> {
                int age;
                try {
                    age = Integer.parseInt(text.trim());
                } catch (NumberFormatException e) {
                    yield msg(state.language,
                            "Please enter your age as a number, e.g. 25.",
                            "Введите возраст числом, например 25.");
                }
                if (age < 10 || age > 120) yield msg(state.language,
                        "Please enter a valid age between 10 and 120.",
                        "Введите корректный возраст от 10 до 120.");
                state.age = age;
                state.step = Step.WEIGHT;
                yield msg(state.language,
                        "What's your current weight in kg? (e.g. 70.5)",
                        "Какой у вас текущий вес в кг? (например, 70.5)");
            }
            case WEIGHT -> {
                double weight;
                try {
                    weight = Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    yield msg(state.language,
                            "Please enter your weight as a number, e.g. 70.5",
                            "Введите вес числом, например 70.5");
                }
                if (weight < 20 || weight > 500) yield msg(state.language,
                        "Please enter a valid weight between 20 and 500 kg.",
                        "Введите корректный вес от 20 до 500 кг.");
                state.weightKg = weight;
                state.step = Step.HEIGHT;
                yield msg(state.language,
                        "What's your height in cm? (e.g. 175)",
                        "Какой у вас рост в см? (например, 175)");
            }
            case HEIGHT -> {
                double height;
                try {
                    height = Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    yield msg(state.language,
                            "Please enter your height as a number, e.g. 175",
                            "Введите рост числом, например 175");
                }
                if (height < 50 || height > 300) yield msg(state.language,
                        "Please enter a valid height between 50 and 300 cm.",
                        "Введите корректный рост от 50 до 300 см.");
                state.heightCm = height;
                state.step = Step.SEX;
                yield msg(state.language,
                        "What is your biological sex?\nPlease reply: male or female",
                        "Укажите ваш биологический пол.\nОтветьте: мужской или женский");
            }
            case SEX -> {
                String input = text.trim().toLowerCase();
                if (input.contains("male") || input.contains("man") ||
                    input.contains("мужской") || input.contains("мужчина") || input.equals("м")) {
                    state.sex = Sex.MALE;
                } else if (input.contains("female") || input.contains("woman") ||
                           input.contains("женский") || input.contains("женщина") || input.equals("ж")) {
                    state.sex = Sex.FEMALE;
                } else {
                    yield msg(state.language,
                            "Please reply with male or female.",
                            "Пожалуйста, ответьте: мужской или женский.");
                }
                state.step = Step.ACTIVITY;
                yield msg(state.language,
                        """
                        Describe your typical daily activity level.
                        For example:
                        \u2022 "I sit at a desk all day and don't exercise"
                        \u2022 "I walk 30 min daily and go to the gym 3x/week"
                        \u2022 "I have a physically demanding job\"""",
                        """
                        Опишите ваш обычный уровень физической активности.
                        Например:
                        \u2022 «Я весь день сижу за столом и не занимаюсь спортом»
                        \u2022 «Хожу пешком 30 минут в день и хожу в зал 3 раза в неделю»
                        \u2022 «У меня физически тяжёлая работа»""");
            }
            case ACTIVITY -> {
                state.activityLevel = geminiService.classifyActivity(text.trim());
                state.step = Step.GOAL;
                yield msg(state.language,
                        """
                        Got it! Describe your goal — for example "I want to lose weight" or "I want to build muscle".

                        Or type directly: LOSE / MAINTAIN / GAIN""",
                        """
                        Отлично! Опишите вашу цель — например, «хочу похудеть» или «хочу набрать мышечную массу».

                        Или напишите напрямую: LOSE / MAINTAIN / GAIN""");
            }
            case GOAL -> {
                String input = text.trim();
                GoalType goalType;
                try {
                    goalType = GoalType.valueOf(input.toUpperCase());
                } catch (IllegalArgumentException e) {
                    goalType = geminiService.classifyGoal(input);
                }
                User user = buildUser(telegramId, state, goalType);
                userRepository.save(user);
                Language lang = state.language;
                String name = state.name;
                sessions.remove(telegramId);
                yield String.format(
                        msg(lang,
                                """
                                Profile saved, %s!

                                Your daily nutrition goals:
                                Calories: %.0f kcal
                                Protein:  %.0f g
                                Carbs:    %.0f g
                                Fat:      %.0f g
                                Fiber:    %.0f g
                                Water:    %.0f ml

                                Now just tell me what you ate and I'll log it for you!""",
                                """
                                Профиль сохранён, %s!

                                Ваши ежедневные цели по питанию:
                                Калории:   %.0f ккал
                                Белки:     %.0f г
                                Углеводы:  %.0f г
                                Жиры:      %.0f г
                                Клетчатка: %.0f г
                                Вода:      %.0f мл

                                Теперь просто напишите, что вы съели, и я всё запишу!"""),
                        name,
                        user.getCalorieGoal(),
                        user.getProteinGoal(),
                        user.getCarbGoal(),
                        user.getFatGoal(),
                        user.getFiberGoal(),
                        user.getWaterGoal());
            }
        };
    }

    private static boolean hasCyrillic(String text) {
        return text.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
    }

    private static String msg(Language lang, String en, String ru) {
        return lang == Language.RU ? ru : en;
    }

    private User buildUser(Long telegramId, State state, GoalType goalType) {
        // Sex-specific Mifflin-St Jeor BMR
        double bmr;
        if (state.sex == Sex.MALE) {
            bmr = 10 * state.weightKg + 6.25 * state.heightCm - 5 * state.age + 5;
        } else if (state.sex == Sex.FEMALE) {
            bmr = 10 * state.weightKg + 6.25 * state.heightCm - 5 * state.age - 161;
        } else {
            // Unisex fallback (midpoint of male and female constants)
            bmr = 10 * state.weightKg + 6.25 * state.heightCm - 5 * state.age - 78;
        }

        double activityMultiplier = switch (state.activityLevel) {
            case SEDENTARY -> 1.2;
            case LIGHT     -> 1.375;
            case MODERATE  -> 1.55;
            case ACTIVE    -> 1.725;
        };

        double tdee = bmr * activityMultiplier;

        double calorieGoal = switch (goalType) {
            case LOSE     -> tdee * 0.85;
            case MAINTAIN -> tdee;
            case GAIN     -> tdee * 1.10;
        };

        // Weight-based protein goal
        double proteinGoal = switch (goalType) {
            case LOSE     -> state.weightKg * 2.2;
            case MAINTAIN -> state.weightKg * 1.8;
            case GAIN     -> state.weightKg * 2.5;
        };

        // Remaining calories after protein → split 30% fat / rest carbs
        double fatGoal  = (calorieGoal * 0.30) / 9;
        double carbGoal = Math.max((calorieGoal - proteinGoal * 4 - fatGoal * 9) / 4, 0);

        // Fiber goal (sex-based)
        double fiberGoal = (state.sex == Sex.MALE) ? 38.0 : 25.0;

        // Water goal (ml/day)
        double waterGoal = state.weightKg * 35;

        User user = new User();
        user.setTelegramId(telegramId);
        user.setName(state.name);
        user.setAge(state.age);
        user.setWeightKg(state.weightKg);
        user.setHeightCm(state.heightCm);
        user.setSex(state.sex);
        user.setActivityLevel(state.activityLevel);
        user.setGoalType(goalType);
        user.setCalorieGoal(calorieGoal);
        user.setProteinGoal(proteinGoal);
        user.setCarbGoal(carbGoal);
        user.setFatGoal(fatGoal);
        user.setFiberGoal(fiberGoal);
        user.setWaterGoal(waterGoal);
        user.setLanguage(state.language == Language.RU ? "RU" : "EN");
        return user;
    }
}
