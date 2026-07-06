package io.virbius.engine.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final Server server;

    public GrpcServerLifecycle(
            EvaluateGrpcService evaluateGrpcService,
            @Value("${virbius.grpc.port:50051}") int port) {
        this.server = ServerBuilder.forPort(port)
                .addService(evaluateGrpcService)
                .build();
        log.info("gRPC server starting on port {}", port);
        try {
            this.server.start();
            log.info("gRPC server started on port {}", port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("gRPC server shutting down");
        server.shutdown();
    }
}
