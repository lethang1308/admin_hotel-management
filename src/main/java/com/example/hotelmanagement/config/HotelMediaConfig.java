package com.example.hotelmanagement.config;

import com.example.hotelmanagement.modules.hotel.service.HotelMediaStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@RequiredArgsConstructor
public class HotelMediaConfig implements WebMvcConfigurer {
    private final HotelMediaStorage storage;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/hotel/**")
                .addResourceLocations(storage.root().toUri().toString() + "/");
    }
}
