"""LangGraph / langchain-mcp-adapters: single-tenant M1 over SSE.

No agent code changes beyond client registration. Local tools appear as
`virbius__shell` / `virbius__python` / `virbius__node` when the deployment
license grants them explicitly.
"""

import asyncio

from langchain_mcp_adapters.client import MultiServerMCPClient

PROXY_SSE_URL = "http://127.0.0.1:9090/sse"


async def main() -> None:
    client = MultiServerMCPClient(
        {"virbius": {"transport": "sse", "url": PROXY_SSE_URL}}
    )
    tools = await client.get_tools()
    print("tools:", [t.name for t in tools])


if __name__ == "__main__":
    asyncio.run(main())
