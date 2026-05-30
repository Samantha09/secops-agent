package com.secops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SecOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecOpsApplication.class, args);
    }
}
