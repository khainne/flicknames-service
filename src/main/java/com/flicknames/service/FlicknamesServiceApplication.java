package com.flicknames.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlicknamesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlicknamesServiceApplication.class, args);
    }
}
