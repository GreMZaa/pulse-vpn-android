#!/usr/bin/env python3
"""
Автономный 24/7 сборщик и фильтр живых серверов для облака ПУЛЬС VPN (Hugging Face Space).
Запускается автоматически каждые 20 минут через GitHub Actions:
1. Скачивает 15+ источников Белых списков РФ (igareck Mobile/Cable/CIDR, zieng2/wl, LowiKLive, AvenCores, Hysteria2, OpenRay);
2. Удаляет дубликаты по host:port и проверяет реальное TLS/Reality рукопожатие с целевым SNI;
3. Загружает текущий cloud_state.json из облака, сохраняя 100% ключей клиентов, HWID-привязок, промокодов и VIP-каскадов;
4. Генерирует кроссплатформенные подписки (/sub/<KEY>, /sub/<KEY>_singbox.json, /sub/<KEY>_xray.json) и атомарно коммитит в Hugging Face Space.
"""

import base64
import json
import os
import sys
import time
import urllib.request
from typing import Any, Dict, List

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vless_compiler import (
    build_singbox_auto_config,
    build_xray_auto_config,
    deduplicate_parsed_nodes,
    fetch_raw_subscription_from_mirrors,
    parse_proxy_uri,
    probe_nodes_concurrently,
)

HF_REPO = os.environ.get("HF_REPO", "GreeMZaa/pulsewl-vpn")
LIVE_URL = os.environ.get("LIVE_URL", "https://greemzaa-pulsewl-vpn.static.hf.space")


def get_hf_token() -> str:
    env_tok = os.environ.get("HF_TOKEN", "").strip()
    if not env_tok:
        raise RuntimeError("HF_TOKEN environment variable is required")
    return env_tok


def fetch_current_cloud_state() -> Dict[str, Any]:
    url = f"{LIVE_URL.rstrip('/')}/api/cloud_state.json?t={int(time.time())}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "PulseVPN-AutoCollector/1.0", "Cache-Control": "no-cache"},
    )
    with urllib.request.urlopen(req, timeout=12.0) as resp:
        return json.loads(resp.read().decode("utf-8", errors="ignore"))


def commit_files_to_hf(token: str, repo_id: str, summary: str, files_map: Dict[str, str]) -> None:
    commit_url = f"https://huggingface.co/api/spaces/{repo_id}/commit/main"
    ndjson_lines = [
        json.dumps({"key": "header", "value": {"summary": summary, "description": "Automated 20m Health Check & Whitelist Sync"}})
    ]
    for path, content_str in files_map.items():
        b64_content = base64.b64encode(content_str.encode("utf-8")).decode("ascii")
        ndjson_lines.append(
            json.dumps(
                {
                    "key": "file",
                    "value": {
                        "path": path,
                        "content": b64_content,
                        "encoding": "base64",
                    },
                }
            )
        )
    body = ("\n".join(ndjson_lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        commit_url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/x-ndjson",
            "User-Agent": "PulseVPN-AutoCollector/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=30.0) as resp:
        print(f"✅ Успешный коммит в Hugging Face ({resp.status}): {summary}")


def main() -> None:
    now = int(time.time())
    print("🔄 [1/4] Загрузка текущего состояния ключей и промокодов из облака...")
    current_state = fetch_current_cloud_state()
    keys_list: List[Dict[str, Any]] = current_state.get("keys", [])
    promos_list: List[Dict[str, Any]] = current_state.get("promos", [])
    custom_cascade_uris: List[str] = current_state.get("custom_cascade_uris", [])
    plans_list = current_state.get("plans", [])

    print(f"🔑 Сохранено ключей клиентов: {len(keys_list)}, промокодов: {len(promos_list)}, VIP-каскадов: {len(custom_cascade_uris)}")

    print("🌐 [2/4] Сбор серверов из 15+ источников Белых списков РФ и Hysteria2...")
    raw_uris, source_summary = fetch_raw_subscription_from_mirrors(timeout=8.0, max_candidates=260)
    parsed_nodes = []
    for i, u in enumerate(raw_uris):
        p = parse_proxy_uri(u, i)
        if p:
            parsed_nodes.append(p)

    print(f"⚡ [3/4] Проверка реального TLS/Reality рукопожатия для {len(parsed_nodes)} уникальных серверов...")
    alive_nodes = probe_nodes_concurrently(parsed_nodes, timeout=2.0, max_workers=40, only_alive=True)

    # Добавляем собственные VIP-каскады в самое начало списка
    cascade_nodes: List[Dict[str, Any]] = []
    for idx, c_uri in enumerate(custom_cascade_uris):
        pc = parse_proxy_uri(c_uri, idx)
        if pc:
            pc["is_cascade"] = True
            if "👑" not in pc["remark"]:
                pc["remark"] = f"👑 VIP Каскад | {pc['remark']}"
            pc["latency_ms"] = pc.get("latency_ms") or 25
            cascade_nodes.append(pc)

    merged_nodes = deduplicate_parsed_nodes(cascade_nodes + alive_nodes)[:110]
    if len(merged_nodes) < 5:
        print("⚠️ Слишком мало живых серверов при проверке, отмена перезаписи.")
        return

    foreign_uris = [n["raw_uri"] for n in merged_nodes if not n.get("is_ru", False)]
    all_uris = [n["raw_uri"] for n in merged_nodes]
    b64_all = base64.b64encode("\n".join(all_uris or foreign_uris).encode("utf-8")).decode("utf-8")
    b64_foreign = base64.b64encode("\n".join(foreign_uris or all_uris).encode("utf-8")).decode("utf-8")

    singbox_json = json.dumps(
        build_singbox_auto_config(merged_nodes, mode="whitelist_all", smart_routing_ru=True, enable_adblock=True),
        indent=2,
        ensure_ascii=False,
    )
    xray_json = json.dumps(
        build_xray_auto_config(merged_nodes, mode="whitelist_all", smart_routing_ru=True, enable_adblock=True, enable_fragment=True),
        indent=2,
        ensure_ascii=False,
    )

    # Обновляем дни подписки для ключей
    updated_keys = []
    files_to_commit: Dict[str, str] = {}

    for k in keys_list:
        code = (k.get("key_code") or "").strip()
        if not code:
            continue
        exp_ts = int(k.get("expires_at") or (now + 86400))
        is_act = bool(k.get("is_active", True))
        is_val = bool(is_act and exp_ts > now)
        rem_days = round(max(0, exp_ts - now) / 86400, 1)
        k_copy = dict(k)
        k_copy["days_left"] = rem_days
        k_copy["is_subscription_valid"] = is_val
        updated_keys.append(k_copy)

        if is_val:
            files_to_commit[f"sub/{code}"] = b64_all
            files_to_commit[f"sub/{code}_singbox.json"] = singbox_json
            files_to_commit[f"sub/{code}_xray.json"] = xray_json

    for p in promos_list:
        pcode = (p.get("code") or "").strip().upper()
        if pcode:
            files_to_commit[f"sub/{pcode}"] = b64_all
            files_to_commit[f"sub/{pcode}_singbox.json"] = singbox_json
            files_to_commit[f"sub/{pcode}_xray.json"] = xray_json

    files_to_commit["sub/DEMO"] = b64_all
    files_to_commit["sub/singbox.json"] = singbox_json
    files_to_commit["sub/xray.json"] = xray_json

    alive_count = len(merged_nodes)
    foreign_count = sum(1 for n in merged_nodes if not n.get("is_ru", False))
    ru_count = sum(1 for n in merged_nodes if n.get("is_ru", False))

    new_cloud_state = {
        "updated_at": now,
        "live_url": LIVE_URL,
        "source_mirror": source_summary,
        "auto_sync_interval_min": 20,
        "custom_cascade_uris": custom_cascade_uris,
        "features": {
            "multi_protocol_armor": ["VLESS-Reality", "VLESS-XHTTP", "Hysteria2-QUIC", "TUIC-v5", "TLS-Fragment"],
            "smart_routing_ru": True,
            "adguard_dns_adblock": True,
            "cross_platform_subs": True,
        },
        "pool": {
            "total_nodes": alive_count,
            "alive_nodes": alive_count,
            "foreign_nodes": foreign_count,
            "ru_whitelist_nodes": ru_count,
            "last_sync": now,
        },
        "plans": plans_list,
        "promos": promos_list,
        "keys": updated_keys,
        "nodes": [
            {
                "id": n["id"],
                "remark": n["remark"],
                "protocol": n.get("protocol", "vless").upper(),
                "address": n["address"],
                "port": n["port"],
                "type": n["type"].upper(),
                "security": n["security"].upper(),
                "sni": n.get("sni", ""),
                "is_ru": n["is_ru"],
                "is_cascade": bool(n.get("is_cascade", False)),
                "latency_ms": n["latency_ms"],
                "raw_uri": n["raw_uri"],
            }
            for n in merged_nodes
        ],
        "subscriptions_b64": {
            "all": b64_all,
            "foreign_only": b64_foreign,
        },
    }

    files_to_commit["api/cloud_state.json"] = json.dumps(new_cloud_state, indent=2, ensure_ascii=False)

    print(f"🚀 [4/4] Отправка {alive_count} живых серверов в облако Hugging Face ({HF_REPO})...")
    commit_files_to_hf(
        token=get_hf_token(),
        repo_id=HF_REPO,
        summary=f"Auto-Sync: {alive_count} alive Whitelist/Reality/HY2 nodes",
        files_map=files_to_commit,
    )


if __name__ == "__main__":
    main()
