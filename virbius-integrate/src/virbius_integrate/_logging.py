from __future__ import annotations

import logging

LOGGER_NAME = "virbius_integrate"


def get_logger() -> logging.Logger:
    """Library logger, deliberately without a NullHandler.

    If the host app has not configured logging, Python's ``logging.lastResort``
    handler surfaces WARNING+ to stderr — so a failed startup handshake or a
    failed first flush is never silently swallowed. INFO/DEBUG stay hidden until
    the app configures logging (or raises the level), keeping the happy path
    quiet. We never set a level and never add our own StreamHandler, so an app
    that configures logging keeps full control.
    """
    return logging.getLogger(LOGGER_NAME)
