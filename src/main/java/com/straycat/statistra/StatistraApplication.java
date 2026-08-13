package com.straycat.statistra;

import com.straycat.statistra.config.StatistraProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StatistraProperties.class)
public class StatistraApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatistraApplication.class, args);
    }

}
