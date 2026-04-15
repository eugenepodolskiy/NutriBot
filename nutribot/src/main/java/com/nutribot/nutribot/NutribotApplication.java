package com.nutribot.nutribot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NutribotApplication {

	public static void main(String[] args) {
		SpringApplication.run(NutribotApplication.class, args);
	}

}
