import os
import secrets
import sqlite3
import time
from typing import Any, Dict, List, Optional, Tuple

DB_PATH = os.environ.get(
    "VPN_DB_PATH",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "vpn_service.db"),
)

PLANS = {
    "trial": {"id": "trial", "title": "Пробный (24 часа)", "days": 1, "price_rub": 0, "max_devices": 2},
    "month_1": {"id": "month_1", "title": "1 Месяц (Полный Авто-Обход)", "days": 30, "price_rub": 199, "max_devices": 3},
    "month_3": {"id": "month_3", "title": "3 Месяца (-15% Выгода)", "days": 90, "price_rub": 499, "max_devices": 5},
    "year_1": {"id": "year_1", "title": "1 Год VIP (Максимум устройств)", "days": 365, "price_rub": 1499, "max_devices": 10},
}


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def generate_key_code() -> str:
    """Генерирует красивый ключ доступа в стиле Mullvad/Amnezia: XXXX-XXXX-XXXX-XXXX"""
    alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    parts = ["".join(secrets.choice(alphabet) for _ in range(4)) for _ in range(4)]
    return "-".join(parts)


def init_db() -> None:
    conn = get_conn()
    cur = conn.cursor()

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS access_keys (
            key_code TEXT PRIMARY KEY,
            plan_name TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            max_devices INTEGER NOT NULL DEFAULT 3,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            note TEXT DEFAULT ''
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            key_code TEXT NOT NULL,
            hwid TEXT NOT NULL,
            device_name TEXT NOT NULL,
            os_type TEXT NOT NULL,
            last_seen INTEGER NOT NULL,
            UNIQUE(key_code, hwid)
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS promo_codes (
            code TEXT PRIMARY KEY,
            days INTEGER NOT NULL,
            max_uses INTEGER NOT NULL DEFAULT 100,
            used_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS promo_usages (
            key_code TEXT NOT NULL,
            code TEXT NOT NULL,
            used_at INTEGER NOT NULL,
            PRIMARY KEY (key_code, code)
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS custom_nodes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            vless_uri TEXT NOT NULL,
            is_active INTEGER NOT NULL DEFAULT 1,
            added_at INTEGER NOT NULL
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS payments (
            order_id TEXT PRIMARY KEY,
            key_code TEXT NOT NULL,
            plan_id TEXT NOT NULL,
            amount_rub INTEGER NOT NULL,
            days INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at INTEGER NOT NULL
        )
        """
    )

    conn.commit()
    conn.close()


def create_or_bind_device(
    hwid: str,
    device_name: str = "Windows PC",
    os_type: str = "windows",
    key_code: Optional[str] = None,
) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    """
    Если key_code передан — проверяет его и привязывает устройство (с учетом лимита max_devices).
    Если key_code не передан — ищет существующий ключ по HWID или создает новый с 24ч триалом.
    """
    conn = get_conn()
    cur = conn.cursor()
    now = int(time.time())

    if key_code:
        clean_key = key_code.strip().upper()
        row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (clean_key,)).fetchone()
        if not row:
            conn.close()
            return False, "Ключ доступа не найден. Проверьте правильность ввода.", None
        if not row["is_active"]:
            conn.close()
            return False, "Этот ключ заблокирован администратором.", None

        # Проверяем, привязано ли уже это устройство
        existing_dev = cur.execute(
            "SELECT * FROM devices WHERE key_code = ? AND hwid = ?", (clean_key, hwid)
        ).fetchone()
        if not existing_dev:
            dev_count = cur.execute(
                "SELECT COUNT(*) as cnt FROM devices WHERE key_code = ?", (clean_key,)
            ).fetchone()["cnt"]
            if dev_count >= row["max_devices"]:
                conn.close()
                return (
                    False,
                    f"Достигнут лимит устройств ({row['max_devices']} шт.) для этого ключа.",
                    None,
                )
            cur.execute(
                "INSERT INTO devices (key_code, hwid, device_name, os_type, last_seen) VALUES (?, ?, ?, ?, ?)",
                (clean_key, hwid, device_name, os_type, now),
            )
        else:
            cur.execute(
                "UPDATE devices SET last_seen = ?, device_name = ? WHERE id = ?",
                (now, device_name, existing_dev["id"]),
            )
        conn.commit()
        conn.close()
        return True, "Ключ успешно активирован на устройстве!", get_key_status(clean_key)

    # Если ключ не передан, ищем ранее привязанный ключ по HWID
    dev_row = cur.execute(
        "SELECT key_code FROM devices WHERE hwid = ? ORDER BY last_seen DESC LIMIT 1", (hwid,)
    ).fetchone()
    if dev_row:
        existing_key = dev_row["key_code"]
        # Проверяем, существует ли этот ключ и активен ли
        row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (existing_key,)).fetchone()
        if row and row["is_active"]:
            cur.execute(
                "UPDATE devices SET last_seen = ? WHERE key_code = ? AND hwid = ?",
                (now, existing_key, hwid),
            )
            conn.commit()
            conn.close()
            return True, "Устройство авторизовано", get_key_status(existing_key)

    conn.close()
    return False, "Для входа требуется ввести промокод или ключ доступа.", None


def activate_device_with_promo(
    hwid: str,
    promo_code: str,
    device_name: str = "Windows PC",
    os_type: str = "windows",
) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    """
    Обязательный вход по промокоду:
    Проверяет валидность промокода, генерирует персональный ключ доступа с начисленными днями
    и привязывает устройство.
    """
    conn = get_conn()
    cur = conn.cursor()
    clean_promo = promo_code.strip().upper()
    now = int(time.time())

    promo_row = cur.execute("SELECT * FROM promo_codes WHERE code = ?", (clean_promo,)).fetchone()
    if not promo_row:
        conn.close()
        return False, f"Промокод «{clean_promo}» не найден. Проверьте правильность ввода.", None

    if promo_row["used_count"] >= promo_row["max_uses"]:
        conn.close()
        return False, "Лимит активаций этого промокода исчерпан.", None

    # Генерируем новый персональный ключ
    new_key = generate_key_code()
    expires_at = now + promo_row["days"] * 86400

    cur.execute(
        """
        INSERT INTO access_keys (key_code, plan_name, expires_at, max_devices, is_active, created_at, note)
        VALUES (?, ?, ?, ?, 1, ?, ?)
        """,
        (new_key, f"Промокод: {clean_promo} (+{promo_row['days']} дн.)", expires_at, 3, now, f"Promo: {clean_promo}"),
    )
    cur.execute(
        "UPDATE promo_codes SET used_count = used_count + 1 WHERE code = ?", (clean_promo,)
    )
    cur.execute(
        "INSERT INTO promo_usages (key_code, code, used_at) VALUES (?, ?, ?)",
        (new_key, clean_promo, now),
    )
    cur.execute(
        "INSERT INTO devices (key_code, hwid, device_name, os_type, last_seen) VALUES (?, ?, ?, ?, ?)",
        (new_key, hwid, device_name, os_type, now),
    )
    conn.commit()
    conn.close()
    return True, f"Промокод «{clean_promo}» успешно активирован! Доступ открыт на {promo_row['days']} дн.", get_key_status(new_key)


def get_key_status(key_code: str, is_admin: bool = False) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    clean_key = key_code.strip().upper()
    row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (clean_key,)).fetchone()
    if not row:
        conn.close()
        return None

    devs = cur.execute(
        "SELECT hwid, device_name, os_type, last_seen FROM devices WHERE key_code = ? ORDER BY last_seen DESC",
        (clean_key,),
    ).fetchall()
    conn.close()

    now = int(time.time())
    seconds_left = max(0, row["expires_at"] - now)
    is_valid = bool(row["is_active"]) and seconds_left > 0

    res = {
        "key_code": row["key_code"],
        "plan_name": row["plan_name"],
        "expires_at": row["expires_at"],
        "seconds_left": seconds_left,
        "days_left": round(seconds_left / 86400, 1),
        "max_devices": row["max_devices"],
        "devices_used": len(devs),
        "is_active": bool(row["is_active"]),
        "is_subscription_valid": is_valid,
    }
    if is_admin:
        res["devices"] = [dict(d) for d in devs]
        res["note"] = row["note"]
    return res


def apply_promo_code(key_code: str, promo_code: str) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    conn = get_conn()
    cur = conn.cursor()
    clean_key = key_code.strip().upper()
    clean_promo = promo_code.strip().upper()

    key_row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (clean_key,)).fetchone()
    if not key_row:
        conn.close()
        return False, "Ключ доступа не найден", None

    promo_row = cur.execute("SELECT * FROM promo_codes WHERE code = ?", (clean_promo,)).fetchone()
    if not promo_row:
        conn.close()
        return False, f"Промокод «{clean_promo}» не существует", None

    if promo_row["used_count"] >= promo_row["max_uses"]:
        conn.close()
        return False, "Лимит активаций этого промокода исчерпан", None

    already = cur.execute(
        "SELECT 1 FROM promo_usages WHERE key_code = ? AND code = ?", (clean_key, clean_promo)
    ).fetchone()
    if already:
        conn.close()
        return False, "Вы уже активировали этот промокод ранее", None

    now = int(time.time())
    base_time = max(now, key_row["expires_at"])
    new_expires = base_time + promo_row["days"] * 86400

    cur.execute(
        "UPDATE access_keys SET expires_at = ?, plan_name = ? WHERE key_code = ?",
        (new_expires, f"Промо ({clean_promo} +{promo_row['days']} дн.)", clean_key),
    )
    cur.execute(
        "UPDATE promo_codes SET used_count = used_count + 1 WHERE code = ?", (clean_promo,)
    )
    cur.execute(
        "INSERT INTO promo_usages (key_code, code, used_at) VALUES (?, ?, ?)",
        (clean_key, clean_promo, now),
    )
    conn.commit()
    conn.close()
    return (
        True,
        f"Промокод «{clean_promo}» активирован! Подписка продлена на {promo_row['days']} дн.",
        get_key_status(clean_key),
    )


def create_payment_order(key_code: str, plan_id: str) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    plan = PLANS.get(plan_id)
    if not plan or plan_id == "trial":
        return False, "Некорректный тариф", None

    conn = get_conn()
    cur = conn.cursor()
    clean_key = key_code.strip().upper()
    key_row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (clean_key,)).fetchone()
    if not key_row:
        conn.close()
        return False, "Ключ доступа не найден", None

    now = int(time.time())
    order_id = f"ORD-{int(now)}-{secrets.token_hex(3).upper()}"
    cur.execute(
        """
        INSERT INTO payments (order_id, key_code, plan_id, amount_rub, days, status, created_at)
        VALUES (?, ?, ?, ?, ?, 'pending', ?)
        """,
        (order_id, clean_key, plan_id, plan["price_rub"], plan["days"], now),
    )
    conn.commit()
    conn.close()

    return (
        True,
        "Счет на оплату создан",
        {
            "order_id": order_id,
            "key_code": clean_key,
            "plan": plan,
            "amount_rub": plan["price_rub"],
            "days": plan["days"],
            "status": "pending",
        },
    )


def confirm_payment_order(order_id: str, is_admin: bool = False) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    allow_demo = os.environ.get("ALLOW_DEMO_CONFIRM", "0") == "1"
    if not is_admin and not allow_demo:
        return False, "Неавторизованное подтверждение заказа запрещено политикой безопасности", None

    conn = get_conn()
    cur = conn.cursor()
    order = cur.execute("SELECT * FROM payments WHERE order_id = ?", (order_id,)).fetchone()
    if not order:
        conn.close()
        return False, "Заказ не найден", None

    if order["status"] == "paid":
        conn.close()
        return True, "Заказ уже был оплачен", get_key_status(order["key_code"])

    key_row = cur.execute("SELECT * FROM access_keys WHERE key_code = ?", (order["key_code"],)).fetchone()
    if not key_row:
        conn.close()
        return False, "Ключ не найден", None

    plan = PLANS.get(order["plan_id"], {"title": "Премиум подписка", "max_devices": 5})
    now = int(time.time())
    base_time = max(now, key_row["expires_at"])
    new_expires = base_time + order["days"] * 86400

    cur.execute("UPDATE payments SET status = 'paid' WHERE order_id = ?", (order_id,))
    cur.execute(
        "UPDATE access_keys SET expires_at = ?, plan_name = ?, max_devices = MAX(max_devices, ?) WHERE key_code = ?",
        (new_expires, plan["title"], plan["max_devices"], order["key_code"]),
    )
    conn.commit()
    conn.close()
    return (
        True,
        f"Оплата успешно подтверждена! Подписка продлена на {order['days']} дней.",
        get_key_status(order["key_code"]),
    )


def get_custom_nodes() -> List[str]:
    conn = get_conn()
    rows = conn.execute("SELECT vless_uri FROM custom_nodes WHERE is_active = 1 ORDER BY id DESC").fetchall()
    conn.close()
    return [r["vless_uri"] for r in rows]


def add_custom_node(vless_uri: str) -> bool:
    uri = vless_uri.strip()
    if not uri.startswith("vless://"):
        return False
    conn = get_conn()
    conn.execute(
        "INSERT INTO custom_nodes (vless_uri, is_active, added_at) VALUES (?, 1, ?)",
        (uri, int(time.time())),
    )
    conn.commit()
    conn.close()
    return True


def delete_access_key(key_code: str) -> bool:
    clean_key = key_code.strip()
    if not clean_key:
        return False
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("DELETE FROM access_keys WHERE key_code = ?", (clean_key,))
    cur.execute("DELETE FROM devices WHERE key_code = ?", (clean_key,))
    cur.execute("DELETE FROM promo_usages WHERE key_code = ?", (clean_key,))
    cur.execute("DELETE FROM payments WHERE key_code = ?", (clean_key,))
    conn.commit()
    conn.close()
    return True


def delete_promo_code(code: str) -> bool:
    clean_code = code.strip().upper()
    if not clean_code:
        return False
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("DELETE FROM promo_codes WHERE code = ?", (clean_code,))
    cur.execute("DELETE FROM promo_usages WHERE code = ?", (clean_code,))
    conn.commit()
    conn.close()
    return True


def delete_custom_node(node_id: int) -> bool:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("DELETE FROM custom_nodes WHERE id = ?", (node_id,))
    conn.commit()
    conn.close()
    return True


def clear_all_data() -> None:
    """Полная очистка всех тестовых ключей, промокодов, платежей и устройств."""
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("DELETE FROM access_keys")
    cur.execute("DELETE FROM devices")
    cur.execute("DELETE FROM promo_codes")
    cur.execute("DELETE FROM promo_usages")
    cur.execute("DELETE FROM payments")
    cur.execute("DELETE FROM custom_nodes")
    conn.commit()
    conn.close()


def list_all_admin_data() -> Dict[str, Any]:
    conn = get_conn()
    keys = [dict(r) for r in conn.execute("SELECT * FROM access_keys ORDER BY created_at DESC LIMIT 100").fetchall()]
    promos = [dict(r) for r in conn.execute("SELECT * FROM promo_codes ORDER BY created_at DESC").fetchall()]
    payments = [dict(r) for r in conn.execute("SELECT * FROM payments ORDER BY created_at DESC LIMIT 50").fetchall()]
    custom_nodes = [dict(r) for r in conn.execute("SELECT * FROM custom_nodes ORDER BY id DESC").fetchall()]
    conn.close()
    return {
        "keys": keys,
        "promos": promos,
        "payments": payments,
        "custom_nodes": custom_nodes,
    }
