package com.travel;

import com.travel.config.DotenvEnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravelApplication {

    public static void main(String[] args) {
        DotenvEnvironmentPostProcessor.applyDotenvToSystemProperties();
        SpringApplication.run(TravelApplication.class, args);
    }
}
