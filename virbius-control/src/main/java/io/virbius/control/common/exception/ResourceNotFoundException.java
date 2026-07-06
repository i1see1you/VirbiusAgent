package io.virbius.control.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, String id) {
        super(404, resource + " not found: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}