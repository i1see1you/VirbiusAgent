package io.virbius.control.domain.dto.request;

import java.util.List;

public record AccessListEntriesRequest(
        String value, List<String> values, List<AccessListEntryInput> entries) {}
