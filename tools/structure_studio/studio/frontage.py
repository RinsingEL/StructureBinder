"""Manual frontage decisions, bound to the actual NBT and authored entrance geometry."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from threading import RLock

from .model import sha256, write_json

LOCK = RLock()
POLICIES = {"FIXED_FRONT", "ANY_AUTHORED_ENTRANCE"}


def entrances(author):
    return [point for point in author.get("points", []) if point.get("kind") == "entrance"]


def entrance_hash(author):
    value = [{key: point.get(key) for key in ("id", "pos", "facing")} for point in entrances(author)]
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def resolve(author, nbt_hash=None):
    """Manual decisions win; otherwise use the user's deterministic first-entrance default."""
    points = entrances(author)
    ids = [point.get("id") for point in points]
    if not ids:
        return dict(status="missing", message="没有已标入口，需先在模型作者记录中补齐入口。")
    if any(not isinstance(key, str) or not key.strip() for key in ids) or len(ids) != len(set(ids)):
        return dict(status="invalid", message="入口编号为空或重复，请先修正入口标记。")
    decision = author.get("frontage")
    if decision is not None:
        if not isinstance(decision, dict) or decision.get("policy") not in POLICIES:
            return dict(status="invalid", message="正面配置格式无效，请重新标注。")
        if (decision.get("nbt_sha256") != (nbt_hash or author.get("nbt_sha256"))
                or decision.get("entrances_sha256") != entrance_hash(author)):
            return dict(status="stale", message="模型或入口位置已改变，请重新核对并保存正面。")
        primary = decision.get("entrance_id", "")
        if decision["policy"] == "FIXED_FRONT" and primary not in ids:
            return dict(status="invalid", message="所选主入口已不存在，请重新标注。")
        if decision["policy"] == "ANY_AUTHORED_ENTRANCE" and primary:
            return dict(status="invalid", message="无固定正面的配置不能同时指定主入口。")
        return dict(status="ready", policy=decision["policy"], entrance_id=primary, manual=True,
                    message="正面配置已保存。")
    fronts = [key for key in ids if key.lower() == "front"]
    if len(ids) == 1 or len(fronts) == 1:
        return dict(status="ready", policy="FIXED_FRONT", entrance_id=ids[0] if len(ids) == 1 else fronts[0],
                    manual=False, message="沿用唯一入口或已有 front 主入口；可手动修改。")
    return dict(status="ready", policy="FIXED_FRONT", entrance_id=ids[0], manual=False,
                selection="first_authored_entrance", message="暂按入口数组的第一个作为主入口；可手动修改。")


def save(directory: Path, request):
    """Optimistic, atomic author-only update. Never relabel old visual evidence."""
    with LOCK:
        path = directory / "author.json"
        before = path.read_bytes()
        author = json.loads(before)
        if hashlib.sha256(before).hexdigest() != request.get("author_sha256"):
            raise FileExistsError("作者记录已被修改，请刷新模型后重新标注。")
        actual = sha256(directory / "structure.nbt")
        if actual != request.get("nbt_sha256") or actual != author.get("nbt_sha256"):
            raise FileExistsError("模型已经变化，请刷新并核对 NBT 与作者记录。")
        policy = request.get("policy")
        primary = request.get("entrance_id", "")
        if policy not in POLICIES or not isinstance(primary, str):
            raise ValueError("请选择有效的正面策略和入口。")
        author["frontage"] = dict(policy=policy, entrance_id=primary, nbt_sha256=actual,
                                  entrances_sha256=entrance_hash(author))
        state = resolve(author, actual)
        if state["status"] != "ready":
            raise ValueError(state["message"])
        write_json(path, author)
        return dict(author=author, author_sha256=sha256(path), frontage=state)


def runtime_frontage(author, ports):
    """Map the chosen authored entrance to City's existing reserved 'front' ID."""
    state = resolve(author)
    if state["status"] != "ready":
        raise ValueError(f"STUDIO_FRONTAGE_REQUIRED: {author['id']}: {state['message']}")
    result = deepcopy(ports)
    primary = state.get("entrance_id")
    # ANY must also remove a legacy 'front' name, which City would otherwise prioritize.
    taken = {port["entranceId"] for port in result}
    for port in result:
        original = port["entranceId"]
        if state["policy"] == "FIXED_FRONT" and original == primary:
            port["entranceId"] = "front"
        elif original.lower() == "front":
            renamed = "authored_" + original
            while renamed in taken:
                renamed = "authored_" + renamed
            taken.add(renamed)
            port["entranceId"] = renamed
    if state["policy"] == "FIXED_FRONT" and not any(p["entranceId"] == "front" for p in result):
        raise ValueError(f"STUDIO_FRONTAGE_ENTRANCE_MISSING: {author['id']}")
    return result, state["policy"]
