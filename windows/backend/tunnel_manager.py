import atexit
import ctypes
import json
import os
import platform
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from typing import Any, Dict, Optional

if sys.platform == "win32":
    import winreg

from database import create_or_bind_device
from vless_compiler import build_xray_auto_config, is_safe_public_host

def _find_bin_dir() -> str:
    candidates = []
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(sys.executable)
        candidates.extend([
            os.path.join(exe_dir, "bin"),
            os.path.join(exe_dir, "windows_client", "bin"),
            exe_dir,
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "bin"),
            os.path.join(getattr(sys, "_MEIPASS", exe_dir), "windows_client", "bin"),
        ])
    base_dir = os.path.dirname(os.path.abspath(__file__))
    candidates.extend([
        os.path.abspath(os.path.join(base_dir, "..", "windows_client", "bin")),
        os.path.abspath(os.path.join(base_dir, "bin")),
        os.path.abspath(os.path.join(base_dir, "windows_client", "bin")),
    ])
    for c in candidates:
        if os.path.exists(os.path.join(c, "xray.exe")):
            return c
    return candidates[0]

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
BIN_DIR = _find_bin_dir()
XRAY_EXE = os.path.join(BIN_DIR, "xray.exe")
RUNTIME_CONFIG_PATH = os.path.join(BIN_DIR, "runtime_config.json")


def is_safe_remote_url(url: str) -> bool:
    """Проверяет безопасность URL удаленного сервера для предотвращения SSRF."""
    if not url:
        return False
    try:
        parsed = urllib.parse.urlsplit(url.strip())
        if parsed.scheme.lower() not in ("https", "http"):
            return False
        host = parsed.hostname
        if not host:
            return False
        return is_safe_public_host(host)
    except Exception:
        return False


XRAY_DOWNLOAD_URL = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip"
HTTP_PROXY_PORT = 10809
SOCKS_PROXY_PORT = 10808


class LocalTunnelManager:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.xray_proc: Optional[subprocess.Popen] = None
        self.is_connected: bool = False
        self.is_connecting: bool = False
        self.status_step: str = "СИСТЕМА ГОТОВА // ОЖИДАНИЕ ЗАПУСКА"
        self.active_mode: str = "foreign_only"
        self.active_node: Optional[Dict[str, Any]] = None
        self.active_pool_size: int = 0
        self.connected_since: Optional[int] = None
        self.last_error: Optional[str] = None
        atexit.register(self.stop_tunnel)

    def set_windows_proxy(self, enable: bool) -> None:
        if sys.platform != "win32":
            return
        try:
            key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
                0,
                winreg.KEY_ALL_ACCESS,
            )
            if enable:
                winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
                winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, f"127.0.0.1:{HTTP_PROXY_PORT}")
                winreg.SetValueEx(
                    key,
                    "ProxyOverride",
                    0,
                    winreg.REG_SZ,
                    "localhost;127.*;10.*;172.16.*;192.168.*;<local>",
                )
            else:
                winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 0)
            winreg.CloseKey(key)

            internet_set_option = ctypes.windll.Wininet.InternetSetOptionW
            internet_set_option(0, 39, 0, 0)
            internet_set_option(0, 37, 0, 0)
        except Exception:
            pass

    def ensure_xray_binary(self) -> bool:
        if os.path.exists(XRAY_EXE):
            return True
        os.makedirs(BIN_DIR, exist_ok=True)
        zip_path = os.path.join(BIN_DIR, "xray.zip")
        self.status_step = "СКАЧИВАНИЕ ЯДРА XRAY-CORE (ПЕРВЫЙ ЗАПУСК)..."
        try:
            req = urllib.request.Request(XRAY_DOWNLOAD_URL, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as resp, open(zip_path, "wb") as out_f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    out_f.write(chunk)
            with zipfile.ZipFile(zip_path, "r") as zf:
                target_base = os.path.abspath(BIN_DIR)
                for member in zf.namelist():
                    member_path = os.path.abspath(os.path.join(target_base, member))
                    if not (member_path == target_base or member_path.startswith(target_base + os.sep)):
                        raise RuntimeError(f"Zip traversal detected: {member}")
                zf.extractall(BIN_DIR)
            try:
                os.remove(zip_path)
            except Exception:
                pass
            return os.path.exists(XRAY_EXE)
        except Exception as e:
            self.last_error = f"Не удалось скачать ядро Xray-core: {e}"
            return False

    def start_tunnel(
        self,
        key_code: str,
        hwid: str,
        nodes: list,
        mode: str = "foreign_only",
        preferred_node_id: Optional[str] = None,
        remote_api_url: Optional[str] = None,
    ) -> Dict[str, Any]:
        with self.lock:
            self.is_connecting = True
            self.last_error = None
            self.status_step = "ПРОВЕРКА КЛЮЧА ДОСТУПА И УСТРОЙСТВА..."

        try:
            best = None
            candidates_count = 0

            if remote_api_url and remote_api_url.strip():
                if not is_safe_remote_url(remote_api_url):
                    raise ValueError("Недопустимый адрес удаленного сервера (SSRF защита)")
                import urllib.error
                import urllib.parse

                base_remote = remote_api_url.strip().rstrip("/")
                self.status_step = "СИНХРОНИЗАЦИЯ С ОБЛАКОМ HUGGING FACE SPACES..."

                # Сначала пробуем облачный реестр Hugging Face (/api/cloud_state.json)
                cloud_state_url = f"{base_remote}/api/cloud_state.json?t={int(time.time())}"
                req_cs = urllib.request.Request(cloud_state_url, headers={"User-Agent": "PulseWL-Client/1.0"})
                try:
                    with urllib.request.urlopen(req_cs, timeout=15) as resp:
                        cs_data = json.loads(resp.read().decode("utf-8"))
                    from vless_compiler import parse_vless_uri

                    cloud_nodes = []
                    for idx, item in enumerate(cs_data.get("nodes", [])):
                        parsed = parse_vless_uri(item.get("raw_uri", ""), idx)
                        if parsed:
                            parsed["latency_ms"] = item.get("latency_ms")
                            cloud_nodes.append(parsed)
                    if not cloud_nodes:
                        cloud_nodes = nodes

                    cfg = build_xray_auto_config(
                        nodes=cloud_nodes,
                        mode=mode,
                        socks_port=SOCKS_PROXY_PORT,
                        http_port=HTTP_PROXY_PORT,
                        preferred_node_id=preferred_node_id,
                    )
                    candidates = [
                        n for n in cloud_nodes if (not n.get("is_ru", False) if mode == "foreign_only" else True)
                    ]
                    candidates_count = len(candidates)
                    if preferred_node_id:
                        pref = [n for n in candidates if n.get("id") == preferred_node_id]
                        best = pref[0] if pref else (candidates[0] if candidates else None)
                    else:
                        best = candidates[0] if candidates else None
                except Exception:
                    # Фоллбэк на динамический FastAPI /api/v1/vpn/config
                    params = {
                        "key_code": key_code,
                        "hwid": hwid,
                        "mode": mode,
                        "engine": "xray",
                        "socks_port": str(SOCKS_PROXY_PORT),
                        "http_port": str(HTTP_PROXY_PORT),
                    }
                    if preferred_node_id:
                        params["preferred_node_id"] = preferred_node_id
                    url = f"{base_remote}/api/v1/vpn/config?{urllib.parse.urlencode(params)}"
                    req = urllib.request.Request(url, headers={"User-Agent": "PulseWL-Client/1.0"})
                    with urllib.request.urlopen(req, timeout=20) as resp:
                        remote_data = json.loads(resp.read().decode("utf-8"))
                    cfg = remote_data["config"]
                    best = remote_data.get("best_node")
                    candidates_count = remote_data.get("active_pool_size", 0)
            else:
                ok, msg, sub = create_or_bind_device(
                    hwid=hwid,
                    device_name=f"Windows ({platform.node()})",
                    os_type="windows",
                    key_code=key_code,
                )
                if not ok or not sub:
                    raise RuntimeError(msg)
                if not sub["is_subscription_valid"]:
                    raise RuntimeError("Срок подписки истёк. Активируйте промокод или оплатите тариф.")

                self.status_step = "СБОРКА АВТО-БАЛАНСИРОВЩИКА И ОБХОДА БЕЛЫХ СПИСКОВ..."
                cfg = build_xray_auto_config(
                    nodes=nodes,
                    mode=mode,
                    socks_port=SOCKS_PROXY_PORT,
                    http_port=HTTP_PROXY_PORT,
                    preferred_node_id=preferred_node_id,
                )
                candidates = [n for n in nodes if (not n.get("is_ru", False) if mode == "foreign_only" else True)]
                candidates_count = len(candidates)
                if preferred_node_id:
                    pref = [n for n in candidates if n.get("id") == preferred_node_id]
                    best = pref[0] if pref else (candidates[0] if candidates else None)
                else:
                    best = candidates[0] if candidates else None

            if not self.ensure_xray_binary():
                raise RuntimeError(self.last_error or "Ошибка загрузки Xray-core")

            os.makedirs(BIN_DIR, exist_ok=True)
            with open(RUNTIME_CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(cfg, f, indent=2, ensure_ascii=False)

            self.stop_tunnel()
            self.status_step = "ЗАПУСК ЗАШИФРОВАННОГО ТУННЕЛЯ..."

            creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            self.xray_proc = subprocess.Popen(
                [XRAY_EXE, "run", "-c", RUNTIME_CONFIG_PATH],
                cwd=BIN_DIR,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                creationflags=creationflags,
            )

            time.sleep(0.75)
            if self.xray_proc.poll() is not None:
                err_txt = self.xray_proc.stderr.read().decode("utf-8", errors="ignore") if self.xray_proc.stderr else ""
                raise RuntimeError(f"Ошибка запуска Xray: {err_txt[:250]}")

            self.set_windows_proxy(True)

            with self.lock:
                self.is_connected = True
                self.is_connecting = False
                self.active_mode = mode
                self.active_pool_size = candidates_count
                self.connected_since = int(time.time())
                self.status_step = f"ТУННЕЛЬ АКТИВЕН // ПРОКСИ 127.0.0.1:{HTTP_PROXY_PORT}"
                self.active_node = (
                    {
                        "id": best["id"],
                        "remark": best["remark"],
                        "latency_ms": best["latency_ms"],
                        "type": best["type"].upper(),
                        "security": best["security"].upper(),
                    }
                    if best
                    else None
                )
            return self.get_state()

        except Exception as e:
            self.stop_tunnel()
            with self.lock:
                self.is_connecting = False
                self.is_connected = False
                self.last_error = str(e)
                self.status_step = "ОШИБКА // ТУННЕЛЬ ОСТАНОВЛЕН"
            raise RuntimeError(str(e))

    def stop_tunnel(self) -> Dict[str, Any]:
        self.set_windows_proxy(False)
        if self.xray_proc and self.xray_proc.poll() is None:
            try:
                self.xray_proc.terminate()
                self.xray_proc.wait(timeout=2)
            except Exception:
                try:
                    self.xray_proc.kill()
                except Exception:
                    pass
        self.xray_proc = None
        with self.lock:
            self.is_connected = False
            self.is_connecting = False
            self.connected_since = None
            self.status_step = "СИСТЕМА ГОТОВА // ОЖИДАНИЕ ЗАПУСКА"
        return self.get_state()

    def get_state(self) -> Dict[str, Any]:
        alive_proc = self.xray_proc is not None and self.xray_proc.poll() is None
        if self.is_connected and not alive_proc:
            self.is_connected = False
            self.status_step = "СИСТЕМА ГОТОВА // ОЖИДАНИЕ ЗАПУСКА"
            self.set_windows_proxy(False)

        uptime = (int(time.time()) - self.connected_since) if (self.is_connected and self.connected_since) else 0
        return {
            "is_connected": self.is_connected,
            "is_connecting": self.is_connecting,
            "status_step": self.status_step,
            "active_mode": self.active_mode,
            "active_node": self.active_node,
            "active_pool_size": self.active_pool_size,
            "uptime_seconds": uptime,
            "http_proxy": f"127.0.0.1:{HTTP_PROXY_PORT}",
            "socks_proxy": f"127.0.0.1:{SOCKS_PROXY_PORT}",
            "last_error": self.last_error,
        }


TUNNEL_MANAGER = LocalTunnelManager()
