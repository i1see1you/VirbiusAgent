package io.virbius.control;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduling
public class VirbiusControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirbiusControlApplication.class, args);
    }
}
