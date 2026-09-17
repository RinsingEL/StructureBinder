"""Geomantia's cancellation adapter for the pinned Hermes session-stream API.

The session SSE coroutine and its executor worker have separate lifetimes.
This adapter is verified against Hermes 0.21.3.
Keep an agent reference and interrupt that worker too. No vendor files are edited.
"""
import asyncio
import functools
import sys

def install_input_adapter(api_server):
    # Disable the vendor normalizer's character slicing as well as our former rejection.
    # Actual HTTP body limits remain enforced by the session server; no text is silently cut.
    api_server.MAX_NORMALIZED_TEXT_LENGTH = sys.maxsize


class AgentLease(list):
    def __init__(self):
        super().__init__([None])
        self.revoked = False

    def __setitem__(self, key, value):
        super().__setitem__(key, value)
        if self.revoked:
            self.interrupt()

    def interrupt(self):
        self.revoked = True
        if self and self[0] is not None:
            self[0].interrupt("Geomantia host ended this design turn")


def install_cancellation_adapter(adapter_type):
    original = adapter_type._run_agent
    if getattr(original, "_geomantia_cancellation", False):
        return

    @functools.wraps(original)
    async def managed(self, *args, **kwargs):
        lease = kwargs.get("agent_ref")
        if lease is None:
            lease = AgentLease()
            kwargs["agent_ref"] = lease
        try:
            return await original(self, *args, **kwargs)
        except asyncio.CancelledError:
            if isinstance(lease, AgentLease):
                lease.interrupt()
            elif lease and lease[0] is not None:
                lease[0].interrupt("Geomantia host ended this design turn")
            raise

    managed._geomantia_cancellation = True
    adapter_type._run_agent = managed


def install_reasoning_adapter(agent_type):
    """Forward provider-visible reasoning via the pinned session SSE progress channel."""
    original = agent_type._fire_reasoning_delta
    if getattr(original, "_geomantia_reasoning", False):
        return

    @functools.wraps(original)
    def forward(self, text):
        original(self, text)
        callback = getattr(self, "tool_progress_callback", None)
        if callback and isinstance(text, str) and text:
            callback("reasoning.available", "_geomantia_reasoning", text, None)

    forward._geomantia_reasoning = True
    agent_type._fire_reasoning_delta = forward


def main():
    from gateway.platforms import api_server
    install_input_adapter(api_server)
    install_cancellation_adapter(api_server.APIServerAdapter)
    from run_agent import AIAgent
    install_reasoning_adapter(AIAgent)
    from hermes_cli.main import main as hermes_main
    hermes_main()


if __name__ == "__main__":
    main()
