package com.nutribot.nutribot.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "supplement_log")
@Getter
@Setter
public class SupplementLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "supplement_id", nullable = false)
    private Supplement supplement;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime takenAt = LocalDateTime.now();

    @Column(nullable = false)
    private Boolean taken = true;
}
