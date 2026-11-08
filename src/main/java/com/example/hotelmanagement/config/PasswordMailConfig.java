package com.example.hotelmanagement.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PasswordMailConfig {
    @Bean("passwordResetExecutor")
    public ThreadPoolTaskExecutor executor() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(50);
        pool.setThreadNamePrefix("password-mail-");
        pool.setWaitForTasksToCompleteOnShutdown(false);
        return pool;
    }
}
