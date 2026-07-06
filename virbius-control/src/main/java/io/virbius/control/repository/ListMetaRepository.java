package io.virbius.control.repository;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ListMetaRepository {

    List<AccessListMeta> listMeta(String tenantId);

    Optional<AccessListMeta> getMeta(String tenantId, String listName);

    void upsertMeta(AccessListMeta meta);

    void deleteMeta(String tenantId, String listName);

    List<AccessListEntry> listEntries(String tenantId, String listName);

    void replaceEntries(String tenantId, String listName, List<AccessListEntry> entries);

    boolean addEntry(String tenantId, String listName, String value, String remark, Instant expiresAt);

    boolean removeEntry(String tenantId, String listName, String value);
}
