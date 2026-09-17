"""Offline compatibility checks against the actual bundled Hermes source."""
import asyncio
from types import SimpleNamespace
from unittest.mock import patch
from agent import auxiliary_client as aux
from agent import chat_completion_helpers as main
from agent.opencode_affinity import opencode_session_headers
import geomantia_hermes_bootstrap as host

messages = [{"role": "user", "content": "test"}]
url = "https://opencode.ai/zen/go/v1"
agent = SimpleNamespace(provider="custom", base_url=url, session_id="city-session-1")
with patch.object(main, "_build_api_kwargs_for_mode", side_effect=lambda *args: {}):
    first = main.build_api_kwargs(agent, messages)["extra_headers"]["x-opencode-session"]
    assert first == main.build_api_kwargs(agent, messages)["extra_headers"]["x-opencode-session"] == "city-session-1"
    agent.session_id = "city-session-2"
    assert main.build_api_kwargs(agent, messages)["extra_headers"]["x-opencode-session"] != first
assert opencode_session_headers("custom", "https://example.com/v1", "city-session-1") == {}
token = aux.set_runtime_main("custom", "deepseek-v4.1-flash", base_url=url, session_id="city-session-1")
try:
    kwargs = aux._build_call_kwargs("custom", "deepseek-v4.1-flash", messages, base_url=url)
    assert kwargs["extra_headers"]["x-opencode-session"] == first
finally:
    aux._RUNTIME_MAIN_CONTEXT.reset(token)

from run_agent import AIAgent
assert hasattr(AIAgent, "_fire_reasoning_delta")
from gateway.platforms import api_server
host.install_input_adapter(api_server)
assert api_server._normalize_chat_content("x" * 300000).endswith("x" * 300000)

class Worker:
    def __init__(self): self.interrupted = False
    def interrupt(self, reason): self.interrupted = True
class Adapter:
    async def _run_agent(self, *, agent_ref):
        agent_ref[0] = worker
        raise asyncio.CancelledError()
worker = Worker()
host.install_cancellation_adapter(Adapter)
try: asyncio.run(Adapter()._run_agent())
except asyncio.CancelledError: pass
assert worker.interrupted
print("HERMES_AFFINITY_AND_ADAPTERS_READY")

# Exercise the same CLI gateway used by the host, not merely import its modules.
import json, os, pathlib, secrets, socket, subprocess, sys, time, urllib.request
profile = pathlib.Path(os.environ["HERMES_HOME"]) / "profiles" / "smoke"
profile.mkdir(parents=True, exist_ok=True)
(profile / "config.yaml").write_text("model:\n  provider: custom\n  default: fixture-model\n  base_url: http://127.0.0.1:1/v1\n  api_key: fixture-only\ntoolsets: []\n", encoding="utf-8")
with socket.socket() as reserve:
    reserve.bind(("127.0.0.1", 0))
    port = reserve.getsockname()[1]
key = secrets.token_hex(32)
env = os.environ.copy()
env.update(HERMES_HOME=str(profile), API_SERVER_ENABLED="true", API_SERVER_HOST="127.0.0.1", API_SERVER_PORT=str(port),
           API_SERVER_KEY=key, OPENAI_API_KEY="fixture-only", OPENAI_BASE_URL="http://127.0.0.1:1/v1")
log = profile / "gateway-smoke.log"
with log.open("wb") as output:
    process = subprocess.Popen([sys.executable, host.__file__, "-p", "smoke", "gateway"],
                               env=env, stdout=output, stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        started = time.monotonic()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() - started < 30:
            assert process.poll() is None, log.read_text(encoding="utf-8", errors="replace")
            try:
                with opener.open(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                    if response.status == 200: break
            except (OSError, urllib.error.URLError): time.sleep(0.2)
        else: raise AssertionError("Gateway did not become healthy in 30s: " + log.read_text(encoding="utf-8", errors="replace"))
        payload = json.dumps({"id": "geomantia-smoke-session", "model": "fixture-model"}).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/sessions", data=payload,
                  headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        with opener.open(request, timeout=3) as response: assert response.status in (200, 201)
        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/sessions/geomantia-smoke-session",
                  headers={"Authorization": "Bearer " + key})
        with opener.open(request, timeout=3) as response: assert response.status == 200
        print("HERMES_GATEWAY_HEALTH_AND_SESSION_READY", round(time.monotonic() - started, 2))
    finally:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
