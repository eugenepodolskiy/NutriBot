package com.nutribot.nutribot.models;

import com.nutribot.nutribot.enums.LogSource;
import com.nutribot.nutribot.enums.MealType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "food_log")
@Getter
@Setter
public class FoodLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime loggedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    private MealType mealType;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Double grams;
    private Double calories;
    private Double protein;
    private Double carbs;
    private Double fat;

    @Enumerated(EnumType.STRING)
    private LogSource source;
}