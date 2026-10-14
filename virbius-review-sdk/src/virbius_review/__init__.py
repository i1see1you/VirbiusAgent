"""virbius-review-sdk — official VirbiusAgent review SDK.

Input review before the LLM, output review after it, and streaming
sliding-window review — all over the engine's ``POST /v1/evaluate`` API.
"""

from .client import AsyncReviewClient, ReviewAsyncUnavailable, ReviewClient
from .config import ReviewConfig
from .core import ReviewError, apply_disposal
from .models import Blocked, Delta, Done, InputVerdict, OutputVerdict
from .window import StreamReviewWindow

__version__ = "0.1.0"

__all__ = [
    "AsyncReviewClient",
    "Blocked",
    "Delta",
    "Done",
    "InputVerdict",
    "OutputVerdict",
    "ReviewAsyncUnavailable",
    "ReviewClient",
    "ReviewConfig",
    "ReviewError",
    "StreamReviewWindow",
    "apply_disposal",
]
