package io.virbius.engine.grpc;

import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.v1.EvaluateRequest;
import io.virbius.engine.v1.EvaluateResponse;
import io.virbius.engine.v1.EvaluateServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class EvaluateGrpcService extends EvaluateServiceGrpc.EvaluateServiceImplBase {

    private final EvaluateOrchestrator orchestrator;
    private final ProtoMapper protoMapper;

    public EvaluateGrpcService(EvaluateOrchestrator orchestrator, ProtoMapper protoMapper) {
        this.orchestrator = orchestrator;
        this.protoMapper = protoMapper;
    }

    @Override
    public void evaluate(EvaluateRequest request, StreamObserver<EvaluateResponse> responseObserver) {
        var dto = protoMapper.toDto(request);
        var result = orchestrator.evaluate(dto);
        responseObserver.onNext(protoMapper.toProto(result));
        responseObserver.onCompleted();
    }
}
