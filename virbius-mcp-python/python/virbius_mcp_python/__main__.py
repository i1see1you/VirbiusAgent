"""``python -m virbius_mcp_python <command>`` — currently: ``doctor``.

    python -m virbius_mcp_python doctor [--json] [--engine]
"""

from __future__ import annotations

import sys


def main(argv=None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "doctor":
        from .doctor import main as doctor_main

        return doctor_main(args[1:])
    sys.stderr.write("usage: python -m virbius_mcp_python doctor [--json] [--engine]\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
