package io.virbius.control.job;

import io.virbius.control.service.ImageBlacklistAdminService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis imgbl SET/HASH members have no per-member TTL, so expired image
 * samples would keep matching until the next list change rebuilds the keys.
 * This job rebuilds them periodically; the default aligns with the engine's
 * 60s snapshot refresh.
 */
@Component
public class ImageListRebuildJob {

    private final ImageBlacklistAdminService imageListService;

    public ImageListRebuildJob(ImageBlacklistAdminService imageListService) {
        this.imageListService = imageListService;
    }

    @Scheduled(fixedDelayString = "${virbius.lists.image.rebuild-ms:60000}")
    public void rebuild() {
        imageListService.rebuildImageListKeys();
    }
}
