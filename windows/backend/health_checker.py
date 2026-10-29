#!/usr/bin/env python3
"""
Pulse VPN - Automated Health Check & Node Filter
Пингует все узлы, отсеивает упавшие/заблокированные серверы,
сортирует живые серверы с приоритетом Белых списков (RU SNI) и Hysteria 2,
и обновляет подписки в облаке.
"""

import base64
import json
import logging
import os
import sys
import time
from typing import Any, Dict, List

# Add current dir to import vless_compiler and hf_cloud_sync
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from vless_compiler import (
    fetch_raw_subscription_from_mirrors,
    parse_proxy_uri,
    probe_nodes_concurrently,
    get_node_whitelist_priority,
)

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("HealthChecker")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(BASE_DIR, ".."))
HF_CLOUD_DIR = os.path.join(PROJECT_ROOT, "hf_space_cloud")
SUB_DIR = os.path.join(HF_CLOUD_DIR, "sub")


def run_health_check(timeout: float = 2.0, max_workers: int = 30) -> List[Dict[str, Any]]:
    logger.info("🔄 Запуск автоматического Health Check узлов...")

    # 1. Загрузка исходных списков
    raw_lines, source_url = fetch_raw_subscription_from_mirrors(timeout=10.0)
    logger.info(f"📥 Загружено {len(raw_lines)} ключей из зеркала: {source_url or 'локальные/зеркала'}")

    if not raw_lines:
        logger.warning("⚠️ Не удалось получить ключи из зеркал.")
        return []

    # 2. Парсинг VLESS и Hysteria 2
    parsed_nodes: List[Dict[str, Any]] = []
    for idx, line in enumerate(raw_lines):
        node = parse_proxy_uri(line, idx)
        if node:
            parsed_nodes.append(node)

    logger.info(f"🧩 Успешно распарсено: {len(parsed_nodes)} узлов.")

    # 3. Параллельное тестирование доступности
    logger.info(f"⚡ Тестирование доступности узлов (таймаут: {timeout}s)...")
    probed_nodes = probe_nodes_concurrently(parsed_nodes, timeout=timeout, max_workers=max_workers)

    alive_nodes = [n for n in probed_nodes if n.get("latency_ms") is not None]
    dead_nodes_count = len(probed_nodes) - len(alive_nodes)

    logger.info(f"✅ Результаты проверки:")
    logger.info(f"   - Всего проверено: {len(probed_nodes)}")
    logger.info(f"   - Живых и доступных: {len(alive_nodes)}")
    logger.info(f"   - Мертвых / неотвечающих (отфильтровано): {dead_nodes_count}")

    # 4. Если живые есть, формируем чистый отсортированный пул
    target_nodes = alive_nodes if alive_nodes else probed_nodes

    # 5. Сохраняем в локальные файлы подписок hf_space_cloud/sub
    os.makedirs(SUB_DIR, exist_ok=True)

    alive_uris = [n["raw_uri"] for n in target_nodes]
    sub_plain_text = "\n".join(alive_uris) + "\n"
    sub_b64_text = base64.b64encode(sub_plain_text.encode("utf-8")).decode("utf-8")

    universal_txt_path = os.path.join(SUB_DIR, "vless_universal.txt")
    with open(universal_txt_path, "w", encoding="utf-8") as f:
        f.write(sub_plain_text)

    list_universal_path = os.path.join(SUB_DIR, "list_universal.txt")
    with open(list_universal_path, "w", encoding="utf-8") as f:
        f.write(sub_plain_text)

    # Сохраняем также метаданные о состоянии здоровья
    health_report = {
        "timestamp": int(time.time()),
        "total": len(probed_nodes),
        "alive": len(alive_nodes),
        "dead": dead_nodes_count,
        "nodes": [
            {
                "remark": n["remark"],
                "protocol": n.get("protocol", "vless"),
                "address": n["address"],
                "port": n["port"],
                "latency_ms": n["latency_ms"],
                "whitelist_priority": get_node_whitelist_priority(n),
            }
            for n in target_nodes[:30]
        ],
    }

    report_path = os.path.join(HF_CLOUD_DIR, "health_state.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(health_report, f, ensure_ascii=False, indent=2)

    logger.info(f"💾 Обновлены локальные файлы подписок: {universal_txt_path}")
    return target_nodes


if __name__ == "__main__":
    nodes = run_health_check()
    logger.info(f"🎉 Проверка здоровья завершена! Доступно {len(nodes)} серверов.")
