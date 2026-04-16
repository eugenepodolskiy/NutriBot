package com.nutribot.nutribot.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "supplements")
@Getter
@Setter
public class Supplement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    private Double dose;

    private String unit;

    /** Comma-separated HH:mm reminder times, e.g. "08:00,20:00" */
    @Column(name = "reminder_times")
    private String reminderTimes;

    @Column(nullable = false)
    private Boolean active = true;
}
