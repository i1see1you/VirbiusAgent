package io.virbius.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VirbiusEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirbiusEngineApplication.class, args);
    }
}
