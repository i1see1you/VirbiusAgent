"""OpenAI Agents SDK: single-tenant M1 over SSE.

Verify the exact `MCPServerSse` constructor/params against the installed
`openai-agents` version. The proxy speaks SSE at `/sse` (not Streamable HTTP).
"""

import asyncio

from agents import Agent, Runner
from agents.mcp import MCPServerSse

PROXY_SSE_URL = "http://127.0.0.1:9090/sse"


async def main() -> None:
    async with MCPServerSse(params={"url": PROXY_SSE_URL}) as mcp:
        agent = Agent(
            name="demo",
            instructions="Use virbius__shell only when explicitly asked.",
            mcp_servers=[mcp],
        )
        result = await Runner.run(agent, "List the available tools.")
        print(result.final_output)


if __name__ == "__main__":
    asyncio.run(main())
