"""Offline group-intent compiler. Runtime validation/planning stays in Realmfolk Java.

No model/network calls, no game save writes and no publication. All authored choices
are visible in the intent and policy; missing world evidence is never invented.
"""
import argparse
import copy
import hashlib
import json
import math
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fingerprint(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def nearby_candidates(sites, origin, dimension, maximum_distance, maximum_tier):
    """Readiness only: a generated site is not proof its enemies/kill goal exist."""
    ready, excluded = [], []
    for site in sites:
        reason = None
        if site.get("dimension") != dimension:
            reason = "different_dimension"
        elif site.get("complete") is not True:
            reason = "not_materialized"
        elif type(site.get("tier")) is not int or not 1 <= site["tier"] <= maximum_tier:
            reason = "tier_out_of_range"
        elif not all(type(site.get(k)) in (int, float) and math.isfinite(site[k]) for k in ("x", "z")):
            reason = "invalid_position"
        elif math.hypot(site["x"] - origin[0], site["z"] - origin[1]) > maximum_distance:
            reason = "too_far"
        if reason:
            excluded.append({"id": site.get("id"), "reason": reason})
        else:
            ready.append({"id": site["id"], "tier": site["tier"], "distance": round(math.hypot(site["x"] - origin[0], site["z"] - origin[1])), "status": "location_only_not_kill_contract"})
    return {"locations": sorted(ready, key=lambda x: (x["distance"], x["id"])), "excluded": excluded,
            "huntContracts": [], "reason": "No verified targets or kill/settlement adapter; do not issue a hunt."}


def freeze_d4(directory):
    paths = {n: directory / n for n in ["city_blueprint.json", "city_blueprint_submission_trace.json",
                                       "city_blueprint_geometry_commit.json", "city_blueprint_catalog_snapshot.json"]}
    docs = {n: read(p) for n, p in paths.items()}
    digest = "sha256:" + fingerprint(paths["city_blueprint.json"])
    trace, commit = docs["city_blueprint_submission_trace.json"], docs["city_blueprint_geometry_commit.json"]
    require(trace["status"] == "accepted" and trace["cityBlueprintHash"] == digest and commit["blueprintHash"] == digest,
            "D4 acceptance/geometry hash mismatch")
    anchors = commit["result"]["structureAnchorPlan"]["anchors"]
    require(len({a["anchorId"] for a in anchors}) == len(anchors), "Duplicate D4 anchor")
    used = {a["templateId"] for a in anchors}
    profiles = [p for p in docs["city_blueprint_catalog_snapshot.json"]["structureCatalog"]["semanticProfiles"] if p["semanticProfileId"] in used]
    by_profile = {p["semanticProfileId"]: p for p in profiles}
    for a in anchors:
        p = by_profile.get(a["templateId"], {})
        require(p.get("reviewState") == "approved" and p.get("functionTerms"), "Unapproved or unclassified retained building: " + a["anchorId"])
    return {"source": {"directory": str(directory), "blueprintHash": digest,
                       "files": {n: fingerprint(p) for n, p in paths.items()}},
            "anchors": {"anchors": anchors}, "snapshot": {"structureCatalog": {"semanticProfiles": profiles}},
            "districts": docs["city_blueprint.json"]["districtDesigns"]}


def compile_intent(intent, policy, evidence, economy):
    require(intent.get("schema") == "realmfolk.group-intent.experiment.v1", "Unknown intent schema")
    require(set(intent) <= {"schema", "cityName", "groups"}, "Unknown intent field")
    require(isinstance(intent["groups"], list) and 1 <= len(intent["groups"]) <= 8, "Expected 1..8 groups")
    profiles = {p["semanticProfileId"]: p for p in evidence["snapshot"]["structureCatalog"]["semanticProfiles"]}
    anchors = evidence["anchors"]["anchors"]
    terms = sorted({t for a in anchors for t in profiles[a["templateId"]]["functionTerms"]})
    settings = copy.deepcopy(policy["population"])
    require(settings["housingTerms"] and set(settings["housingTerms"]) <= set(terms), "Housing must match actual D4 author terms")
    require(0 <= settings["housingOccupancy"] <= 1 and type(settings["residentsPerHouse"]) is int and 1 <= settings["residentsPerHouse"] <= 8, "Invalid housing policy")
    settings["jobs"] = {}
    submission = {"baseRevision": 1, "plan": {"template": "default", "overrides": settings,
                  "keyPeople": [], "economy": {"pools": {}, "tiers": policy["tiers"], "priceBand": policy["priceBand"]},
                  "functionAnswers": []}, "professions": {}, "reuseProfessions": []}
    require(policy["priceBand"] in economy["priceBands"], "Unknown price band")
    require(set(policy["tiers"]) <= set(economy["tiers"]), "Unknown tier")
    groups, pending, demands, identifiers = [], [], [], set()
    for group in intent["groups"]:
        require(set(group) <= {"id", "name", "purpose", "work", "roster", "pools", "needs", "requestedAbilities"}, "Unknown group field")
        gid = group["id"]
        require(isinstance(gid, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,18}", gid) and gid not in identifiers, "Invalid or duplicate group ID")
        identifiers.add(gid)
        require(group["name"] and group["purpose"], "Group requires name/purpose")
        require(group["work"] and set(group["work"]) <= set(terms), "Unknown work function in " + gid)
        require(group["pools"] and set(group["pools"]) <= set(economy["pools"]), "Unknown goods pool in " + gid)
        require(group["roster"] and set(group["roster"]) <= set(policy["roles"]), "Unknown role in " + gid)
        require(set(group["needs"]) <= set(policy["needs"]), "Unknown need in " + gid)
        require(len(group["needs"]) <= 1, "Runtime has one delivery commission per profession; split demand or wait for an adapter")
        for requested in group.get("requestedAbilities", []):
            require(requested in policy["unsupported"], "Unknown requested ability: " + requested)
            pending.append({"group": gid, "ability": requested, "reason": policy["unsupported"][requested]})
        for pool in group["pools"]:
            submission["plan"]["economy"]["pools"][pool] = 1
        jobs = []
        for role, weight in sorted(group["roster"].items()):
            require(type(weight) in (int, float) and math.isfinite(weight) and 0 < weight <= 1000, "Invalid relative role weight")
            rule = policy["roles"][role]
            pid = gid + "_" + role
            require(len(pid) <= 32, "Profession ID too long")
            profession = {"name": group["name"] + "·" + rule["name"], "bio": group["purpose"],
                          "appearance": rule["appearance"], "capabilities": list(rule["capabilities"]),
                          "workplaceTerms": group["work"] if rule["atWork"] else [],
                          "restStart": policy["restStart"], "restEnd": policy["restEnd"],
                          "itemPools": group["pools"], "stockSlots": rule["stockSlots"],
                          "goodsPurpose": group["purpose"]}
            if rule["atWork"] and group["needs"]:
                need = policy["needs"][group["needs"][0]]
                require(need["item"] in economy["goods"], "Unknown demand item")
                require(type(need["count"]) is int and 1 <= need["count"] <= 64, "Invalid demand count")
                supply = [pool for pool in group["pools"] if any(e["item"] == need["item"] for e in economy["pools"][pool]["entries"])]
                require(supply, "Demand has no authored acquisition channel: " + need["item"])
                good = economy["goods"][need["item"]]
                # Currency reward follows author buyback value, never an invented windfall.
                currency = min(64, math.floor(good["buyback"] * need["count"] / economy["legacyUnit"]))
                profession["capabilities"].append("commission")
                profession["commission"] = {"item": need["item"], "count": need["count"], "emeralds": currency,
                                            "affection": policy["quest"]["affection"], "reputation": policy["quest"]["reputation"]}
                profession["commissionPurpose"] = need["purpose"]
                demands.append({"group": gid, "profession": pid, **need, "source": "configured_city_trade_pool",
                                "sourcePools": supply, "availability": "供货规则可达；随机库存不保证即时有货，未证明野外分布",
                                "currencyReward": currency * economy["legacyUnit"]})
            submission["professions"][pid] = profession
            settings["jobs"][pid] = {"min": rule["minimum"], "max": rule["maximum"], "weight": weight}
            jobs.append(pid)
        buildings = [a["anchorId"] for a in anchors if set(profiles[a["templateId"]]["functionTerms"]) & set(group["work"])]
        groups.append({**group, "professions": jobs, "workBuildings": buildings})
    require(len(submission["professions"]) <= 16, "Realmfolk supports at most 16 new professions per city")
    for term in terms:
        jobs = [pid for pid, p in submission["professions"].items() if term in p["workplaceTerms"] or (not p["workplaceTerms"] and term in settings["housingTerms"])]
        submission["plan"]["functionAnswers"].append({"functionTerm": term, "housing": term in settings["housingTerms"],
             "professions": jobs, "reason": "按群体规则安排岗位或住所" if jobs else "建筑用途保留，本轮不另设常驻岗位"})
    return submission, {"groups": groups, "pendingAbilities": pending, "deliveryDemands": demands}


def build(args):
    intent, policy, economy = read(args.intent), read(args.policy), read(args.economy)
    evidence = freeze_d4(Path(args.d4))
    submission, report = compile_intent(intent, policy, evidence, economy)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    source_files = {str(Path(p).resolve()): fingerprint(p) for p in (args.intent, args.policy, args.economy)}
    report.update({"title": intent["cityName"], "scope": "offline_configuration_experiment", "source": evidence["source"],
                   "inputHashes": source_files, "seed": policy["seed"], "intent": intent,
                   "metrics": {"aiGroups": len(intent["groups"]), "compiledProfessions": len(submission["professions"]),
                               "retainedBuildings": len(evidence["anchors"]["anchors"]), "functionRows": len(submission["plan"]["functionAnswers"]),
                               "aiInputCharacters": len(json.dumps(intent, ensure_ascii=False, separators=(",", ":"))),
                               "expandedCharacters": len(json.dumps(submission, ensure_ascii=False, separators=(",", ":")))},
                   "nearby": {"locations": [], "huntContracts": [], "reason": "本学院未提供真实冒险地点/怪物观测；不从结构池虚构附近坐标。"},
                   "limitations": ["离线使用正式校验与规划器，不代表游戏已生成 NPC。", "人口演算站立点为显式试验假设，未进行游戏内可站立验证。",
                                   "群体目前映射为职业与背景，亲属关系、共同住所、群体声望尚未写入运行时。",
                                   "岗位按建筑用途匹配，不能保证落在 AI 指定的某个院落。",
                                   "赠礼使用已有个人好感和 d100 规则；尚无派系专属关系门槛。",
                                   "教学、施法及附近讨伐待接入；不会自动降级成交易来宣称已实现。"]})
    write(output / "evidence.json", evidence)
    write(output / "submission.json", submission)
    write(output / "economy.json", economy)
    write(output / "report.json", report)
    print(json.dumps(report["metrics"], ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--d4", required=True)
    parser.add_argument("--intent", default=HERE / "academy.intent.json")
    parser.add_argument("--policy", default=HERE / "author-policy.json")
    parser.add_argument("--economy", default=REPO / "examples/modpacks/pcl-industries/economy.json")
    parser.add_argument("--output", default=HERE / "results/academy")
    build(parser.parse_args())
