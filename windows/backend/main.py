import base64
import html
import json
import os
import secrets
import sys

if sys.platform == "win32" and ("pythonw" in sys.executable.lower() or sys.stdout is None):
    try:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
    except Exception:
        pass

import threading
import time
import urllib.parse
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import Depends, FastAPI, Form, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel

from database import (
    PLANS,
    activate_device_with_promo,
    add_custom_node,
    apply_promo_code,
    confirm_payment_order,
    create_or_bind_device,
    create_payment_order,
    delete_access_key,
    delete_custom_node,
    delete_promo_code,
    clear_all_data,
    generate_key_code,
    get_conn,
    get_custom_nodes,
    get_key_status,
    init_db,
    list_all_admin_data,
)
from tunnel_manager import TUNNEL_MANAGER
from vless_compiler import (
    build_singbox_auto_config,
    build_xray_auto_config,
    fetch_raw_subscription_from_mirrors,
    parse_vless_uri,
    probe_nodes_concurrently,
)

# Глобальный кэш проверенных серверов в памяти
SERVER_POOL_LOCK = threading.Lock()
SERVER_POOL_STATE: Dict[str, Any] = {
    "nodes": [],
    "source_mirror": "",
    "last_sync": 0,
    "total_count": 0,
    "alive_count": 0,
    "foreign_count": 0,
    "ru_count": 0,
    "sync_in_progress": False,
}


def sync_and_probe_servers() -> Dict[str, Any]:
    """
    Скачивает свежие списки серверов из зеркал (zieng2/wl) + кастомные ноды из БД,
    парсит их и проверяет задержку (TCP Ping) в несколько потоков.
    """
    with SERVER_POOL_LOCK:
        if SERVER_POOL_STATE["sync_in_progress"]:
            return SERVER_POOL_STATE
        SERVER_POOL_STATE["sync_in_progress"] = True

    try:
        custom_uris = get_custom_nodes()
        mirror_uris, used_mirror = fetch_raw_subscription_from_mirrors(timeout=8.0)
        all_uris = custom_uris + mirror_uris

        parsed_nodes: List[Dict[str, Any]] = []
        seen_endpoints = set()
        for idx, uri in enumerate(all_uris):
            node = parse_vless_uri(uri, idx)
            if not node:
                continue
            # Дедупликация по (address, port, type, security, sni, path)
            dedup_key = (
                node["address"],
                node["port"],
                node["type"],
                node["security"],
                node["sni"],
                node["path"],
                node["remark"],
            )
            if dedup_key in seen_endpoints:
                continue
            seen_endpoints.add(dedup_key)
            parsed_nodes.append(node)

        probed_nodes = probe_nodes_concurrently(parsed_nodes, timeout=1.6, max_workers=25)
        alive_count = sum(1 for n in probed_nodes if n.get("latency_ms") is not None)
        foreign_count = sum(1 for n in probed_nodes if not n.get("is_ru", False))
        ru_count = sum(1 for n in probed_nodes if n.get("is_ru", False))

        with SERVER_POOL_LOCK:
            SERVER_POOL_STATE["nodes"] = probed_nodes
            SERVER_POOL_STATE["source_mirror"] = used_mirror or "Local/Custom"
            SERVER_POOL_STATE["last_sync"] = int(time.time())
            SERVER_POOL_STATE["total_count"] = len(probed_nodes)
            SERVER_POOL_STATE["alive_count"] = alive_count
            SERVER_POOL_STATE["foreign_count"] = foreign_count
            SERVER_POOL_STATE["ru_count"] = ru_count
            SERVER_POOL_STATE["sync_in_progress"] = False
    except Exception:
        with SERVER_POOL_LOCK:
            SERVER_POOL_STATE["sync_in_progress"] = False

    return SERVER_POOL_STATE


def background_sync_worker() -> None:
    """Фоновый поток: обновляет подписку и проверяет серверы каждые 15 минут."""
    while True:
        try:
            sync_and_probe_servers()
        except Exception:
            pass
        time.sleep(900)


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    t = threading.Thread(target=background_sync_worker, daemon=True)
    t.start()
    yield


app = FastAPI(
    title="PulseWL VPN Backend API",
    description="Автоматический коммерческий VPN-сервис с обходом белых списков (Android & Windows)",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Настройки безопасности и аутентификации администратора ---
security_basic = HTTPBasic(auto_error=False)
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "Volter2026")

ADMIN_ATTEMPTS_LOCK = threading.Lock()
ADMIN_ATTEMPTS: Dict[str, List[float]] = {}


def check_admin_rate_limit(request: Request, max_attempts: int = 5, window_sec: int = 60) -> None:
    client_ip = request.client.host if (request and request.client) else "unknown"
    now = time.time()
    with ADMIN_ATTEMPTS_LOCK:
        history = [t for t in ADMIN_ATTEMPTS.get(client_ip, []) if now - t < window_sec]
        if len(history) >= max_attempts:
            raise HTTPException(
                status_code=429,
                detail="Слишком много попыток входа в админ-панель. Подождите одну минуту.",
            )
        history.append(now)
        ADMIN_ATTEMPTS[client_ip] = history


def verify_admin_auth(
    request: Request,
    credentials: Optional[HTTPBasicCredentials] = Depends(security_basic)
) -> bool:
    """Серверная проверка аутентификации администратора с защитой от подбора пароля (Rate Limit)."""
    if not credentials:
        raise HTTPException(
            status_code=401,
            detail="Необходима авторизация администратора",
            headers={"WWW-Authenticate": "Basic realm='PulseVPN Admin'"},
        )
    check_admin_rate_limit(request)
    user_ok = secrets.compare_digest(credentials.username.encode("utf-8"), ADMIN_USERNAME.encode("utf-8"))
    pass_ok = secrets.compare_digest(credentials.password.encode("utf-8"), ADMIN_PASSWORD.encode("utf-8"))
    if not (user_ok and pass_ok):
        raise HTTPException(
            status_code=401,
            detail="Неверный логин или пароль администратора",
            headers={"WWW-Authenticate": "Basic realm='PulseVPN Admin'"},
        )
    return True


# --- Rate Limiter для защиты от подбора промокодов (Anti-Bruteforce) ---
PROMO_ATTEMPTS_LOCK = threading.Lock()
PROMO_ATTEMPTS: Dict[str, List[float]] = {}


def check_promo_rate_limit(request: Request, max_attempts: int = 5, window_sec: int = 60) -> None:
    client_ip = request.client.host if (request and request.client) else "unknown"
    now = time.time()
    with PROMO_ATTEMPTS_LOCK:
        history = [t for t in PROMO_ATTEMPTS.get(client_ip, []) if now - t < window_sec]
        if len(history) >= max_attempts:
            raise HTTPException(
                status_code=429,
                detail="Слишком много попыток ввода промокода. Подождите одну минуту.",
            )
        history.append(now)
        PROMO_ATTEMPTS[client_ip] = history


# --- Pydantic модели для API ---
class DeviceAuthRequest(BaseModel):
    hwid: str
    device_name: str = "Windows PC"
    os_type: str = "windows"
    key_code: Optional[str] = None


class PromoAuthRequest(BaseModel):
    hwid: str
    promo_code: str
    device_name: str = "Windows PC"
    os_type: str = "windows"


class PromoRequest(BaseModel):
    key_code: str
    promo_code: str


class CreateOrderRequest(BaseModel):
    key_code: str
    plan_id: str


class ConfirmOrderRequest(BaseModel):
    order_id: str


# --- Клиентские API эндпоинты ---
@app.get("/api/v1/health")
def api_health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "server_time": int(time.time()),
        "pool": {
            "total_nodes": SERVER_POOL_STATE["total_count"],
            "alive_nodes": SERVER_POOL_STATE["alive_count"],
            "foreign_nodes": SERVER_POOL_STATE["foreign_count"],
            "ru_whitelist_nodes": SERVER_POOL_STATE["ru_count"],
            "last_sync": SERVER_POOL_STATE["last_sync"],
            "source_mirror": SERVER_POOL_STATE["source_mirror"],
        },
    }


@app.post("/api/v1/auth/device")
def api_auth_device(req: DeviceAuthRequest) -> Dict[str, Any]:
    ok, msg, status = create_or_bind_device(
        hwid=req.hwid,
        device_name=req.device_name,
        os_type=req.os_type,
        key_code=req.key_code,
    )
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {
        "success": True,
        "message": msg,
        "subscription": status,
        "plans": list(PLANS.values()),
    }


@app.post("/api/v1/auth/promo")
def api_auth_promo(req: PromoAuthRequest, request: Request) -> Dict[str, Any]:
    """Вход и первичная регистрация устройства по промокоду с защитой от перебора"""
    check_promo_rate_limit(request)
    ok, msg, status = activate_device_with_promo(
        hwid=req.hwid,
        promo_code=req.promo_code,
        device_name=req.device_name,
        os_type=req.os_type,
    )
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {
        "success": True,
        "message": msg,
        "subscription": status,
        "plans": list(PLANS.values()),
    }


@app.get("/api/v1/user/status")
def api_user_status(key_code: str = Query(...)) -> Dict[str, Any]:
    status = get_key_status(key_code, is_admin=False)
    if not status:
        raise HTTPException(status_code=404, detail="Ключ доступа не найден")
    return {
        "success": True,
        "subscription": status,
        "pool": {
            "total_nodes": SERVER_POOL_STATE["total_count"],
            "alive_nodes": SERVER_POOL_STATE["alive_count"],
            "foreign_nodes": SERVER_POOL_STATE["foreign_count"],
            "ru_whitelist_nodes": SERVER_POOL_STATE["ru_count"],
            "last_sync": SERVER_POOL_STATE["last_sync"],
        },
        "plans": list(PLANS.values()),
    }


@app.post("/api/v1/billing/promo")
def api_activate_promo(req: PromoRequest, request: Request) -> Dict[str, Any]:
    check_promo_rate_limit(request)
    ok, msg, status = apply_promo_code(req.key_code, req.promo_code)
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {"success": True, "message": msg, "subscription": status}


@app.post("/api/v1/billing/create-order")
def api_create_order(req: CreateOrderRequest) -> Dict[str, Any]:
    ok, msg, order = create_payment_order(req.key_code, req.plan_id)
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {"success": True, "message": msg, "order": order}


@app.post("/api/v1/billing/confirm-demo")
def api_confirm_demo(req: ConfirmOrderRequest, _: bool = Depends(verify_admin_auth)) -> Dict[str, Any]:
    ok, msg, status = confirm_payment_order(req.order_id, is_admin=True)
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {"success": True, "message": msg, "subscription": status}


@app.get("/api/v1/vpn/nodes")
def api_list_nodes(key_code: str = Query(...), mode: str = Query("foreign_only")) -> Dict[str, Any]:
    status = get_key_status(key_code)
    if not status:
        raise HTTPException(status_code=404, detail="Ключ не найден")
    if not status["is_subscription_valid"]:
        raise HTTPException(status_code=403, detail="Подписка истекла. Оплатите тариф или введите промокод.")

    nodes = SERVER_POOL_STATE["nodes"]
    if mode == "foreign_only":
        filtered = [n for n in nodes if not n.get("is_ru", False)]
    else:
        filtered = list(nodes)

    # Не отдаем полные сырые ключи в обзорном списке, только метаданные и пинг
    safe_list = [
        {
            "id": n["id"],
            "remark": n["remark"],
            "type": n["type"].upper(),
            "security": n["security"].upper(),
            "is_ru": n["is_ru"],
            "latency_ms": n["latency_ms"],
        }
        for n in filtered
    ]
    return {"success": True, "count": len(safe_list), "nodes": safe_list}


@app.get("/api/v1/vpn/config")
def api_get_vpn_config(
    key_code: str = Query(...),
    hwid: str = Query(...),
    mode: str = Query("foreign_only"),
    engine: str = Query("xray"),
    socks_port: int = Query(10808),
    http_port: int = Query(10809),
    preferred_node_id: Optional[str] = Query(None),
) -> Dict[str, Any]:
    """
    Защищённый эндпоинт: проверяет оплату и привязку устройства (HWID),
    а затем возвращает полностью готовый JSON-конфиг для Xray-core или Sing-box,
    где уже включены:
    - Автовыбор лучшего сервера (URL-Test / leastPing каждые 30 сек);
    - allowInsecure=True для обхода белых списков;
    - Фильтр стран (только зарубежные для YouTube/Instagram или все релеи РФ).
    """
    ok, msg, status = create_or_bind_device(hwid=hwid, key_code=key_code)
    if not ok or not status:
        raise HTTPException(status_code=403, detail=msg)

    if not status["is_subscription_valid"]:
        raise HTTPException(
            status_code=402,
            detail="Срок действия подписки истёк. Пожалуйста, продлите подписку в приложении.",
        )

    nodes = SERVER_POOL_STATE["nodes"]
    if not nodes:
        sync_and_probe_servers()
        nodes = SERVER_POOL_STATE["nodes"]

    if engine == "singbox":
        cfg = build_singbox_auto_config(nodes=nodes, mode=mode, mixed_port=socks_port)
    else:
        cfg = build_xray_auto_config(
            nodes=nodes,
            mode=mode,
            socks_port=socks_port,
            http_port=http_port,
            preferred_node_id=preferred_node_id,
        )

    # Определяем лучший сервер по пингу для отображения в UI клиента
    candidates = [n for n in nodes if (not n.get("is_ru", False) if mode == "foreign_only" else True)]
    if preferred_node_id:
        pref = [n for n in candidates if n.get("id") == preferred_node_id]
        best_node = pref[0] if pref else (candidates[0] if candidates else None)
    else:
        best_node = candidates[0] if candidates else None

    return {
        "success": True,
        "engine": engine,
        "mode": mode,
        "best_node": {
            "id": best_node["id"],
            "remark": best_node["remark"],
            "latency_ms": best_node["latency_ms"],
            "type": best_node["type"].upper(),
            "security": best_node["security"].upper(),
        }
        if best_node
        else None,
        "active_pool_size": len(candidates),
        "config": cfg,
    }


@app.get("/sub/{key_code}", response_class=PlainTextResponse)
def personal_subscription_link(key_code: str, mode: str = Query("all")) -> PlainTextResponse:
    """
    Персональная ссылка-подписка для подключения в 1 клик в Karing / Happ / v2rayNG / Streisand.
    Если подписка истекла — отдаёт информационную строку с напоминанием об оплате.
    """
    status = get_key_status(key_code)
    if not status or not status["is_subscription_valid"]:
        expired_uri = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:443?security=none&type=tcp#" + urllib.parse.quote(
            "⛔ Подписка истекла — Оплатите в приложении"
        )
        b64 = base64.b64encode(expired_uri.encode("utf-8")).decode("utf-8")
        return PlainTextResponse(content=b64)

    nodes = SERVER_POOL_STATE["nodes"]
    if mode == "foreign":
        nodes = [n for n in nodes if not n.get("is_ru", False)]

    uris = [n["raw_uri"] for n in nodes]
    raw_text = "\n".join(uris)
    b64_payload = base64.b64encode(raw_text.encode("utf-8")).decode("utf-8")

    headers = {
        "Profile-Title": "base64:" + base64.b64encode("PulseWL Auto-VPN".encode("utf-8")).decode("utf-8"),
        "Profile-Update-Interval": "1",
        "Subscription-Userinfo": f"upload=0; download=0; total=1073741824000; expire={status['expires_at']}",
    }
    return PlainTextResponse(content=b64_payload, headers=headers)


def _resolve_cloud_config_path() -> str:
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data and getattr(sys, "frozen", False):
        app_dir = os.path.join(local_app_data, "PulseVPN")
        try:
            os.makedirs(app_dir, exist_ok=True)
            return os.path.join(app_dir, "cloud_config.json")
        except Exception:
            pass
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "cloud_config.json")


CLOUD_CONFIG_PATH = _resolve_cloud_config_path()


def get_saved_cloud_url() -> str:
    if os.path.exists(CLOUD_CONFIG_PATH):
        try:
            with open(CLOUD_CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f).get("remote_api_url", "").strip().rstrip("/")
        except Exception:
            pass
    return ""


class CloudConfigRequest(BaseModel):
    remote_api_url: str


class LocalConnectRequest(BaseModel):
    key_code: str
    hwid: str
    mode: str = "foreign_only"
    preferred_node_id: Optional[str] = None
    remote_api_url: Optional[str] = None


@app.get("/api/v1/local/cloud-config")
def api_get_cloud_config() -> Dict[str, Any]:
    return {"remote_api_url": get_saved_cloud_url()}


@app.post("/api/v1/local/cloud-config")
def api_set_cloud_config(req: CloudConfigRequest) -> Dict[str, Any]:
    clean_url = req.remote_api_url.strip().rstrip("/")
    with open(CLOUD_CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump({"remote_api_url": clean_url}, f, indent=2, ensure_ascii=False)
    return {"success": True, "remote_api_url": clean_url}




@app.get("/favicon.ico")
def serve_favicon():
    from fastapi.responses import FileResponse
    candidates = [
        os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows_client", "app_icon.ico")),
        os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_icon.ico")),
    ]
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(sys.executable)
        candidates.extend([
            os.path.join(exe_dir, "app_icon.ico"),
            os.path.join(exe_dir, "windows_client", "app_icon.ico"),
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "app_icon.ico"),
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "windows_client", "app_icon.ico"),
        ])
    for ico_path in candidates:
        if os.path.exists(ico_path):
            return FileResponse(ico_path, media_type="image/x-icon")
    raise HTTPException(status_code=404)


@app.get("/api/v1/local/state")
def api_local_tunnel_state() -> Dict[str, Any]:
    state = TUNNEL_MANAGER.get_state()
    state["remote_api_url"] = get_saved_cloud_url()
    return state


@app.post("/api/v1/local/connect")
def api_local_tunnel_connect(req: LocalConnectRequest) -> Dict[str, Any]:
    remote_url = (req.remote_api_url or get_saved_cloud_url()).strip()
    nodes = []
    if not remote_url:
        nodes = SERVER_POOL_STATE["nodes"]
        if not nodes:
            sync_and_probe_servers()
            nodes = SERVER_POOL_STATE["nodes"]
    try:
        return TUNNEL_MANAGER.start_tunnel(
            key_code=req.key_code,
            hwid=req.hwid,
            nodes=nodes,
            mode=req.mode,
            preferred_node_id=req.preferred_node_id,
            remote_api_url=remote_url or None,
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.post("/api/v1/local/disconnect")
def api_local_tunnel_disconnect() -> Dict[str, Any]:
    return TUNNEL_MANAGER.stop_tunnel()


def trigger_hf_cloud_sync_bg() -> None:
    def _worker():
        try:
            from hf_cloud_sync import get_hf_config, sync_to_huggingface_cloud

            cfg = get_hf_config()
            if cfg.get("token"):
                sync_to_huggingface_cloud(
                    token=cfg["token"],
                    nodes=SERVER_POOL_STATE["nodes"] or None,
                    source_mirror=SERVER_POOL_STATE["source_mirror"],
                )
        except Exception:
            pass

    threading.Thread(target=_worker, daemon=True).start()


# --- Веб Админ-Панель (/admin) и Клиентское Окно ПК (/app) ---
@app.get("/", response_class=RedirectResponse)
def root_redirect():
    return RedirectResponse(url="/app")


@app.get("/app", response_class=HTMLResponse)
def app_view():
    candidates = [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "client_ui.html"),
    ]
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(sys.executable)
        candidates.extend([
            os.path.join(exe_dir, "client_ui.html"),
            os.path.join(exe_dir, "backend", "client_ui.html"),
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "client_ui.html"),
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "backend", "client_ui.html"),
        ])
    for ui_path in candidates:
        if os.path.exists(ui_path):
            with open(ui_path, "r", encoding="utf-8") as f:
                return HTMLResponse(content=f.read())
    return HTMLResponse(content="<h1>Клиентский интерфейс не найден</h1>", status_code=404)


@app.get("/android", response_class=RedirectResponse)
def android_redirect():
    return RedirectResponse(url="/admin")


@app.post("/admin/action/sync")
def admin_trigger_sync(_: bool = Depends(verify_admin_auth)):
    def _sync_and_push():
        sync_and_probe_servers()
        trigger_hf_cloud_sync_bg()

    threading.Thread(target=_sync_and_push, daemon=True).start()
    time.sleep(0.3)
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/create-key")
def admin_create_key(
    days: int = Form(30),
    max_devices: int = Form(3),
    note: str = Form(""),
    _: bool = Depends(verify_admin_auth),
):
    conn = get_conn()
    new_key = generate_key_code()
    now = int(time.time())
    conn.execute(
        "INSERT INTO access_keys (key_code, plan_name, expires_at, max_devices, is_active, created_at, note) VALUES (?, ?, ?, ?, 1, ?, ?)",
        (new_key, f"Тариф ({days} дн.)", now + days * 86400, max_devices, now, note),
    )
    conn.commit()
    conn.close()
    trigger_hf_cloud_sync_bg()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/create-promo")
def admin_create_promo(
    code: str = Form(...),
    days: int = Form(7),
    max_uses: int = Form(100),
    _: bool = Depends(verify_admin_auth),
):
    conn = get_conn()
    clean_code = code.strip().upper()
    if clean_code:
        conn.execute(
            "INSERT OR REPLACE INTO promo_codes (code, days, max_uses, used_count, created_at) VALUES (?, ?, ?, 0, ?)",
            (clean_code, days, max_uses, int(time.time())),
        )
        conn.commit()
    conn.close()
    trigger_hf_cloud_sync_bg()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/add-node")
def admin_add_node(vless_uri: str = Form(...), _: bool = Depends(verify_admin_auth)):
    if add_custom_node(vless_uri):
        def _sync_and_push():
            sync_and_probe_servers()
            trigger_hf_cloud_sync_bg()

        threading.Thread(target=_sync_and_push, daemon=True).start()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/delete-key")
def admin_delete_key(key_code: str = Form(...), _: bool = Depends(verify_admin_auth)):
    delete_access_key(key_code)
    trigger_hf_cloud_sync_bg()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/delete-promo")
def admin_delete_promo(code: str = Form(...), _: bool = Depends(verify_admin_auth)):
    delete_promo_code(code)
    trigger_hf_cloud_sync_bg()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/delete-node")
def admin_delete_node(node_id: int = Form(...), _: bool = Depends(verify_admin_auth)):
    delete_custom_node(node_id)
    def _sync_and_push():
        sync_and_probe_servers()
        trigger_hf_cloud_sync_bg()

    threading.Thread(target=_sync_and_push, daemon=True).start()
    return RedirectResponse(url="/admin", status_code=303)


@app.post("/admin/action/clear-all")
def admin_clear_all(_: bool = Depends(verify_admin_auth)):
    clear_all_data()
    trigger_hf_cloud_sync_bg()
    return RedirectResponse(url="/admin", status_code=303)


@app.get("/admin", response_class=HTMLResponse)
def admin_dashboard(request: Request, _: bool = Depends(verify_admin_auth)) -> HTMLResponse:
    data = list_all_admin_data()
    pool = SERVER_POOL_STATE
    now = int(time.time())

    last_sync_str = (
        datetime.fromtimestamp(pool["last_sync"]).strftime("%H:%M:%S")
        if pool["last_sync"]
        else "СИНХРОНИЗАЦИЯ..."
    )

    keys_rows = ""
    for k in data["keys"]:
        rem_days = round(max(0, k["expires_at"] - now) / 86400, 1)
        status_badge = (
            f'<span style="color:#00F59B;font-weight:700;">АКТИВЕН // {rem_days} ДН.</span>'
            if rem_days > 0 and k["is_active"]
            else '<span style="color:#FF2E54;font-weight:700;">ИСТЁК</span>'
        )
        base_str = str(request.base_url)
        if request.headers.get("x-forwarded-proto") == "https" or ".hf.space" in base_str:
            base_str = base_str.replace("http://", "https://", 1)
        k_code_esc = html.escape(str(k['key_code']))
        k_plan_esc = html.escape(str(k['plan_name']))
        k_note_esc = html.escape(str(k['note'] or '—'))
        sub_url = f"{base_str}sub/{k_code_esc}"
        keys_rows += f"""
        <tr>
            <td><code style="background:#07080A;border:1px solid #232A3B;padding:4px 8px;border-radius:4px;color:#F0F4F8;font-size:13px;">{k_code_esc}</code></td>
            <td>{k_plan_esc}</td>
            <td>{status_badge}</td>
            <td>ДО {int(k['max_devices'])} УСТР.</td>
            <td>{k_note_esc}</td>
            <td><a href="{sub_url}" target="_blank" style="color:#FF5500;text-decoration:none;font-size:12px;font-weight:700;">[ССЫЛКА ПОДПИСКИ ↗]</a></td>
            <td>
                <form method="POST" action="/admin/action/delete-key" style="margin:0;" onsubmit="return confirm('Удалить ключ {k_code_esc}?');">
                    <input type="hidden" name="key_code" value="{k_code_esc}">
                    <button type="submit" style="background:#FF2E54;color:#fff;padding:4px 9px;font-size:11px;border-radius:3px;">🗑️ УДАЛИТЬ</button>
                </form>
            </td>
        </tr>
        """
    if not keys_rows:
        keys_rows = '<tr><td colspan="7" style="text-align:center;color:#4D576B;padding:18px;">Нет активных ключей доступа. Выпустите первый ключ сверху.</td></tr>'

    promo_rows = ""
    for p in data["promos"]:
        p_code_esc = html.escape(str(p['code']))
        promo_rows += f"""
        <tr>
            <td><code style="color:#FF5500;font-weight:700;">{p_code_esc}</code></td>
            <td>+{int(p['days'])} ДН.</td>
            <td>{int(p['used_count'])} / {int(p['max_uses'])}</td>
            <td>
                <form method="POST" action="/admin/action/delete-promo" style="margin:0;" onsubmit="return confirm('Удалить промокод {p_code_esc}?');">
                    <input type="hidden" name="code" value="{p_code_esc}">
                    <button type="submit" style="background:#FF2E54;color:#fff;padding:4px 9px;font-size:11px;border-radius:3px;">🗑️ УДАЛИТЬ</button>
                </form>
            </td>
        </tr>
        """
    if not promo_rows:
        promo_rows = '<tr><td colspan="4" style="text-align:center;color:#4D576B;padding:14px;">Нет добавленных промокодов. Добавьте промокод выше.</td></tr>'

    nodes_rows = ""
    for n in pool["nodes"][:39]:
        ping_badge = (
            f'<span style="color:#00F59B;font-weight:700;">{int(n["latency_ms"])} мс</span>'
            if n["latency_ms"] is not None
            else '<span style="color:#4D576B;">НЕДОСТУПЕН</span>'
        )
        cat_badge = (
            '<span style="background:rgba(255,85,0,0.12);border:1px solid rgba(255,85,0,0.4);color:#FF5500;padding:2px 7px;border-radius:3px;font-size:10px;">🇷🇺 БЕЛЫЙ СПИСОК РФ</span>'
            if n["is_ru"]
            else '<span style="background:rgba(0,245,155,0.1);border:1px solid rgba(0,245,155,0.3);color:#00F59B;padding:2px 7px;border-radius:3px;font-size:10px;">🌍 ЗАРУБЕЖНЫЙ ВЫХОД</span>'
        )
        n_remark_esc = html.escape(str(n['remark']))
        n_type_esc = html.escape(str(n['type']).upper())
        n_sec_esc = html.escape(str(n['security']).upper())
        nodes_rows += f"""
        <tr>
            <td>{n_remark_esc}</td>
            <td>{cat_badge}</td>
            <td><code style="color:#8A96AD;">{n_type_esc} // {n_sec_esc}</code></td>
            <td>{ping_badge}</td>
        </tr>
        """

    html_page = f"""
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="UTF-8">
        <title>ПУЛЬС // ВПН — ПАНЕЛЬ УПРАВЛЕНИЯ СЕРВЕРОМ</title>
        <link rel="preconnect" href="https://fonts.googleapis.com">
        <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
        <link href="https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@600;700&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
        <style>
            :root {{
                --bg-void: #07080A;
                --bg-chassis: #0D1017;
                --border-edge: #232A3B;
                --text-primary: #F0F4F8;
                --text-secondary: #8A96AD;
                --accent-amber: #FF5500;
                --accent-emerald: #00F59B;
            }}
            * {{ box-sizing: border-box; font-family: 'JetBrains Mono', monospace; }}
            body {{ margin: 0; background: var(--bg-void); color: var(--text-primary); padding: 28px; }}
            .container {{ max-width: 1220px; margin: 0 auto; }}
            .header {{ display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; border-bottom: 1px solid var(--border-edge); padding-bottom: 16px; }}
            h2, h3 {{ font-family: 'Chakra Petch', sans-serif; letter-spacing: 0.08em; text-transform: uppercase; margin-top: 0; color: var(--text-primary); }}
            .stats {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 24px; }}
            .stat-card {{ background: var(--bg-chassis); border: 1px solid var(--border-edge); padding: 16px; border-radius: 6px; }}
            .stat-card .val {{ font-family: 'Chakra Petch', sans-serif; font-size: 30px; font-weight: 700; color: var(--text-primary); margin-top: 6px; font-variant-numeric: tabular-nums; }}
            .grid-2 {{ display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 20px; }}
            .card {{ background: var(--bg-chassis); border: 1px solid var(--border-edge); padding: 18px; border-radius: 6px; margin-bottom: 18px; }}
            table {{ width: 100%; border-collapse: collapse; font-size: 12px; font-variant-numeric: tabular-nums; }}
            th, td {{ text-align: left; padding: 9px 8px; border-bottom: 1px solid #181E2C; }}
            th {{ color: var(--text-secondary); font-weight: 600; text-transform: uppercase; font-size: 10px; letter-spacing: 0.1em; }}
            input, select, button {{ padding: 8px 12px; border-radius: 4px; border: 1px solid var(--border-edge); background: var(--bg-void); color: #fff; font-size: 12px; }}
            button {{ background: var(--accent-amber); color: #07080A; border: none; cursor: pointer; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; }}
            button:hover {{ filter: brightness(1.12); }}
            .form-row {{ display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <div>
                    <h2 style="margin-bottom:4px;">🛡️ ПУЛЬС // ВПН — ПАНЕЛЬ УПРАВЛЕНИЯ СЕРВЕРОМ</h2>
                    <div style="color:var(--text-secondary);font-size:11px;">ИСТОЧНИК ЗЕРКАЛ: <code>{pool['source_mirror'] or 'zieng2/wl'}</code> // ОБНОВЛЕНО: {last_sync_str}</div>
                </div>
                <div style="display:flex;gap:10px;align-items:center;">
                    <form method="POST" action="/admin/action/sync" style="margin:0;">
                        <button type="submit">🔄 ОБНОВИТЬ СЕРВЕРЫ ({last_sync_str})</button>
                    </form>
                    <form method="POST" action="/admin/action/clear-all" style="margin:0;" onsubmit="return confirm('ВНИМАНИЕ! Это действие удалит ВСЕ тестовые ключи, устройства, промокоды и платежи. База станет полностью чистой. Продолжить?');">
                        <button type="submit" style="background:rgba(255,46,84,0.18);border:1px solid #FF2E54;color:#FF2E54;">🗑️ ОЧИСТИТЬ ВСЁ</button>
                    </form>
                </div>
            </div>

            <div class="stats">
                <div class="stat-card">
                    <div style="color:var(--text-secondary);font-size:10px;letter-spacing:0.1em;">01 // ВСЕГО СЕРВЕРОВ В ПУЛЕ</div>
                    <div class="val">{pool['total_count']}</div>
                </div>
                <div class="stat-card">
                    <div style="color:var(--text-secondary);font-size:10px;letter-spacing:0.1em;">02 // ДОСТУПНЫ СЕЙЧАС (ОНЛАЙН)</div>
                    <div class="val" style="color:var(--accent-emerald);">{pool['alive_count']}</div>
                </div>
                <div class="stat-card">
                    <div style="color:var(--text-secondary);font-size:10px;letter-spacing:0.1em;">03 // 🌍 ЗАРУБЕЖНЫЕ УЗЛЫ</div>
                    <div class="val">{pool['foreign_count']}</div>
                </div>
                <div class="stat-card">
                    <div style="color:var(--text-secondary);font-size:10px;letter-spacing:0.1em;">04 // 🇷🇺 БЕЛЫЕ СПИСКИ РФ</div>
                    <div class="val" style="color:var(--accent-amber);">{pool['ru_count']}</div>
                </div>
            </div>

            <div class="grid-2">
                <div class="card">
                    <h3>🔑 ВЫПУСТИТЬ НОВЫЙ КЛЮЧ ДОСТУПА</h3>
                    <form method="POST" action="/admin/action/create-key" class="form-row">
                        <input type="number" name="days" value="30" min="1" style="width:85px;" placeholder="Дней">
                        <input type="number" name="max_devices" value="3" min="1" style="width:85px;" placeholder="Устр-в">
                        <input type="text" name="note" placeholder="Заметка (напр. Telegram @user)" style="flex:1;">
                        <button type="submit">+ СОЗДАТЬ</button>
                    </form>
                    <p style="font-size:11px;color:var(--text-secondary);margin:0;">Ключ сразу работает в Windows и Android-приложении.</p>
                </div>

                <div class="card">
                    <h3>🎁 ПРОМОКОДЫ</h3>
                    <form method="POST" action="/admin/action/create-promo" class="form-row">
                        <input type="text" name="code" placeholder="КОД (SALE30)" required style="width:130px;">
                        <input type="number" name="days" value="7" min="1" style="width:80px;" placeholder="Дней">
                        <input type="number" name="max_uses" value="100" min="1" style="width:80px;" placeholder="Лимит">
                        <button type="submit">+ ДОБАВИТЬ</button>
                    </form>
                    <table>
                        <thead><tr><th>КОД</th><th>БОНУС</th><th>ИСПОЛЬЗОВАНО</th><th>ДЕЙСТВИЕ</th></tr></thead>
                        <tbody>{promo_rows}</tbody>
                    </table>
                </div>
            </div>

            <div class="card">
                <h3>👥 КЛЮЧИ КЛИЕНТОВ И ПОДПИСКИ</h3>
                <table>
                    <thead>
                        <tr><th>КЛЮЧ ДОСТУПА</th><th>ТАРИФ</th><th>СТАТУС</th><th>ЛИМИТ УСТР.</th><th>ЗАМЕТКА</th><th>ССЫЛКА ДЛЯ KARING / HAPP</th><th>ДЕЙСТВИЕ</th></tr>
                    </thead>
                    <tbody>{keys_rows}</tbody>
                </table>
            </div>

            <div class="card">
                <h3>➕ ДОБАВИТЬ СВОЙ ПРИВАТНЫЙ VLESS-СЕРВЕР</h3>
                <form method="POST" action="/admin/action/add-node" class="form-row">
                    <input type="text" name="vless_uri" placeholder="vless://uuid@ip:port?security=reality&sni=ya.ru...#Мой Сервер" style="flex:1;" required>
                    <button type="submit">+ ДОБАВИТЬ УЗЕЛ</button>
                </form>
            </div>

            <div class="card">
                <h3>⚡ ЖИВАЯ ТЕЛЕМЕТРИЯ // ПУЛ СЕРВЕРОВ (СОРТИРОВКА ПО ПИНГУ)</h3>
                <table>
                    <thead>
                        <tr><th>УЗЕЛ</th><th>КАТЕГОРИЯ</th><th>ТРАНСПОРТ</th><th>ЗАДЕРЖКА</th></tr>
                    </thead>
                    <tbody>{nodes_rows}</tbody>
                </table>
            </div>
        </div>
    </body>
    </html>
    """
    return HTMLResponse(content=html_page)


if __name__ == "__main__":
    import os
    import sys
    import uvicorn

    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8000"))
    uvicorn.run(app, host=host, port=port, log_config=None)

