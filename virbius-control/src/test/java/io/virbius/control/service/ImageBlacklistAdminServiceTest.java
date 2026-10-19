package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.repository.ListMetaRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

/** T5: imgbl rebuild — single-flight lock + atomic RENAME/PERSIST swap. */
class ImageBlacklistAdminServiceTest {

    private final ListMetaRepository listRepo = mock(ListMetaRepository.class);
    private final ControlJedisPools pools = mock(ControlJedisPools.class);
    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    private static final String SHA = "a".repeat(64);
    private static final String PHASH = "0123456789abcdef";

    private void healthyRedis() {
        when(pools.pool()).thenReturn(Optional.of(pool));
        when(pool.getResource()).thenReturn(jedis);
    }

    @Test
    void rebuildStagesLocksAndAtomicallySwaps() {
        healthyRedis();
        when(jedis.set(eq("virbius:imgbl:rebuild-lock"), anyString(), any(SetParams.class))).thenReturn("OK");
        when(listRepo.listAllMeta()).thenReturn(List.of(new AccessListMeta("t", "imglist", "image", null)));
        when(listRepo.listEntries("t", "imglist"))
                .thenReturn(List.of(new AccessListEntry(SHA + ":" + PHASH, null, null, null)));
        when(jedis.scan(anyString(), any())).thenReturn(new ScanResult<>("0", List.of()));
        org.mockito.Mockito.lenient().when(jedis.eval(anyString(),
                ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList())).thenReturn(1L);

        Map<String, Object> r = new ImageBlacklistAdminService(listRepo, pools).rebuildImageListKeys();

        assertEquals(Boolean.TRUE, r.get("pushed"));
        verify(jedis).sadd(anyString(), any(String[].class));
        verify(jedis).hset(anyString(), anyMap());
        // The swap must be one atomic round-trip (RENAME + PERSIST together) —
        // once per staging key (exact + phash here).
        verify(jedis, org.mockito.Mockito.atLeastOnce())
                .eval(argThat((String s) -> s.contains("RENAME") && s.contains("PERSIST")),
                        ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList());
    }

    @Test
    void skipsWhenAnotherRebuildHoldsTheLock() {
        healthyRedis();
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);
        when(listRepo.listAllMeta()).thenReturn(List.of());

        Map<String, Object> r = new ImageBlacklistAdminService(listRepo, pools).rebuildImageListKeys();

        assertEquals("locked", r.get("skipped"));
        assertEquals("locked", r.get("status"));
    }

    /** V2: a swap that throws must surface as pushed=false (not a silent success). */
    @Test
    void partialSwapFailureIsReported() {
        healthyRedis();
        when(jedis.set(eq("virbius:imgbl:rebuild-lock"), anyString(), any(SetParams.class))).thenReturn("OK");
        when(listRepo.listAllMeta()).thenReturn(List.of(new AccessListMeta("t", "imglist", "image", null)));
        when(listRepo.listEntries("t", "imglist"))
                .thenReturn(List.of(new AccessListEntry(SHA + ":" + PHASH, null, null, null)));
        when(jedis.scan(anyString(), any())).thenReturn(new ScanResult<>("0", List.of()));
        org.mockito.Mockito.lenient().when(jedis.eval(anyString(),
                ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList()))
                .thenThrow(new RuntimeException("swap boom"));

        Map<String, Object> r = new ImageBlacklistAdminService(listRepo, pools).rebuildImageListKeys();

        assertEquals(Boolean.FALSE, r.get("pushed"));
        assertEquals("partial", r.get("status"));
        assertTrue(((Number) r.get("swap_failures")).intValue() >= 1);
    }

    /** V3: stale cleanup deletes only keys outside the desired set (never the lock). */
    @Test
    void staleCleanupDeletesOnlyUndesiredNonLockKeys() {
        healthyRedis();
        when(jedis.set(eq("virbius:imgbl:rebuild-lock"), anyString(), any(SetParams.class))).thenReturn("OK");
        when(listRepo.listAllMeta()).thenReturn(List.of(new AccessListMeta("t", "imglist", "image", null)));
        when(listRepo.listEntries("t", "imglist"))
                .thenReturn(List.of(new AccessListEntry(SHA + ":" + PHASH, null, null, null)));
        when(jedis.scan(anyString(), any())).thenReturn(new ScanResult<>("0", List.of(
                "virbius:imgbl:t:imglist:exact",   // desired → keep
                "virbius:imgbl:legacy",            // stale → delete
                "virbius:imgbl:rebuild-lock")));   // the lock → keep
        org.mockito.Mockito.lenient().when(jedis.eval(anyString(),
                ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList())).thenReturn(1L);

        new ImageBlacklistAdminService(listRepo, pools).rebuildImageListKeys();

        org.mockito.ArgumentCaptor<String[]> cap =
                org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(jedis).del(cap.capture());
        assertEquals(1, cap.getValue().length);
        assertEquals("virbius:imgbl:legacy", cap.getValue()[0]);
    }
}
