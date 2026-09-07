package io.virbius.engine.config;

import io.virbius.engine.eval.OcrCircuitBreaker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileConfiguration {

    @Bean
    public OcrCircuitBreaker ocrCircuitBreaker(FileProperties props) {
        return new OcrCircuitBreaker(props.ocrBreakerFailureRate(), props.ocrBreakerWaitMs());
    }

    /**
     * Dedicated bounded pool for attachment checks so OCR latency cannot eat
     * into the servlet worker threads.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService attachmentExecutor(FileProperties props) {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(props.attachmentWorkers(), r -> {
            Thread t = new Thread(r, "file-guard-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
