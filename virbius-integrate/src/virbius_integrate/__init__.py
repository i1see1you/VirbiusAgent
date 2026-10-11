from ._config import IntegrateConfig, package_version
from .callback import VirbiusAuditCallback, bounded_repr, current_session, session
from .classify import RULE_TABLE_VERSION, classify_tool, classify_tools
from .classify import RULE_TABLE_VERSION as rule_table_version
from .handshake import HandshakeResult, VirbiusIntegrateError, run_handshake
from .report import report_tools

__version__ = package_version()

__all__ = [
    "VirbiusAuditCallback",
    "IntegrateConfig",
    "report_tools",
    "classify_tool",
    "classify_tools",
    "session",
    "current_session",
    "bounded_repr",
    "run_handshake",
    "HandshakeResult",
    "VirbiusIntegrateError",
    "rule_table_version",
    "RULE_TABLE_VERSION",
    "doctor",
    "__version__",
]


def doctor(config, callback=None):
    from .doctor import doctor as _doctor

    return _doctor(config, callback)
