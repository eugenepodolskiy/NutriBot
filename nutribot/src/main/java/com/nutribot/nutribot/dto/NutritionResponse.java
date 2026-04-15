package com.nutribot.nutribot.dto;

import lombok.Data;

@Data
public class NutritionResponse {
    private String foodName;
    private Double grams;
    private Double calories;
    private Double protein;
    private Double carbs;
    private Double fat;
}