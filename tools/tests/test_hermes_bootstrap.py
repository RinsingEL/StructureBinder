"""Offline tests; does not start Hermes, a provider, or Minecraft."""
import asyncio
import importlib.util
import sys
from pathlib import Path
import unittest
from types import SimpleNamespace

module_path = Path(__file__).resolve().parents[2] / "src/main/resources/geomantia/sidecar/geomantia_hermes_bootstrap.py"
spec = importlib.util.spec_from_file_location("geomantia_hermes_bootstrap", module_path)
bootstrap = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(bootstrap)


class InputTest(unittest.TestCase):
    def test_complete_input_and_images_survive_beyond_old_limit(self):
        api = SimpleNamespace(MAX_NORMALIZED_TEXT_LENGTH=65536, _normalize_multimodal_content=lambda value: value)
        bootstrap.install_input_adapter(api)
        bootstrap.install_input_adapter(api)
        parts = [{"type": "text", "text": "x" * 100000 + "contextId:tail"},
                 {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA=="}}]
        self.assertEqual(api._normalize_multimodal_content(parts), parts)
        self.assertEqual(api.MAX_NORMALIZED_TEXT_LENGTH, 262144)
        parts[0]["text"] = "x" * 262145
        with self.assertRaisesRegex(ValueError, "planning_input_too_large"):
            api._normalize_multimodal_content(parts)


class CancellationTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancelling_stream_interrupts_worker(self):
        interrupted = []

        class Agent:
            def interrupt(self, reason):
                interrupted.append(reason)

        class Adapter:
            async def _run_agent(self, *, agent_ref=None):
                agent_ref[0] = Agent()
                await asyncio.Event().wait()

        bootstrap.install_cancellation_adapter(Adapter)
        bootstrap.install_cancellation_adapter(Adapter)
        task = asyncio.create_task(Adapter()._run_agent())
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(len(interrupted), 1)

    async def test_late_worker_creation_is_also_interrupted(self):
        interrupted = []
        class Agent:
            def interrupt(self, reason):
                interrupted.append(reason)
        lease = bootstrap.AgentLease()
        lease.interrupt()
        lease[:] = [Agent()]
        self.assertEqual(len(interrupted), 1)

    async def test_normal_completion_is_not_interrupted(self):
        class Adapter:
            async def _run_agent(self, *, agent_ref=None):
                return "completed"
        bootstrap.install_cancellation_adapter(Adapter)
        self.assertEqual(await Adapter()._run_agent(), "completed")


if __name__ == "__main__":
    unittest.main()
