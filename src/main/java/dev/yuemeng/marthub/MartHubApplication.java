package dev.yuemeng.marthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MartHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(MartHubApplication.class, args);
    }
}
