package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeployRolloutLayerLabelTest {

    @Test
    void everyLabelFitsDeployEventColumn() {
        boolean[] bits = {false, true};
        for (boolean falco : bits) {
            for (boolean engine : bits) {
                for (boolean gateway : bits) {
                    for (boolean edge : bits) {
                        String label = DeployRolloutService.layerLabel(falco, engine, gateway, edge);
                        assertTrue(label.length() <= DeployRolloutService.LAYER_LABEL_MAX,
                                label + " exceeds tb_deploy_event.layer");
                    }
                }
            }
        }
    }
}
