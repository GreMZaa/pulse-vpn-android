import atexit
import ctypes
import hashlib
import json
import os
import platform
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
import winreg
import zipfile
from typing import Any, Dict, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
BIN_DIR = os.path.join(BASE_DIR, "bin")
XRAY_EXE = os.path.join(BIN_DIR, "xray.exe")
RUNTIME_CONFIG_PATH = os.path.join(BIN_DIR, "runtime_config.json")
STATE_FILE = os.path.join(BASE_DIR, "client_state.json")

API_BASE_URL = os.environ.get("VPN_API_URL", "http://127.0.0.1:8000")
HTTP_PROXY_PORT = 10809
SOCKS_PROXY_PORT = 10808

XRAY_DOWNLOAD_URL = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip"


def get_machine_hwid() -> str:
    """Генерирует стабильный HWID текущего компьютера."""
    raw = f"{platform.node()}-{platform.machine()}-{uuid.getnode()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24].upper()


def load_saved_key() -> Optional[str]:
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("key_code")
        except Exception:
            pass
    return None


def save_key_code(key_code: str) -> None:
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump({"key_code": key_code}, f, indent=2)
    except Exception:
        pass


def set_windows_system_proxy(enable: bool, proxy_host: str = "127.0.0.1", proxy_port: int = HTTP_PROXY_PORT) -> None:
    """Включает или выключает системный прокси Windows и уведомляет WinINet."""
    try:
        internet_settings = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
            0,
            winreg.KEY_ALL_ACCESS,
        )
        if enable:
            winreg.SetValueEx(internet_settings, "ProxyEnable", 0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(internet_settings, "ProxyServer", 0, winreg.REG_SZ, f"{proxy_host}:{proxy_port}")
            winreg.SetValueEx(
                internet_settings,
                "ProxyOverride",
                0,
                winreg.REG_SZ,
                "localhost;127.*;10.*;172.16.*;192.168.*;<local>",
            )
        else:
            winreg.SetValueEx(internet_settings, "ProxyEnable", 0, winreg.REG_DWORD, 0)
        winreg.CloseKey(internet_settings)

        # Уведомляем браузеры и систему об изменении настроек прокси
        internet_set_option = ctypes.windll.Wininet.InternetSetOptionW
        internet_set_option(0, 39, 0, 0)  # INTERNET_OPTION_SETTINGS_CHANGED
        internet_set_option(0, 37, 0, 0)  # INTERNET_OPTION_REFRESH
    except Exception:
        pass


def api_request(path: str, method: str = "GET", payload: Optional[Dict[str, Any]] = None, timeout: float = 8.0) -> Dict[str, Any]:
    url = f"{API_BASE_URL}{path}"
    data_bytes = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data_bytes = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read().decode("utf-8"))
            detail = err_body.get("detail", str(e))
        except Exception:
            detail = str(e)
        raise RuntimeError(detail)
    except Exception as e:
        raise RuntimeError(f"Ошибка связи с сервером ({API_BASE_URL}): {e}")


class PulseVPNWindow:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("PulseWL VPN — Авто-обход Белых Списков")
        self.root.geometry("460x700")
        self.root.configure(bg="#0b0f19")
        self.root.resizable(False, False)
        ico_file = os.path.join(BASE_DIR, "app_icon.ico")
        if os.path.exists(ico_file):
            try:
                self.root.iconbitmap(ico_file)
            except Exception:
                pass

        self.hwid = get_machine_hwid()
        self.key_code: Optional[str] = load_saved_key()
        self.subscription: Dict[str, Any] = {}
        self.pool_info: Dict[str, Any] = {}
        self.plans: list = []

        self.is_connected = False
        self.is_connecting = False
        self.xray_proc: Optional[subprocess.Popen] = None
        self.backend_proc: Optional[subprocess.Popen] = None
        self.selected_mode = tk.StringVar(value="foreign_only")
        self.preferred_node_id: Optional[str] = None
        self.best_node_label_text = tk.StringVar(value="Автовыбор самого быстрого сервера")

        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        atexit.register(self.cleanup_tunnel)

        # Запускаем инициализацию и проверку локального бэкенда в фоне
        threading.Thread(target=self._bootstrap_connection, daemon=True).start()

    def _build_ui(self) -> None:
        # Верхняя шапка
        header = tk.Frame(self.root, bg="#0b0f19", pady=14, padx=20)
        header.pack(fill="x")

        title_lbl = tk.Label(
            header,
            text="🛡️ PulseWL VPN",
            font=("Segoe UI", 17, "bold"),
            fg="#f8fafc",
            bg="#0b0f19",
        )
        title_lbl.pack(side="left")

        admin_btn = tk.Button(
            header,
            text="⚙️ Админка",
            font=("Segoe UI", 9, "bold"),
            fg="#93c5fd",
            bg="#1e293b",
            activebackground="#334155",
            activeforeground="#ffffff",
            bd=0,
            padx=10,
            pady=5,
            cursor="hand2",
            command=lambda: webbrowser.open(f"{API_BASE_URL}/admin"),
        )
        admin_btn.pack(side="right")

        # Карточка ключа и подписки
        sub_card = tk.Frame(self.root, bg="#111827", highlightbackground="#1f2937", highlightthickness=1, padx=16, pady=12)
        sub_card.pack(fill="x", padx=20, pady=(0, 12))

        top_sub_row = tk.Frame(sub_card, bg="#111827")
        top_sub_row.pack(fill="x")

        tk.Label(top_sub_row, text="ВАШ КЛЮЧ ДОСТУПА:", font=("Segoe UI", 8, "bold"), fg="#94a3b8", bg="#111827").pack(side="left")
        self.status_badge = tk.Label(
            top_sub_row,
            text="Проверка...",
            font=("Segoe UI", 9, "bold"),
            fg="#fbbf24",
            bg="#111827",
        )
        self.status_badge.pack(side="right")

        key_row = tk.Frame(sub_card, bg="#111827", pady=6)
        key_row.pack(fill="x")

        self.key_label = tk.Label(
            key_row,
            text="XXXX-XXXX-XXXX-XXXX",
            font=("Consolas", 13, "bold"),
            fg="#38bdf8",
            bg="#0f172a",
            padx=10,
            pady=4,
        )
        self.key_label.pack(side="left", fill="x", expand=True)

        tk.Button(
            key_row,
            text="📋",
            font=("Segoe UI", 10),
            fg="#e2e8f0",
            bg="#1e293b",
            bd=0,
            padx=8,
            pady=3,
            cursor="hand2",
            command=self.copy_key,
        ).pack(side="left", padx=(6, 0))

        tk.Button(
            key_row,
            text="Сменить",
            font=("Segoe UI", 9),
            fg="#e2e8f0",
            bg="#1e293b",
            bd=0,
            padx=8,
            pady=4,
            cursor="hand2",
            command=self.change_key_dialog,
        ).pack(side="left", padx=(6, 0))

        bot_sub_row = tk.Frame(sub_card, bg="#111827", pady=2)
        bot_sub_row.pack(fill="x")

        self.plan_info_lbl = tk.Label(
            bot_sub_row,
            text="Тариф: Загрузка...",
            font=("Segoe UI", 9),
            fg="#cbd5e1",
            bg="#111827",
        )
        self.plan_info_lbl.pack(side="left")

        tk.Button(
            bot_sub_row,
            text="💎 Оплатить / Промокод",
            font=("Segoe UI", 9, "bold"),
            fg="#ffffff",
            bg="#2563eb",
            activebackground="#1d4ed8",
            activeforeground="#ffffff",
            bd=0,
            padx=10,
            pady=4,
            cursor="hand2",
            command=self.open_billing_modal,
        ).pack(side="right")

        # Центральная круглая кнопка подключения
        center_frame = tk.Frame(self.root, bg="#0b0f19", pady=8)
        center_frame.pack(fill="x")

        self.canvas = tk.Canvas(center_frame, width=210, height=210, bg="#0b0f19", highlightthickness=0, cursor="hand2")
        self.canvas.pack()
        self.outer_ring = self.canvas.create_oval(12, 12, 198, 198, outline="#1e293b", width=6, fill="#111827")
        self.inner_circle = self.canvas.create_oval(28, 28, 182, 182, outline="#334155", width=2, fill="#0f172a")
        self.power_icon = self.canvas.create_text(105, 88, text="⏻", font=("Segoe UI Symbol", 42, "bold"), fill="#64748b")
        self.btn_status_text = self.canvas.create_text(105, 138, text="ПОДКЛЮЧИТЬ", font=("Segoe UI", 11, "bold"), fill="#e2e8f0")
        self.canvas.bind("<Button-1>", lambda e: self.toggle_vpn())

        self.conn_state_lbl = tk.Label(
            center_frame,
            text="Туннель отключен • Автоматический режим готов",
            font=("Segoe UI", 10),
            fg="#94a3b8",
            bg="#0b0f19",
        )
        self.conn_state_lbl.pack(pady=(6, 0))

        # Карточка выбора режима (заменяет ручную фильтрацию из видео)
        mode_card = tk.Frame(self.root, bg="#111827", highlightbackground="#1f2937", highlightthickness=1, padx=16, pady=12)
        mode_card.pack(fill="x", padx=20, pady=8)

        tk.Label(
            mode_card,
            text="РЕЖИМ АВТОМАТИЧЕСКОЙ МАРШРУТИЗАЦИИ:",
            font=("Segoe UI", 8, "bold"),
            fg="#94a3b8",
            bg="#111827",
        ).pack(anchor="w", pady=(0, 6))

        rb1 = tk.Radiobutton(
            mode_card,
            text="🌍 Зарубеж + Автовыбор (YouTube, Instagram, Discord)",
            variable=self.selected_mode,
            value="foreign_only",
            font=("Segoe UI", 9, "bold"),
            fg="#f8fafc",
            bg="#111827",
            selectcolor="#0f172a",
            activebackground="#111827",
            activeforeground="#38bdf8",
            command=self.on_mode_changed,
        )
        rb1.pack(anchor="w")

        rb2 = tk.Radiobutton(
            mode_card,
            text="🛡️ Анти-Глушилка (Все серверы + Белые списки РФ)",
            variable=self.selected_mode,
            value="whitelist_all",
            font=("Segoe UI", 9, "bold"),
            fg="#f8fafc",
            bg="#111827",
            selectcolor="#0f172a",
            activebackground="#111827",
            activeforeground="#38bdf8",
            command=self.on_mode_changed,
        )
        rb2.pack(anchor="w", pady=(4, 0))

        # Карточка текущего авто-сервера и списка узлов
        node_card = tk.Frame(self.root, bg="#111827", highlightbackground="#1f2937", highlightthickness=1, padx=16, pady=12)
        node_card.pack(fill="x", padx=20, pady=6)

        node_top = tk.Frame(node_card, bg="#111827")
        node_top.pack(fill="x")

        tk.Label(
            node_top,
            text="АКТИВНЫЙ БАЛАНСИРОВЩИК (URL-TEST 30s):",
            font=("Segoe UI", 8, "bold"),
            fg="#94a3b8",
            bg="#111827",
        ).pack(side="left")

        tk.Button(
            node_top,
            text="Список серверов ▸",
            font=("Segoe UI", 8, "bold"),
            fg="#38bdf8",
            bg="#111827",
            bd=0,
            cursor="hand2",
            command=self.open_nodes_modal,
        ).pack(side="right")

        self.node_lbl = tk.Label(
            node_card,
            textvariable=self.best_node_label_text,
            font=("Segoe UI", 10, "bold"),
            fg="#10b981",
            bg="#111827",
            anchor="w",
            pady=4,
        )
        self.node_lbl.pack(fill="x")

        self.pool_stats_lbl = tk.Label(
            node_card,
            text="Синхронизация зеркал белых списков...",
            font=("Segoe UI", 8),
            fg="#64748b",
            bg="#111827",
            anchor="w",
        )
        self.pool_stats_lbl.pack(fill="x")

    def _ensure_local_backend_running(self) -> None:
        """Если локальный сервер не запущен, поднимаем его автоматически."""
        try:
            api_request("/api/v1/health", timeout=2.0)
            return
        except Exception:
            pass

        backend_script = os.path.abspath(os.path.join(BASE_DIR, "..", "backend", "main.py"))
        if os.path.exists(backend_script):
            creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            self.backend_proc = subprocess.Popen(
                [sys.executable, backend_script],
                cwd=os.path.dirname(backend_script),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                stdin=subprocess.DEVNULL,
                creationflags=creationflags,
            )
            for _ in range(15):
                time.sleep(0.6)
                try:
                    api_request("/api/v1/health", timeout=2.0)
                    return
                except Exception:
                    continue

    def _bootstrap_connection(self) -> None:
        self._ensure_local_backend_running()
        try:
            resp = api_request(
                "/api/v1/auth/device",
                method="POST",
                payload={
                    "hwid": self.hwid,
                    "device_name": f"Windows ({platform.node()})",
                    "os_type": "windows",
                    "key_code": self.key_code,
                },
            )
            self.subscription = resp["subscription"]
            self.plans = resp.get("plans", [])
            self.key_code = self.subscription["key_code"]
            save_key_code(self.key_code)
            self.refresh_status()
        except Exception as e:
            self.root.after(0, lambda: self.conn_state_lbl.config(text=f"Ошибка: {e}", fg="#ef4444"))

    def refresh_status(self) -> None:
        if not self.key_code:
            return
        try:
            data = api_request(f"/api/v1/user/status?key_code={urllib.parse.quote(self.key_code)}")
            self.subscription = data["subscription"]
            self.pool_info = data["pool"]
            self.plans = data.get("plans", self.plans)
            self.root.after(0, self._update_status_ui)
        except Exception:
            pass

    def _update_status_ui(self) -> None:
        sub = self.subscription
        if not sub:
            return

        self.key_label.config(text=sub["key_code"])
        valid = sub["is_subscription_valid"]
        days = sub["days_left"]

        if valid:
            self.status_badge.config(text=f"● Активна ({days} дн.)", fg="#10b981")
        else:
            self.status_badge.config(text="⛔ Подписка истекла", fg="#ef4444")

        self.plan_info_lbl.config(
            text=f"{sub['plan_name']} • Устройств: {sub['devices_used']}/{sub['max_devices']}"
        )

        pool = self.pool_info
        if pool:
            total = pool.get("total_nodes", 0)
            alive = pool.get("alive_nodes", 0)
            foreign = pool.get("foreign_nodes", 0)
            ru = pool.get("ru_whitelist_nodes", 0)
            self.pool_stats_lbl.config(
                text=f"В пуле: {total} серверов (Онлайн: {alive} | 🌍 Зарубеж: {foreign} | 🇷🇺 РФ: {ru})"
            )

    def copy_key(self) -> None:
        if self.key_code:
            self.root.clipboard_clear()
            self.root.clipboard_append(self.key_code)
            messagebox.showinfo("Скопировано", f"Ключ доступа {self.key_code} скопирован в буфер обмена!")

    def change_key_dialog(self) -> None:
        new_key = simpledialog.askstring(
            "Ввод ключа доступа",
            "Введите ваш купленный ключ доступа (формат XXXX-XXXX-XXXX-XXXX):",
            parent=self.root,
        )
        if not new_key:
            return
        try:
            resp = api_request(
                "/api/v1/auth/device",
                method="POST",
                payload={
                    "hwid": self.hwid,
                    "device_name": f"Windows ({platform.node()})",
                    "os_type": "windows",
                    "key_code": new_key.strip().upper(),
                },
            )
            self.subscription = resp["subscription"]
            self.key_code = self.subscription["key_code"]
            save_key_code(self.key_code)
            self.refresh_status()
            messagebox.showinfo("Успех", resp["message"])
        except Exception as e:
            messagebox.showerror("Ошибка активации ключа", str(e))

    def on_mode_changed(self) -> None:
        self.preferred_node_id = None
        if self.is_connected:
            # Бесшовно перезапускаем туннель с новым фильтром
            threading.Thread(target=self._restart_tunnel_worker, daemon=True).start()

    def _ensure_xray_binary(self) -> bool:
        """Автоматически скачивает официальное ядро Xray-core для Windows при первом запуске."""
        if os.path.exists(XRAY_EXE):
            return True

        os.makedirs(BIN_DIR, exist_ok=True)
        zip_path = os.path.join(BIN_DIR, "xray.zip")
        self.root.after(
            0,
            lambda: self.conn_state_lbl.config(
                text="Загрузка ядра Xray-core (первый запуск, ~18 МБ)...", fg="#38bdf8"
            ),
        )
        try:
            req = urllib.request.Request(XRAY_DOWNLOAD_URL, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as resp, open(zip_path, "wb") as out_f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    out_f.write(chunk)

            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(BIN_DIR)
            try:
                os.remove(zip_path)
            except Exception:
                pass
            return os.path.exists(XRAY_EXE)
        except Exception as e:
            self.root.after(
                0,
                lambda: messagebox.showerror(
                    "Ошибка загрузки ядра",
                    f"Не удалось автоматически скачать Xray-core:\n{e}",
                ),
            )
            return False

    def toggle_vpn(self) -> None:
        if self.is_connecting:
            return
        if self.is_connected:
            self.disconnect_vpn()
        else:
            threading.Thread(target=self._connect_vpn_worker, daemon=True).start()

    def _connect_vpn_worker(self) -> None:
        if not self.key_code:
            return

        self.is_connecting = True
        self.root.after(0, self._set_ui_connecting)

        try:
            # 1. Проверяем/скачиваем ядро Xray-core
            if not self._ensure_xray_binary():
                self.is_connecting = False
                self.root.after(0, self._set_ui_disconnected)
                return

            # 2. Запрашиваем готовый автоматический конфиг у нашего Backend-сервера
            mode = self.selected_mode.get()
            qs = urllib.parse.urlencode(
                {
                    "key_code": self.key_code,
                    "hwid": self.hwid,
                    "mode": mode,
                    "engine": "xray",
                    "socks_port": SOCKS_PROXY_PORT,
                    "http_port": HTTP_PROXY_PORT,
                    "preferred_node_id": self.preferred_node_id or "",
                }
            )
            resp = api_request(f"/api/v1/vpn/config?{qs}", timeout=12.0)

            os.makedirs(BIN_DIR, exist_ok=True)
            with open(RUNTIME_CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(resp["config"], f, indent=2, ensure_ascii=False)

            # 3. Запускаем процесс Xray-core
            self.cleanup_tunnel()
            creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            self.xray_proc = subprocess.Popen(
                [XRAY_EXE, "run", "-c", RUNTIME_CONFIG_PATH],
                cwd=BIN_DIR,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                creationflags=creationflags,
            )

            time.sleep(0.8)
            if self.xray_proc.poll() is not None:
                err_out = self.xray_proc.stderr.read().decode("utf-8", errors="ignore") if self.xray_proc.stderr else ""
                raise RuntimeError(f"Ядро Xray завершилось с ошибкой: {err_out[:300]}")

            # 4. Включаем системный прокси Windows
            set_windows_system_proxy(True, "127.0.0.1", HTTP_PROXY_PORT)

            self.is_connected = True
            self.is_connecting = False

            best = resp.get("best_node")
            pool_size = resp.get("active_pool_size", 0)
            if best:
                ping_str = f"{best['latency_ms']} мс" if best.get("latency_ms") else "Auto"
                node_txt = f"⚡ {best['remark']} ({ping_str}) + ещё {max(0, pool_size - 1)} в резерве"
            else:
                node_txt = f"⚡ Авто-балансировщик ({pool_size} серверов)"

            self.root.after(0, lambda: self._set_ui_connected(node_txt))
        except Exception as e:
            self.is_connecting = False
            self.is_connected = False
            self.cleanup_tunnel()
            err_msg = str(e)
            self.root.after(0, self._set_ui_disconnected)
            self.root.after(0, lambda: messagebox.showwarning("Доступ к VPN", err_msg))

    def _restart_tunnel_worker(self) -> None:
        self.disconnect_vpn()
        time.sleep(0.3)
        self._connect_vpn_worker()

    def disconnect_vpn(self) -> None:
        self.cleanup_tunnel()
        self.is_connected = False
        self.is_connecting = False
        self._set_ui_disconnected()

    def cleanup_tunnel(self) -> None:
        set_windows_system_proxy(False)
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

    def _set_ui_connecting(self) -> None:
        self.canvas.itemconfig(self.outer_ring, outline="#f59e0b")
        self.canvas.itemconfig(self.power_icon, fill="#f59e0b")
        self.canvas.itemconfig(self.btn_status_text, text="ПОДКЛЮЧЕНИЕ...")
        self.conn_state_lbl.config(text="Настройка балансировщика и обхода белых списков...", fg="#fbbf24")

    def _set_ui_connected(self, node_txt: str) -> None:
        self.canvas.itemconfig(self.outer_ring, outline="#10b981")
        self.canvas.itemconfig(self.inner_circle, outline="#059669", fill="#064e3b")
        self.canvas.itemconfig(self.power_icon, fill="#34d399")
        self.canvas.itemconfig(self.btn_status_text, text="ЗАЩИЩЕНО")
        self.best_node_label_text.set(node_txt)
        self.conn_state_lbl.config(
            text=f"Подключено • Системный прокси 127.0.0.1:{HTTP_PROXY_PORT} активен",
            fg="#10b981",
        )

    def _set_ui_disconnected(self) -> None:
        self.canvas.itemconfig(self.outer_ring, outline="#1e293b")
        self.canvas.itemconfig(self.inner_circle, outline="#334155", fill="#0f172a")
        self.canvas.itemconfig(self.power_icon, fill="#64748b")
        self.canvas.itemconfig(self.btn_status_text, text="ПОДКЛЮЧИТЬ")
        self.conn_state_lbl.config(text="Туннель отключен • Нажмите кнопку для авто-подключения", fg="#94a3b8")

    def open_billing_modal(self) -> None:
        win = tk.Toplevel(self.root)
        win.title("Оплата подписки и Промокоды")
        win.geometry("420x480")
        win.configure(bg="#0b0f19")
        win.resizable(False, False)
        win.transient(self.root)
        win.grab_set()

        tk.Label(
            win,
            text="💎 Управление подпиской",
            font=("Segoe UI", 14, "bold"),
            fg="#f8fafc",
            bg="#0b0f19",
            pady=12,
        ).pack()

        # Блок промокода
        promo_box = tk.Frame(win, bg="#111827", padx=14, pady=12, highlightbackground="#1f2937", highlightthickness=1)
        promo_box.pack(fill="x", padx=18, pady=(0, 14))

        tk.Label(
            promo_box,
            text="🎁 Есть промокод? (Тестовые: FREE7, VIP30, WL2026)",
            font=("Segoe UI", 9, "bold"),
            fg="#fbbf24",
            bg="#111827",
        ).pack(anchor="w")

        p_row = tk.Frame(promo_box, bg="#111827", pady=6)
        p_row.pack(fill="x")

        promo_entry = tk.Entry(p_row, font=("Consolas", 12), bg="#0f172a", fg="#ffffff", insertbackground="#ffffff", bd=1)
        promo_entry.pack(side="left", fill="x", expand=True, ipady=4)

        def do_promo():
            code = promo_entry.get().strip()
            if not code:
                return
            try:
                res = api_request(
                    "/api/v1/billing/promo",
                    method="POST",
                    payload={"key_code": self.key_code, "promo_code": code},
                )
                self.subscription = res["subscription"]
                self._update_status_ui()
                messagebox.showinfo("Промокод активирован!", res["message"], parent=win)
                win.destroy()
            except Exception as e:
                messagebox.showerror("Ошибка", str(e), parent=win)

        tk.Button(
            p_row,
            text="Применить",
            font=("Segoe UI", 9, "bold"),
            bg="#10b981",
            fg="#ffffff",
            bd=0,
            padx=12,
            pady=5,
            cursor="hand2",
            command=do_promo,
        ).pack(side="left", padx=(8, 0))

        # Тарифы
        tk.Label(
            win,
            text="ВЫБЕРИТЕ ТАРИФ ДЛЯ ПОКУПКИ (СБП / КАРТА):",
            font=("Segoe UI", 9, "bold"),
            fg="#94a3b8",
            bg="#0b0f19",
        ).pack(anchor="w", padx=18, pady=(0, 6))

        for plan in self.plans:
            if plan["id"] == "trial":
                continue
            card = tk.Frame(win, bg="#111827", padx=14, pady=10, highlightbackground="#1f2937", highlightthickness=1)
            card.pack(fill="x", padx=18, pady=5)

            left_col = tk.Frame(card, bg="#111827")
            left_col.pack(side="left")
            tk.Label(left_col, text=plan["title"], font=("Segoe UI", 10, "bold"), fg="#f8fafc", bg="#111827").pack(anchor="w")
            tk.Label(
                left_col,
                text=f"Срок: {plan['days']} дн. • До {plan['max_devices']} устройств",
                font=("Segoe UI", 8),
                fg="#94a3b8",
                bg="#111827",
            ).pack(anchor="w")

            def buy_plan(p=plan):
                try:
                    order_res = api_request(
                        "/api/v1/billing/create-order",
                        method="POST",
                        payload={"key_code": self.key_code, "plan_id": p["id"]},
                    )
                    order = order_res["order"]
                    confirm = messagebox.askyesno(
                        "Демо-касса СБП / ЮKassa",
                        f"Создан заказ #{order['order_id']}\n"
                        f"Тариф: {p['title']}\n"
                        f"К оплате: {p['price_rub']} ₽\n\n"
                        f"Нажмите «Да», чтобы сымитировать успешную оплату и мгновенно начислить подписку.",
                        parent=win,
                    )
                    if confirm:
                        paid_res = api_request(
                            "/api/v1/billing/confirm-demo",
                            method="POST",
                            payload={"order_id": order["order_id"]},
                        )
                        self.subscription = paid_res["subscription"]
                        self._update_status_ui()
                        messagebox.showinfo("Оплата прошла успешно!", paid_res["message"], parent=win)
                        win.destroy()
                except Exception as e:
                    messagebox.showerror("Ошибка оплаты", str(e), parent=win)

            tk.Button(
                card,
                text=f"Купить за {plan['price_rub']} ₽",
                font=("Segoe UI", 9, "bold"),
                bg="#2563eb",
                fg="#ffffff",
                bd=0,
                padx=12,
                pady=6,
                cursor="hand2",
                command=buy_plan,
            ).pack(side="right")

    def open_nodes_modal(self) -> None:
        win = tk.Toplevel(self.root)
        win.title("Пул серверов и задержка (Latency Test)")
        win.geometry("460x500")
        win.configure(bg="#0b0f19")
        win.transient(self.root)

        top_bar = tk.Frame(win, bg="#0b0f19", padx=16, pady=10)
        top_bar.pack(fill="x")
        tk.Label(
            top_bar,
            text="⚡ Доступные серверы (сортировка по пингу)",
            font=("Segoe UI", 11, "bold"),
            fg="#f8fafc",
            bg="#0b0f19",
        ).pack(side="left")

        cols = ("remark", "proto", "ping")
        tree = ttk.Treeview(win, columns=cols, show="headings", height=16)
        tree.heading("remark", text="Локация / Сервер")
        tree.heading("proto", text="Протокол")
        tree.heading("ping", text="Пинг")
        tree.column("remark", width=230)
        tree.column("proto", width=110)
        tree.column("ping", width=80, anchor="center")
        tree.pack(fill="both", expand=True, padx=16, pady=6)

        node_map: Dict[str, Dict[str, Any]] = {}
        try:
            mode = self.selected_mode.get()
            res = api_request(f"/api/v1/vpn/nodes?key_code={urllib.parse.quote(self.key_code or '')}&mode={mode}")
            tree.insert("", "end", iid="AUTO", values=("🤖 АВТОВЫБОР (Балансировщик 30 сек)", "ALL", "Авто"))
            for n in res.get("nodes", []):
                ping_txt = f"{n['latency_ms']} мс" if n["latency_ms"] is not None else "Timeout"
                iid = n["id"]
                node_map[iid] = n
                tree.insert("", "end", iid=iid, values=(n["remark"], f"{n['type']}+{n['security']}", ping_txt))
        except Exception as e:
            messagebox.showwarning("Список серверов", str(e), parent=win)

        def apply_selected_node():
            sel = tree.selection()
            if not sel:
                return
            chosen = sel[0]
            if chosen == "AUTO":
                self.preferred_node_id = None
                self.best_node_label_text.set("⚡ Автовыбор самого быстрого сервера")
            else:
                n = node_map.get(chosen)
                self.preferred_node_id = chosen
                if n:
                    self.best_node_label_text.set(f"📌 {n['remark']} ({n.get('latency_ms') or 'Auto'} мс)")
            win.destroy()
            if self.is_connected:
                threading.Thread(target=self._restart_tunnel_worker, daemon=True).start()

        tk.Button(
            win,
            text="Выбрать сервер / Включить автовыбор",
            font=("Segoe UI", 10, "bold"),
            bg="#2563eb",
            fg="#ffffff",
            bd=0,
            padx=16,
            pady=8,
            cursor="hand2",
            command=apply_selected_node,
        ).pack(pady=10)

    def on_close(self) -> None:
        self.cleanup_tunnel()
        if self.backend_proc and self.backend_proc.poll() is None:
            try:
                self.backend_proc.terminate()
            except Exception:
                pass
        self.root.destroy()


def launch_standalone_webview_app() -> bool:
    """
    Запускает локальный Backend в фоне (без консоли) и открывает нативное окно
    приложения (Edge/Chrome App Mode без адресной строки) с дизайном Tactical Telemetry.
    """
    import shutil

    # 1. Проверяем, что запущена актуальная версия сервера с поддержкой /app
    backend_running = False
    try:
        api_request("/api/v1/local/state", timeout=1.5)
        backend_running = True
    except Exception:
        # Если порт 8000 занят старым процессом без /app — освобождаем его
        try:
            import psutil
            for conn in psutil.net_connections(kind="inet"):
                if conn.laddr.port == 8000 and conn.pid:
                    psutil.Process(conn.pid).terminate()
                    time.sleep(0.5)
        except Exception:
            pass

    if not backend_running:
        backend_script = os.path.abspath(os.path.join(BASE_DIR, "..", "backend", "main.py"))
        if os.path.exists(backend_script):
            creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
            exe = pyw if os.path.exists(pyw) else sys.executable
            subprocess.Popen(
                [exe, backend_script],
                cwd=os.path.dirname(backend_script),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                stdin=subprocess.DEVNULL,
                creationflags=creationflags,
            )
            for _ in range(25):
                time.sleep(0.4)
                try:
                    api_request("/api/v1/local/state", timeout=1.5)
                    backend_running = True
                    break
                except Exception:
                    continue

    # 2. Ищем Edge или Chrome для открытия нативного окна без рамок браузера (--app)
    browser_candidates = [
        shutil.which("msedge"),
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        shutil.which("chrome"),
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    ]
    browser_exe = next((p for p in browser_candidates if p and os.path.exists(p)), None)
    if browser_exe and backend_running:
        creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        subprocess.Popen(
            [
                browser_exe,
                f"--app={API_BASE_URL}/app",
                "--window-size=420,740",
                "--disable-extensions",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            creationflags=creationflags,
        )
        return True
    return False


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            hwnd = ctypes.windll.kernel32.GetConsoleWindow()
            if hwnd:
                ctypes.windll.user32.ShowWindow(hwnd, 0)  # SW_HIDE = 0
        except Exception:
            pass

    if "--tk" not in sys.argv and launch_standalone_webview_app():
        sys.exit(0)

    root = tk.Tk()
    app = PulseVPNWindow(root)
    root.mainloop()


