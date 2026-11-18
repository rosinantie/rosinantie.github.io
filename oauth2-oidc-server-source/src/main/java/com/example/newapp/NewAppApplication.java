package com.example.newapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NewAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewAppApplication.class, args);
    }

}
