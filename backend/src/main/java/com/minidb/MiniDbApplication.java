package com.minidb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MiniDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniDbApplication.class, args);
    }
}
