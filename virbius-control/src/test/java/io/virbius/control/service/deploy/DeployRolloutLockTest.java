package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.control.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class DeployRolloutLockTest {

    @Test
    void acquireLockFailsClosedWhenRedisThrows() {
        DeployRolloutPointerStore pointerStore = mock(DeployRolloutPointerStore.class);
        when(pointerStore.redisAvailable()).thenReturn(true);
        when(pointerStore.getPointer(anyString())).thenReturn(java.util.Optional.empty());
        when(pointerStore.requireJedis()).thenThrow(new RuntimeException("redis down"));

        DeployRolloutService service = new DeployRolloutService(
                null, null, pointerStore, null, null, null, null, null, null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.acquireLock("t"));
        assertEquals(503, ex.getCode());
    }
}
