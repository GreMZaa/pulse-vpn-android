import atexit
import ctypes
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser

# Пути
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.join(BASE_DIR, "backend")

# Добавляем в sys.path
for p in [BACKEND_DIR, BASE_DIR]:
    if os.path.exists(p) and p not in sys.path:
        sys.path.insert(0, p)

try:
    from backend.main import app
    from backend.tunnel_manager import TUNNEL_MANAGER
except ImportError:
    from main import app
    from tunnel_manager import TUNNEL_MANAGER

PORT = 8000
SERVER_URL = f"http://127.0.0.1:{PORT}"
APP_URL = f"{SERVER_URL}/app"


def cleanup():
    try:
        if TUNNEL_MANAGER:
            TUNNEL_MANAGER.stop_tunnel()
            TUNNEL_MANAGER.set_windows_proxy(False)
    except Exception:
        pass


atexit.register(cleanup)


def is_backend_running() -> bool:
    try:
        req = urllib.request.Request(f"{SERVER_URL}/api/v1/local/state")
        with urllib.request.urlopen(req, timeout=0.8) as resp:
            return resp.status == 200
    except Exception:
        return False


def start_uvicorn():
    import uvicorn
    config = uvicorn.Config(
        app,
        host="127.0.0.1",
        port=PORT,
        log_level="warning",
        access_log=False,
    )
    server = uvicorn.Server(config)
    server.run()


def find_browser_executable():
    # 1. Microsoft Edge (установлен на 100% Windows 10/11)
    edge_paths = [
        os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%LocalAppData%\Microsoft\Edge\Application\msedge.exe"),
    ]
    for p in edge_paths:
        if os.path.exists(p):
            return p, "edge"

    which_edge = shutil.which("msedge.exe")
    if which_edge:
        return which_edge, "edge"

    # 2. Google Chrome
    chrome_paths = [
        os.path.expandvars(r"%ProgramFiles%\Google\Chrome\Application\chrome.exe"),
        os.path.expandvars(r"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"),
        os.path.expandvars(r"%LocalAppData%\Google\Chrome\Application\chrome.exe"),
    ]
    for p in chrome_paths:
        if os.path.exists(p):
            return p, "chrome"

    which_chrome = shutil.which("chrome.exe")
    if which_chrome:
        return which_chrome, "chrome"

    return None, None


def main():
    # Если бэкенд не запущен, запускаем в фоновом потоке
    if not is_backend_running():
        server_thread = threading.Thread(target=start_uvicorn, daemon=True)
        server_thread.start()

        # Ждем готовности сервера до 8 секунд
        for _ in range(32):
            if is_backend_running():
                break
            time.sleep(0.25)

    browser_exe, b_type = find_browser_executable()

    if browser_exe:
        # Запускаем в standalone app-режиме (выглядит как мобильное приложение без вкладок и адресной строки)
        cmd = [
            browser_exe,
            f"--app={APP_URL}",
            "--window-size=430,780",
            "--window-position=300,100",
            "--disable-features=Translate",
            "--app-id=PulseVPNApp",
        ]
        try:
            proc = subprocess.Popen(cmd)
            proc.wait()
        except Exception:
            webbrowser.open(APP_URL)
    else:
        # Fallback на системный браузер
        webbrowser.open(APP_URL)
        # Держим процесс живым
        try:
            while True:
                time.sleep(2)
        except (KeyboardInterrupt, SystemExit):
            pass

    cleanup()
    sys.exit(0)


if __name__ == "__main__":
    main()
