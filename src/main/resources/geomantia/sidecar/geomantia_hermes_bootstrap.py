"""Geomantia's cancellation adapter for the pinned Hermes session-stream API.

Hermes 0.18.2 cancels the SSE coroutine but its worker runs in an executor.
Keep an agent reference and interrupt that worker too. No vendor files are edited.
"""
import asyncio
import functools

# Shared with HermesAgentClient: bounded host decision input, not a model token limit.
MAX_PLANNING_TEXT_LENGTH = 262_144


def install_input_adapter(api_server):
    api_server.MAX_NORMALIZED_TEXT_LENGTH = MAX_PLANNING_TEXT_LENGTH
    original = api_server._normalize_multimodal_content
    if getattr(original, "_geomantia_input", False):
        return

    @functools.wraps(original)
    def normalize(content):
        parts = content if isinstance(content, list) else [content]
        text_length = 0
        for part in parts:
            if isinstance(part, str):
                text_length += len(part)
            elif isinstance(part, dict) and part.get("type") in ("text", "input_text", "output_text"):
                text_length += len(str(part.get("text") or ""))
        if text_length > MAX_PLANNING_TEXT_LENGTH:
            raise ValueError("planning_input_too_large:Host planning text exceeds the complete-input budget.")
        return original(content)

    normalize._geomantia_input = True
    api_server._normalize_multimodal_content = normalize


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


def main():
    from gateway.platforms import api_server
    install_input_adapter(api_server)
    install_cancellation_adapter(api_server.APIServerAdapter)
    from hermes_cli.main import main as hermes_main
    hermes_main()


if __name__ == "__main__":
    main()
