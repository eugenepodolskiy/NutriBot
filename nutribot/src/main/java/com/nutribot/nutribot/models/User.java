package com.nutribot.nutribot.models;

import com.nutribot.nutribot.enums.ActivityLevel;
import com.nutribot.nutribot.enums.GoalType;
import com.nutribot.nutribot.enums.Sex;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long telegramId;

    @Column(nullable = false)
    private String name;

    private Integer age;
    private Double weightKg;
    private Double heightCm;

    @Enumerated(EnumType.STRING)
    private Sex sex;

    @Enumerated(EnumType.STRING)
    private ActivityLevel activityLevel;

    @Enumerated(EnumType.STRING)
    private GoalType goalType;

    private Double calorieGoal;
    private Double proteinGoal;
    private Double fatGoal;
    private Double carbGoal;
    private Double fiberGoal;
    private Double waterGoal;

    @Column(nullable = false)
    private String language = "EN";

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
