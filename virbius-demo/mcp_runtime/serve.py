# -*- coding: utf-8 -*-
"""起 7 个 FastMCP SSE 进程内线程。python -m mcp_runtime.serve"""
import threading
import time

from mcp_runtime.labs import LABS
from mcp_runtime.sse import run_sse


def _load_mcps():
    from chain_agent.mcp import mcp as chain_mcp
    from dvla_agent.mcp import mcp as bank_mcp
    from egress_agent.mcp import mcp as egress_mcp
    from memory_agent.mcp import mcp as memory_mcp
    from ops_agent.mcp import mcp as ops_mcp
    from owasp_agent.llm06.mcp import mcp as llm06_mcp
    from owasp_agent.llm10.mcp import mcp as llm10_mcp
    return {
        "bank": bank_mcp,
        "llm10": llm10_mcp,
        "llm06": llm06_mcp,
        "memory": memory_mcp,
        "ops": ops_mcp,
        "egress": egress_mcp,
        "chain": chain_mcp,
    }


def main() -> None:
    mcps = _load_mcps()
    threads = []
    for lab in LABS:
        mcp = mcps[lab.id]
        t = threading.Thread(
            target=run_sse,
            args=(mcp, lab.port, lab.id),
            name="sse-" + lab.id,
            daemon=True,
        )
        t.start()
        threads.append(t)
    # 给 bind 一点时间；失败的线程会自己打印
    time.sleep(0.4)
    print("mcp_runtime: FastMCP SSE labs=%s" % ",".join(l.id for l in LABS), flush=True)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
