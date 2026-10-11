from __future__ import annotations

import logging

from virbius_integrate._logging import LOGGER_NAME, get_logger


def test_logger_has_no_nullhandler_and_propagates():
    logger = get_logger()
    assert logger.name == LOGGER_NAME
    # No NullHandler: an unconfigured app must still see WARNING+ via logging.lastResort.
    assert logger.handlers == []
    assert logger.propagate is True


def test_handshake_failure_warning_is_emitted(caplog):
    from virbius_integrate import IntegrateConfig, run_handshake

    config = IntegrateConfig(app_id="a", tenant_id="t", endpoint="http://control:8080")
    with caplog.at_level(logging.WARNING, logger=LOGGER_NAME):
        run_handshake(config, transport=lambda m, u, p, h: (401, {}))
    assert any("handshake FAILED" in r.getMessage() for r in caplog.records)
