package com.example.hotelmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HotelmanagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelmanagementApplication.class, args);
    }
}
