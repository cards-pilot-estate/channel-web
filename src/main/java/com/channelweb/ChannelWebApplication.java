package com.channelweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** The web self-service channel: a customer BFF serving the SPA (ADR-0020). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChannelWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelWebApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
