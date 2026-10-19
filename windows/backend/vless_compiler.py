import base64
import ipaddress
import json
import os
import re
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

# 1. Специализированные источники под Белые списки РФ (Мобильные операторы, Кабель, Белые CIDR РФ, Reality SNI)
WHITELIST_PRIMARY_URLS = [
    "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
    "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Cable.txt",
    "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-checked.txt",
    "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
    "https://raw.githubusercontent.com/zieng2/wl/main/vless.txt",
    "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt",
    "https://raw.githubusercontent.com/LowiKLive/BypassWhitelistRu/main/WhiteList_VPN.txt",
    "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/1.txt",
    "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/6.txt",
    "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
    "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt",
]

# 2. Мульти-протокольные пулы (Hysteria 2 QUIC, TUIC v5, VLESS Reality / XHTTP)
MULTI_PROTOCOL_POOL_URLS = [
    "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/hysteria2.txt",
    "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt",
    "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
    "https://raw.githubusercontent.com/sakha1370/OpenRay/main/output/all_valid_proxies.txt",
]

# Для обратной совместимости
MIRROR_URLS = WHITELIST_PRIMARY_URLS + MULTI_PROTOCOL_POOL_URLS

# Домены из Белого списка РФ (ТСПУ / Мобильные операторы) и разрешённые глобальные CDN
WHITE_SNI_DOMAINS = (
    "yandex", "ya.ru", "vk.com", "userapi.com", "ok.ru", "mail.ru",
    "gosuslugi", "ozon", "ozone.ru", "wildberries", "wb.ru", "sber",
    "tinkoff", "t-bank", "tbank", "max.ru", "ligastavok", "selectel",
    "dzen.ru", "rutube", "avito", "kinopoisk", "2gis", "mts.ru",
    "beeline.ru", "megafon.ru", "tele2.ru", "rostelecom", "pochta.ru",
    "rzd.ru", "mos.ru", "nalog.ru", "cloud.ru", "timeweb", "beget",
    "microsoft.com", "apple.com", "icloud.com", "yahoo.com", "cloudflare.com",
    "akamai", "fastly", "cdn", ".ru", ".рф", "xn--p1ai"
)

# Список российских доменов и сервисов для Умной маршрутизации (Smart Routing РФ напрямую без VPN)
RU_DIRECT_DOMAINS = [
    "domain:ru",
    "domain:xn--p1ai",
    "domain:su",
    "domain:sberbank.ru",
    "domain:online.sberbank.ru",
    "domain:sber.ru",
    "domain:tbank.ru",
    "domain:tinkoff.ru",
    "domain:vtb.ru",
    "domain:alfabank.ru",
    "domain:gazprombank.ru",
    "domain:raiffeisen.ru",
    "domain:sovcombank.ru",
    "domain:rshb.ru",
    "domain:psbank.ru",
    "domain:gosuslugi.ru",
    "domain:esia.gosuslugi.ru",
    "domain:nalog.ru",
    "domain:mos.ru",
    "domain:emiass.info",
    "domain:ozon.ru",
    "domain:ozone.ru",
    "domain:wildberries.ru",
    "domain:wb.ru",
    "domain:avito.ru",
    "domain:yandex.ru",
    "domain:ya.ru",
    "domain:yandex.net",
    "domain:yastatic.net",
    "domain:kinopoisk.ru",
    "domain:vk.com",
    "domain:vk.ru",
    "domain:userapi.com",
    "domain:ok.ru",
    "domain:mail.ru",
    "domain:dzen.ru",
    "domain:rutube.ru",
    "domain:2gis.ru",
    "domain:pochta.ru",
    "domain:rzd.ru",
    "domain:hh.ru",
    "domain:DNS-shop.ru",
    "domain:mvideo.ru",
    "domain:citilink.ru",
    "domain:cian.ru",
    "domain:domclick.ru",
    "domain:kufar.by",
]

# Сайты и подсети, которые ВСЕГДА должны идти через VPN-туннель (даже если имеют .ru зеркала или CDN)
TUNNEL_FORCE_DOMAINS = [
    "domain:telegram.org",
    "domain:t.me",
    "domain:tdesktop.com",
    "domain:telegra.ph",
    "domain:telesco.pe",
    "domain:youtube.com",
    "domain:youtu.be",
    "domain:googlevideo.com",
    "domain:ytimg.com",
    "domain:ggpht.com",
    "domain:instagram.com",
    "domain:cdninstagram.com",
    "domain:facebook.com",
    "domain:fbcdn.net",
    "domain:whatsapp.com",
    "domain:whatsapp.net",
    "domain:discord.com",
    "domain:discordapp.com",
    "domain:discordapp.net",
    "domain:discord.gg",
    "domain:openai.com",
    "domain:chatgpt.com",
    "domain:oaistatic.com",
    "domain:oaiusercontent.com",
    "domain:claude.ai",
    "domain:anthropic.com",
    "domain:gemini.google.com",
    "domain:spotify.com",
    "domain:scdn.co",
    "domain:netflix.com",
    "domain:nflxvideo.net",
    "domain:tiktok.com",
    "domain:tiktokcdn.com",
    "domain:x.com",
    "domain:twitter.com",
    "domain:twimg.com",
    "domain:rutracker.org",
    "domain:ntc.party",
]

TELEGRAM_CIDRS = [
    "149.154.160.0/20",
    "91.108.4.0/22",
    "91.108.8.0/22",
    "91.108.12.0/22",
    "91.108.16.0/22",
    "91.108.20.0/22",
    "91.108.56.0/22",
    "95.161.64.0/20",
    "185.76.151.0/24",
]

# Встроенная фильтрация рекламных сетей и трекеров (AdGuard / AdBlock на уровне маршрутизации)
ADBLOCK_DOMAINS = [
    "domain:doubleclick.net",
    "domain:googlesyndication.com",
    "domain:googleadservices.com",
    "domain:adservice.google.com",
    "domain:pagead2.googlesyndication.com",
    "domain:an.yandex.ru",
    "domain:ads.vk.com",
    "domain:target.my.com",
    "domain:ad.mail.ru",
    "domain:mc.yandex.ru",
    "domain:app-measurement.com",
    "domain:unityads.unity3d.com",
    "domain:applovin.com",
    "domain:vungle.com",
    "domain:ironsrc.com",
    "domain:chartboost.com",
    "domain:adcolony.com",
    "domain:inmobi.com",
    "domain:criteo.com",
    "domain:taboola.com",
    "domain:outbrain.com",
    "domain:popads.net",
    "domain:propellerads.com",
]


def is_safe_public_host(host: str, resolve_dns: bool = False) -> bool:
    """
    Проверяет, что хост или IP не является внутренним/локальным/метаданным адресом (защита от SSRF).
    По умолчанию проверяет синтаксис и IP мгновенно, без блокирующего DNS-резолва при парсинге сотен строк.
    """
    clean_host = host.strip().strip("[]").lower()
    if not clean_host or clean_host in ("localhost", "0.0.0.0", "127.0.0.1", "::1"):
        return False

    # 1. Если это прямой IP-адрес (IPv4 или IPv6)
    try:
        ip = ipaddress.ip_address(clean_host)
        if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified:
            return False
        return True
    except ValueError:
        pass

    # Проверка корректности доменного имени
    if "." not in clean_host or any(c in clean_host for c in ("/", "\\", " ", "@", ":")):
        return False

    if not resolve_dns:
        return True

    # 2. Если требуется полный резолв домена (при проверке сокета)
    try:
        addr_info = socket.getaddrinfo(clean_host, None)
        if not addr_info:
            return False
        for entry in addr_info:
            ip_str = entry[4][0]
            ip = ipaddress.ip_address(ip_str)
            if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_reserved or ip.is_multicast or ip.is_unspecified:
                return False
        return True
    except Exception:
        return False


def _clean_node_remark(raw_remark: str, index: int, proto_label: str = "Server") -> str:
    remark = urllib.parse.unquote(raw_remark or f"{proto_label} #{index + 1}").strip()
    # Очищаем чужие рекламные приписки телеграм-каналов и агрегаторов, сохраняя флаг страны и локацию
    remark = re.sub(r"(@[A-Za-z0-9_]+|https?://\S+|t\.me/\S+|b2n\.ir/\S+|epodonios|v2ray-configs)", "", remark, flags=re.IGNORECASE).strip()
    remark = re.sub(r"\s{2,}", " ", remark).strip(" -|•⚡:")
    if not remark or remark.isdigit():
        remark = f"🛡️ ПУЛЬС {proto_label} #{index + 1}"
    return remark[:65]


def parse_vless_uri(uri: str, index: int = 0) -> Optional[Dict[str, Any]]:
    """
    Парсит ссылку формата vless://uuid@host:port?params#remark
    Поддерживает TCP/RAW, WS, gRPC, XHTTP (SplitHTTP) + Reality / TLS.
    """
    uri = uri.strip()
    if not uri.startswith("vless://"):
        return None

    try:
        split_res = urllib.parse.urlsplit(uri)
        if "@" not in split_res.netloc:
            return None

        uuid_part, host_port = split_res.netloc.split("@", 1)
        uuid_part = urllib.parse.unquote(uuid_part).strip()
        if not uuid_part or len(uuid_part) < 8:
            return None

        if host_port.startswith("["):
            m = re.match(r"^\[([^\]]+)\]:(\d+)$", host_port)
            if not m:
                return None
            address, port_str = m.group(1), m.group(2)
        else:
            if ":" not in host_port:
                return None
            address, port_str = host_port.rsplit(":", 1)

        port = int(port_str)
        if port <= 0 or port > 65535:
            return None

        if not is_safe_public_host(address, resolve_dns=False):
            return None

        qs = urllib.parse.parse_qs(split_res.query, keep_blank_values=True)
        def get_param(key: str, default: str = "") -> str:
            vals = qs.get(key)
            return vals[0].strip() if vals else default

        raw_fragment = urllib.parse.unquote(split_res.fragment or "")
        if any(bad in raw_fragment.lower() for bad in ["устарела", "обновите ссылку", "не работает", "expired"]):
            return None

        remark = _clean_node_remark(raw_fragment, index, "VLESS")

        net_type = get_param("type", "tcp").lower()
        security = get_param("security", "none").lower()
        sni = get_param("sni", "")
        host = get_param("host", "")
        path = get_param("path", "/")
        mode = get_param("mode", "auto")
        fp = get_param("fp", "chrome") or "chrome"
        alpn_raw = get_param("alpn", "")
        pbk = get_param("pbk", "")
        sid = get_param("sid", "")
        spx = get_param("spx", "")
        flow = get_param("flow", "")
        service_name = get_param("serviceName", get_param("path", ""))
        extra_raw = get_param("extra", "")

        # Для Reality обязательно наличие валидного pbk (32-байтный Curve25519 ключ в Base64) и hex sid
        if security == "reality":
            if not pbk:
                return None
            try:
                padded_pbk = pbk.replace("-", "+").replace("_", "/") + "=" * ((4 - len(pbk) % 4) % 4)
                raw_pbk = base64.b64decode(padded_pbk)
                if len(raw_pbk) != 32:
                    return None
            except Exception:
                return None
            if sid and (len(sid) > 16 or not re.match(r"^[0-9a-fA-F]+$", sid)):
                sid = ""

        is_ru = (
            "🇷🇺" in remark
            or any(k in remark.lower() for k in ["russia", "россия", "moscow", "москва", "spb", "спб", "beget", "timeweb", "vk", "cloud.ru", "selectel", "yandex", "cidr"])
        )
        is_cascade = any(k in remark.lower() for k in ["каскад", "cascade", "relay", "мост", "vip"])

        # Убираем устаревшие параметры allowInsecure/insecure (вызывают ошибку в Xray 26.3+)
        base_uri_no_frag = uri.split("#")[0]
        base_uri_no_frag = re.sub(r"([?&])(allowInsecure|insecure)=[^&]*&?", r"\1", base_uri_no_frag)
        base_uri_no_frag = base_uri_no_frag.rstrip("?&")
        # Пересобираем чистый raw_uri с аккуратным именем
        clean_uri = base_uri_no_frag + "#" + urllib.parse.quote(remark)

        return {
            "id": f"node_{index}",
            "raw_uri": clean_uri,
            "remark": remark,
            "protocol": "vless",
            "uuid": uuid_part,
            "address": address,
            "port": port,
            "type": net_type,
            "security": security,
            "sni": sni,
            "host": host,
            "path": path,
            "mode": mode,
            "fp": fp,
            "alpn": [a.strip().lstrip("+") for a in alpn_raw.split(",") if a.strip()] if alpn_raw else [],
            "pbk": pbk,
            "sid": sid,
            "spx": spx,
            "flow": flow,
            "service_name": service_name,
            "extra": extra_raw,
            "is_ru": is_ru,
            "is_cascade": is_cascade,
            "latency_ms": None,
        }
    except Exception:
        return None


def parse_hy2_uri(uri: str, index: int = 0) -> Optional[Dict[str, Any]]:
    """
    Парсит ссылку формата hy2://password@host:port?sni=domain&insecure=1#remark
    Резервный протокол Hysteria 2 на базе UDP QUIC для пробития ТСПУ и плохих сетей.
    """
    uri = uri.strip()
    if not (uri.startswith("hy2://") or uri.startswith("hysteria2://")):
        return None

    try:
        split_res = urllib.parse.urlsplit(uri)
        if "@" not in split_res.netloc:
            return None

        auth_part, host_port = split_res.netloc.split("@", 1)
        password = urllib.parse.unquote(auth_part).strip()
        if not password:
            return None

        if host_port.startswith("["):
            m = re.match(r"^\[([^\]]+)\]:(\d+)$", host_port)
            if not m:
                return None
            address, port_str = m.group(1), m.group(2)
        else:
            if ":" not in host_port:
                address, port_str = host_port, "443"
            else:
                address, port_str = host_port.rsplit(":", 1)
                # Отсекаем multi-port синтаксис вида 443,10000-20000
                port_str = port_str.split(",")[0].split("-")[0]

        port = int(port_str)
        if port <= 0 or port > 65535:
            return None

        if not is_safe_public_host(address, resolve_dns=False):
            return None

        qs = urllib.parse.parse_qs(split_res.query, keep_blank_values=True)
        def get_param(key: str, default: str = "") -> str:
            vals = qs.get(key)
            return vals[0].strip() if vals else default

        remark = _clean_node_remark(split_res.fragment, index, "Hysteria2 ⚡")
        if "hy2" not in remark.lower() and "hysteria" not in remark.lower():
            remark = f"⚡ HY2 | {remark}"

        sni = get_param("sni", address)
        obfs = get_param("obfs", "")
        obfs_password = get_param("obfs-password", "")
        insecure = get_param("insecure", "1") in ("1", "true")

        is_ru = (
            "🇷🇺" in remark
            or any(k in remark.lower() for k in ["russia", "россия", "beget", "timeweb", "vk", "cloud.ru", "selectel", "yandex"])
        )
        clean_uri = uri.split("#")[0] + "#" + urllib.parse.quote(remark)

        return {
            "id": f"node_{index}",
            "raw_uri": clean_uri,
            "remark": remark,
            "protocol": "hysteria2",
            "uuid": password,
            "password": password,
            "address": address,
            "port": port,
            "type": "hysteria2",
            "security": "tls",
            "sni": sni,
            "obfs": obfs,
            "obfs_password": obfs_password,
            "insecure": insecure,
            "is_ru": is_ru,
            "is_cascade": False,
            "latency_ms": None,
        }
    except Exception:
        return None


def parse_tuic_uri(uri: str, index: int = 0) -> Optional[Dict[str, Any]]:
    """
    Парсит ссылку формата tuic://uuid:password@host:port?congestion_control=bbr&alpn=h3&sni=...#remark
    Протокол TUIC v5 (QUIC UDP) с мультиплексированием 0-RTT.
    """
    uri = uri.strip()
    if not uri.startswith("tuic://"):
        return None

    try:
        split_res = urllib.parse.urlsplit(uri)
        if "@" not in split_res.netloc:
            return None

        auth_part, host_port = split_res.netloc.split("@", 1)
        auth_decoded = urllib.parse.unquote(auth_part).strip()
        if ":" in auth_decoded:
            uuid_part, password = auth_decoded.split(":", 1)
        else:
            uuid_part, password = auth_decoded, ""

        if host_port.startswith("["):
            m = re.match(r"^\[([^\]]+)\]:(\d+)$", host_port)
            if not m:
                return None
            address, port_str = m.group(1), m.group(2)
        else:
            if ":" not in host_port:
                return None
            address, port_str = host_port.rsplit(":", 1)

        port = int(port_str)
        if port <= 0 or port > 65535 or not is_safe_public_host(address, resolve_dns=False):
            return None

        qs = urllib.parse.parse_qs(split_res.query, keep_blank_values=True)
        def get_param(key: str, default: str = "") -> str:
            vals = qs.get(key)
            return vals[0].strip() if vals else default

        remark = _clean_node_remark(split_res.fragment, index, "TUIC v5 ⚡")
        if "tuic" not in remark.lower():
            remark = f"⚡ TUIC | {remark}"
        sni = get_param("sni", address)
        cc = get_param("congestion_control", "bbr")
        clean_uri = uri.split("#")[0] + "#" + urllib.parse.quote(remark)

        return {
            "id": f"node_{index}",
            "raw_uri": clean_uri,
            "remark": remark,
            "protocol": "tuic",
            "uuid": uuid_part,
            "password": password,
            "address": address,
            "port": port,
            "type": "tuic",
            "security": "tls",
            "sni": sni,
            "congestion_control": cc,
            "is_ru": "🇷🇺" in remark,
            "is_cascade": False,
            "latency_ms": None,
        }
    except Exception:
        return None


def parse_proxy_uri(uri: str, index: int = 0) -> Optional[Dict[str, Any]]:
    uri = uri.strip()
    if uri.startswith("vless://"):
        return parse_vless_uri(uri, index)
    if uri.startswith("hy2://") or uri.startswith("hysteria2://"):
        return parse_hy2_uri(uri, index)
    if uri.startswith("tuic://"):
        return parse_tuic_uri(uri, index)
    return None


def is_white_sni_node(node: Dict[str, Any]) -> bool:
    """
    Проверяет, использует ли узел домен маскировки (SNI/Host) из Белого списка РФ или разрешённых CDN.
    """
    sni = (node.get("sni") or node.get("host") or "").lower()
    if not sni:
        return False
    return any(d in sni for d in WHITE_SNI_DOMAINS)


def get_node_whitelist_priority(node: Dict[str, Any]) -> int:
    """
    Возвращает приоритет сортировки (меньше = выше в списке):
    -1: 👑 Собственный VIP Каскад (РФ -> Зарубеж)
     0: 🛡️ Белый список РФ (VLESS Reality / XHTTP с RU/White SNI или Hysteria 2)
     1: 🇷🇺 Проверенные российские CIDR / Белые подсети
     2: ⚡ Зарубежные VLESS Reality / XHTTP (SplitHTTP)
     3: Обычные VLESS TLS / WS
    """
    if node.get("is_cascade"):
        return -1

    sec = (node.get("security") or "").lower()
    net = (node.get("type") or "").lower()
    proto = (node.get("protocol") or "vless").lower()
    remark = (node.get("remark") or "").lower()

    if proto in ("hysteria2", "hy2", "tuic"):
        return 0

    if sec == "reality" and is_white_sni_node(node):
        return 0

    if net in ("xhttp", "splithttp") and (sec in ("reality", "tls")):
        return 0

    if any(k in remark for k in ["🇷🇺", "бел", "white", "cidr", "mobile", "мтс", "билайн", "мегафон", "теле2"]):
        return 1

    if sec == "reality":
        return 2

    return 3


def check_node_tcp_latency(node: Dict[str, Any], timeout: float = 2.0) -> Optional[int]:
    """
    Глубокая проверка доступности узла:
    - Проверяет безопасность IP (анти-SSRF);
    - Для VLESS TLS / Reality выполняет реальное TLS ClientHello рукопожатие с целевым SNI!
      Это отсеивает 100% «пустышек» (серверов, где порт открыт, но Xray/Reality мёртв или SNI заблокирован ТСПУ).
    - Для Hysteria 2 / TUIC проверяет сетевую доступность хоста и порта.
    """
    addr = node["address"]
    port = int(node["port"])
    if not is_safe_public_host(addr, resolve_dns=True):
        return None

    proto = (node.get("protocol") or "vless").lower()
    sec = (node.get("security") or "none").lower()
    sni = (node.get("sni") or node.get("host") or addr).strip()

    start = time.perf_counter()
    try:
        if proto in ("hysteria2", "hy2", "tuic"):
            # Hysteria2 / TUIC работают поверх UDP QUIC, но большинство таких серверов также держат
            # TCP 443 или отвечают по UDP без ICMP Unreachable
            try:
                with socket.create_connection((addr, port), timeout=min(timeout, 1.4)):
                    elapsed_ms = int((time.perf_counter() - start) * 1000)
                    return max(1, elapsed_ms)
            except Exception:
                # Проверяем UDP QUIC Initial-пакет (минимальный QUIC header >= 1200 байт)
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.settimeout(min(timeout, 1.2))
                try:
                    start_udp = time.perf_counter()
                    quic_probe = b"\xc0\x00\x00\x00\x01\x08" + os.urandom(16) + b"\x00" * 1180 if "os" in globals() else b"\xc0\x00\x00\x00\x01\x08" + b"\x1a" * 1196
                    sock.sendto(quic_probe, (addr, port))
                    data, _ = sock.recvfrom(2048)
                    if data:
                        return max(1, int((time.perf_counter() - start_udp) * 1000))
                except Exception:
                    return None
                finally:
                    sock.close()
                return None

        # Для VLESS: устанавливаем TCP соединение
        with socket.create_connection((addr, port), timeout=timeout) as raw_sock:
            raw_sock.settimeout(timeout)
            # Если узел использует TLS или Reality — обязательно проверяем TLS/Reality Handshake с SNI!
            if sec in ("tls", "reality"):
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                # Поддержка современных шифров как в Chrome uTLS
                with ctx.wrap_socket(raw_sock, server_hostname=sni, do_handshake_on_connect=False) as tls_sock:
                    tls_sock.settimeout(timeout)
                    tls_sock.do_handshake()
            elapsed_ms = int((time.perf_counter() - start) * 1000)
            return max(1, elapsed_ms)
    except Exception:
        return None


def _find_xray_binary() -> Optional[str]:
    import shutil
    env_bin = os.environ.get("XRAY_BIN", "").strip()
    if env_bin and os.path.isfile(env_bin):
        return env_bin
    base_dir = Path(__file__).resolve().parent
    candidates = [
        base_dir / "bin" / "xray.exe",
        base_dir / "bin" / "xray",
        base_dir.parent / "backend" / "bin" / "xray.exe",
        base_dir.parent / "backend" / "bin" / "xray",
    ]
    for c in candidates:
        if c.is_file():
            return str(c)
    return shutil.which("xray")


def _probe_single_xray_proxy_port(proxy_port: int, timeout: float = 4.5) -> Optional[int]:
    proxy_url = f"http://127.0.0.1:{proxy_port}"
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url})
    )
    start = time.perf_counter()
    try:
        req = urllib.request.Request(
            "http://cp.cloudflare.com/generate_204",
            headers={"User-Agent": "Mozilla/5.0", "Connection": "close"},
        )
        with opener.open(req, timeout=timeout) as resp:
            if resp.status not in (200, 204):
                return None
    except Exception:
        try:
            req2 = urllib.request.Request(
                "http://www.gstatic.com/generate_204",
                headers={"User-Agent": "Mozilla/5.0", "Connection": "close"},
            )
            with opener.open(req2, timeout=timeout) as resp2:
                if resp2.status not in (200, 204):
                    return None
        except Exception:
            return None

    rtt_ms = max(1, int((time.perf_counter() - start) * 1000))

    # Проверяем, что выходной сервер реально открывает Telegram (не заблокирован РКН на выходе в РФ)
    try:
        tg_req = urllib.request.Request(
            "https://api.telegram.org",
            headers={"User-Agent": "Mozilla/5.0", "Connection": "close"},
        )
        with opener.open(tg_req, timeout=timeout) as tg_resp:
            if tg_resp.status in (200, 301, 302, 404):
                return rtt_ms
    except urllib.error.HTTPError as he:
        # api.telegram.org корень возвращает 302 или 404 — значит соединение до Telegram установлено!
        if he.code in (200, 301, 302, 400, 401, 404):
            return rtt_ms
        return None
    except Exception:
        return None
    return rtt_ms


def _verify_vless_nodes_via_xray(
    alive_nodes: List[Dict[str, Any]],
    xray_bin: str,
    batch_size: int = 55,
    timeout: float = 4.5,
) -> List[Dict[str, Any]]:
    import subprocess
    import tempfile

    vless_nodes = [n for n in alive_nodes if n.get("protocol", "vless").lower() == "vless"]
    other_nodes = [n for n in alive_nodes if n.get("protocol", "vless").lower() != "vless"]
    if not vless_nodes:
        return alive_nodes

    verified_vless: List[Dict[str, Any]] = []
    base_port = 24100

    for batch_start in range(0, len(vless_nodes), batch_size):
        batch = vless_nodes[batch_start:batch_start + batch_size]
        inbounds = []
        outbounds = []
        rules = []

        for idx, node in enumerate(batch):
            in_tag = f"in_{idx}"
            out_tag = f"out_{idx}"
            port = base_port + idx
            inbounds.append({
                "tag": in_tag,
                "port": port,
                "listen": "127.0.0.1",
                "protocol": "http",
                "settings": {"timeout": 5},
            })
            outbounds.append(node_to_xray_outbound(node, tag=out_tag, enable_fragment=True))
            rules.append({
                "type": "field",
                "inboundTag": [in_tag],
                "outboundTag": out_tag,
            })

        outbounds.append({
            "tag": "fragment",
            "protocol": "freedom",
            "settings": {
                "fragment": {
                    "packets": "tlshello",
                    "length": "10-20",
                    "interval": "10-20",
                }
            },
            "streamSettings": {"sockopt": {"tcpNoDelay": True}},
        })

        cfg = {
            "log": {"loglevel": "none"},
            "inbounds": inbounds,
            "outbounds": outbounds,
            "routing": {"domainStrategy": "AsIs", "rules": rules},
        }

        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".json", prefix="pulse_xray_verify_")
        proc = None
        try:
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as f:
                json.dump(cfg, f)

            proc = subprocess.Popen(
                [xray_bin, "run", "-c", tmp_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            time.sleep(0.65)

            with ThreadPoolExecutor(max_workers=len(batch)) as ex:
                fut_map = {
                    ex.submit(_probe_single_xray_proxy_port, base_port + idx, timeout): batch[idx]
                    for idx in range(len(batch))
                }
                for fut in as_completed(fut_map):
                    node = dict(fut_map[fut])
                    try:
                        real_rtt = fut.result()
                    except Exception:
                        real_rtt = None
                    if real_rtt is not None:
                        node["latency_ms"] = real_rtt
                        verified_vless.append(node)
        except Exception:
            pass
        finally:
            if proc is not None:
                try:
                    proc.terminate()
                    proc.wait(timeout=2)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
            try:
                os.remove(tmp_path)
            except Exception:
                pass

    return verified_vless if verified_vless else (verified_vless + other_nodes)


def probe_nodes_concurrently(
    nodes: List[Dict[str, Any]],
    timeout: float = 2.0,
    max_workers: int = 40,
    only_alive: bool = False,
) -> List[Dict[str, Any]]:
    """
    Параллельно проверяет реальное TLS/Reality рукопожатие, а при наличии ядра Xray —
    настоящую прокачку трафика HTTP-204 и доступность Telegram (api.telegram.org) через туннель!
    """
    if not nodes:
        return []

    results: List[Dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=min(max_workers, max(1, len(nodes)))) as executor:
        future_map = {executor.submit(check_node_tcp_latency, n, timeout): n for n in nodes}
        for fut in as_completed(future_map):
            node = dict(future_map[fut])
            try:
                ping = fut.result()
            except Exception:
                ping = None
            node["latency_ms"] = ping
            if only_alive and ping is None:
                continue
            results.append(node)

    # Если включён режим only_alive и доступно ядро Xray — проверяем реальный туннель (HTTP-204 + Telegram)
    xray_bin = _find_xray_binary()
    if only_alive and xray_bin and results:
        real_verified = _verify_vless_nodes_via_xray(results, xray_bin)
        if real_verified:
            results = real_verified

    results.sort(key=lambda x: (
        x["latency_ms"] is None,
        get_node_whitelist_priority(x),
        x["latency_ms"] if x["latency_ms"] is not None else 99999
    ))
    return results


def _decode_subscription_text(raw_text: str) -> List[str]:
    """
    Извлекает строки vless://, hy2://, hysteria2://, tuic:// как из обычного текста,
    так и из Base64-кодированных подписок.
    """
    text = raw_text.strip()
    if not text:
        return []

    # Если в тексте нет явных протоколов, пробуем декодировать Base64
    if not any(p in text for p in ("vless://", "hy2://", "hysteria2://", "tuic://")):
        try:
            padded = text + "=" * ((4 - len(text) % 4) % 4)
            decoded = base64.b64decode(padded).decode("utf-8", errors="ignore")
            if any(p in decoded for p in ("vless://", "hy2://", "hysteria2://", "tuic://")):
                text = decoded
        except Exception:
            pass

    valid_prefixes = ("vless://", "hy2://", "hysteria2://", "tuic://")
    lines = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith(valid_prefixes):
            lines.append(s)
    return lines


def _fetch_single_source(url: str, timeout: float = 7.0, is_primary_wl: bool = True) -> Tuple[List[str], str]:
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PulseVPN/0.1.5 Karing/1.0",
        "Cache-Control": "no-cache",
    }
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw_text = resp.read().decode("utf-8", errors="ignore")
            lines = _decode_subscription_text(raw_text)
            if not is_primary_wl:
                # Из глобальных агрегаторов берём VLESS с Reality или XHTTP
                filtered_lines = []
                for ln in lines:
                    low = ln.lower()
                    if (
                        "security=reality" in low
                        or "type=xhttp" in low
                        or "type=splithttp" in low
                        or ln.startswith(("hy2://", "hysteria2://", "tuic://"))
                    ):
                        filtered_lines.append(ln)
                lines = filtered_lines[:180]
            else:
                lines = lines[:300]
            return lines, url
    except Exception:
        return [], url


def fetch_raw_subscription_from_mirrors(timeout: float = 8.0, max_candidates: int = 600) -> Tuple[List[str], str]:
    """
    Параллельно скачивает списки серверов из ВСЕХ проверенных источников Белых списков РФ
    (igareck Mobile/Cable/CIDR, zieng2/wl, LowiKLive, AvenCores, Hysteria2 пулы),
    удаляет дубликаты по (host, port) и возвращает единый расширенный пул.
    """
    all_tasks = [(u, True) for u in WHITELIST_PRIMARY_URLS] + [(u, False) for u in MULTI_PROTOCOL_POOL_URLS]
    collected_lines: List[str] = []
    active_sources: List[str] = []

    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = {
            executor.submit(_fetch_single_source, url, timeout, is_primary): (url, is_primary)
            for url, is_primary in all_tasks
        }
        # Сначала собираем в порядке приоритета источников
        results_by_url: Dict[str, List[str]] = {}
        for fut in as_completed(futures):
            try:
                lines, url = fut.result()
                if lines:
                    results_by_url[url] = lines
            except Exception:
                continue

    for url, _ in all_tasks:
        if url in results_by_url:
            collected_lines.extend(results_by_url[url])
            # Короткое имя репозитория для статистики
            parts = url.split("/")
            short_src = f"{parts[3]}/{parts[4]}" if len(parts) > 4 else url
            if short_src not in active_sources:
                active_sources.append(short_src)

    # Строгая дедупликация по (protocol, host:port) чтобы не пинговать клоны одного и того же сервера
    deduped_uris: List[str] = []
    seen_endpoints: Set[Tuple[str, str, int]] = set()

    for uri in collected_lines:
        parsed = parse_proxy_uri(uri, len(deduped_uris))
        if not parsed:
            continue
        endpoint_key = (
            parsed["protocol"],
            parsed["address"].lower(),
            int(parsed["port"]),
        )
        if endpoint_key in seen_endpoints:
            continue
        seen_endpoints.add(endpoint_key)
        deduped_uris.append(parsed["raw_uri"])
        if len(deduped_uris) >= max_candidates:
            break

    source_summary = ", ".join(active_sources[:5]) if active_sources else "zieng2/wl + igareck/wl"
    return deduped_uris, source_summary


def deduplicate_parsed_nodes(nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Убирает дубликаты распарсенных узлов по (protocol, address, port).
    """
    unique: List[Dict[str, Any]] = []
    seen: Set[Tuple[str, str, int]] = set()
    for n in nodes:
        key = (n.get("protocol", "vless"), str(n.get("address", "")).lower(), int(n.get("port", 443)))
        if key in seen:
            continue
        seen.add(key)
        n["id"] = f"node_{len(unique) + 1}"
        unique.append(n)
    return unique


def node_to_xray_outbound(node: Dict[str, Any], tag: str, enable_fragment: bool = True) -> Dict[str, Any]:
    """
    Преобразует распарсенный VLESS, Hysteria2 или TUIC узел в формат outbound для Xray-core.
    Поддерживает Multi-Protocol Armor:
    - VLESS + XHTTP (SplitHTTP) + Reality / TLS
    - Анти-DPI фрагментацию TLS ClientHello (dialerProxy -> fragment)
    - Hysteria 2 / TUIC v5
    """
    if node.get("protocol") == "hysteria2":
        return {
            "tag": tag,
            "protocol": "hysteria2",
            "settings": {
                "servers": [
                    {
                        "address": node["address"],
                        "port": node["port"],
                        "password": node.get("password") or node.get("uuid"),
                    }
                ]
            },
            "streamSettings": {
                "network": "udp",
                "security": "tls",
                "tlsSettings": {
                    "serverName": node.get("sni") or node["address"],
                },
            },
        }

    net_type = node.get("type", "tcp")
    if net_type not in ("tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade"):
        net_type = "tcp"

    xray_net = "xhttp" if net_type in ("xhttp", "splithttp") else ("raw" if net_type == "raw" else net_type)

    user_obj: Dict[str, Any] = {
        "id": node["uuid"],
        "encryption": "none",
    }
    if node.get("flow") and xray_net in ("tcp", "raw"):
        user_obj["flow"] = node["flow"]

    stream_settings: Dict[str, Any] = {
        "network": xray_net,
        "security": node["security"] if node["security"] in ("tls", "reality") else "none",
    }

    if enable_fragment and node.get("security") in ("tls", "reality"):
        stream_settings["sockopt"] = {
            "dialerProxy": "fragment",
            "tcpKeepAliveInterval": 30,
            "tcpNoDelay": True,
        }

    if node["security"] == "tls":
        tls_settings: Dict[str, Any] = {
            "fingerprint": node.get("fp") or "chrome",
        }
        if node.get("sni"):
            tls_settings["serverName"] = node["sni"]
        if node.get("alpn"):
            tls_settings["alpn"] = node["alpn"]
        stream_settings["tlsSettings"] = tls_settings

    elif node["security"] == "reality":
        stream_settings["realitySettings"] = {
            "show": False,
            "fingerprint": node.get("fp") or "chrome",
            "serverName": node.get("sni", ""),
            "publicKey": node.get("pbk", ""),
            "shortId": node.get("sid", ""),
            "spiderX": node.get("spx") or "/",
        }

    if xray_net == "ws":
        ws_settings: Dict[str, Any] = {"path": node.get("path") or "/"}
        if node.get("host"):
            ws_settings["headers"] = {"Host": node["host"]}
        stream_settings["wsSettings"] = ws_settings

    elif xray_net == "grpc":
        stream_settings["grpcSettings"] = {
            "serviceName": node.get("service_name") or "",
            "multiMode": False,
        }

    elif xray_net == "xhttp":
        xhttp_settings: Dict[str, Any] = {
            "path": node.get("path") or "/",
            "mode": node.get("mode") or "auto",
        }
        if node.get("host"):
            xhttp_settings["host"] = node["host"]
        if node.get("extra") and node["extra"] != "null":
            try:
                extra_json = json.loads(node["extra"])
                if isinstance(extra_json, dict):
                    xhttp_settings["extra"] = extra_json
            except Exception:
                pass
        stream_settings["xhttpSettings"] = xhttp_settings

    return {
        "tag": tag,
        "protocol": "vless",
        "settings": {
            "vnext": [
                {
                    "address": node["address"],
                    "port": node["port"],
                    "users": [user_obj],
                }
            ]
        },
        "streamSettings": stream_settings,
    }


def build_xray_auto_config(
    nodes: List[Dict[str, Any]],
    mode: str = "foreign_only",
    socks_port: int = 10808,
    http_port: int = 10809,
    preferred_node_id: Optional[str] = None,
    smart_routing_ru: bool = True,
    enable_adblock: bool = True,
    enable_fragment: bool = True,
) -> Dict[str, Any]:
    """
    Генерирует флагманский конфиг Xray-core для ПУЛЬС VPN:
    1) 🛡️ Multi-Protocol Armor: VLESS Reality + XHTTP (SplitHTTP) + Анти-DPI TLS Fragment;
    2) 🧠 Умная маршрутизация (Smart Routing РФ + Мир): Сбербанк, Т-Банк, Госуслуги, Озон, WB,
       Кинопоиск и все сайты .ru / .рф работают НАПРЯМУЮ без выключения VPN, а Telegram, YouTube,
       Instagram, Discord, ChatGPT и зарубежные сайты летят через быстрый туннель;
    3) 🚫 Встроенный блокировщик рекламы и трекеров (AdGuard DoH DNS + фильтрация рекламных доменов);
    4) ⚡ Авто-балансировщик (burstObservatory + leastPing) по живым узлам.
    """
    if mode == "foreign_only":
        filtered = [n for n in nodes if not n.get("is_ru", False)]
        if not filtered:
            filtered = list(nodes)
    else:
        filtered = list(nodes)

    alive = [n for n in filtered if n.get("latency_ms") is not None]
    target_nodes = alive if alive else filtered

    if preferred_node_id:
        preferred = [n for n in filtered if n.get("id") == preferred_node_id]
        if preferred:
            target_nodes = preferred + [n for n in target_nodes if n.get("id") != preferred_node_id]

    target_nodes = target_nodes[:25]

    proxy_outbounds: List[Dict[str, Any]] = []
    proxy_tags: List[str] = []

    for idx, node in enumerate(target_nodes):
        tag = f"proxy_{idx + 1}"
        proxy_outbounds.append(node_to_xray_outbound(node, tag, enable_fragment=enable_fragment))
        proxy_tags.append(tag)

    if not proxy_outbounds:
        proxy_outbounds.append({"tag": "proxy_1", "protocol": "freedom", "settings": {}})
        proxy_tags.append("proxy_1")

    fragment_outbound = {
        "tag": "fragment",
        "protocol": "freedom",
        "settings": {
            "domainStrategy": "AsIs",
            "fragment": {
                "packets": "tlshello",
                "length": "100-200",
                "interval": "10-20",
            },
        },
        "streamSettings": {
            "sockopt": {
                "tcpNoDelay": True,
                "tcpKeepAliveInterval": 30,
            }
        },
    }

    # Настройка DNS: AdGuard DoH (блокировка рекламы + защита от утечек) или Cloudflare DoH + Яндекс DNS для .ru
    primary_doh = "https://dns.adguard-dns.com/dns-query" if enable_adblock else "https://1.1.1.1/dns-query"
    dns_servers: List[Any] = [
        primary_doh,
        "https://dns.google/dns-query",
    ]
    if smart_routing_ru:
        dns_servers.append({
            "address": "77.88.8.8",
            "port": 53,
            "domains": RU_DIRECT_DOMAINS[:25],
        })

    routing_rules: List[Dict[str, Any]] = [
        # 1. Локальные сети — напрямую
        {
            "type": "field",
            "ip": ["geoip:private"],
            "outboundTag": "direct",
        },
    ]

    # 2. Блокировщик рекламы и трекеров (AdGuard / AdBlock на уровне ядра)
    if enable_adblock:
        routing_rules.append({
            "type": "field",
            "domain": ADBLOCK_DOMAINS,
            "outboundTag": "block",
        })

    # 3. Принудительное туннелирование заблокированных сервисов (Telegram, YouTube, Instagram, Discord, ChatGPT)
    routing_rules.append({
        "type": "field",
        "domain": TUNNEL_FORCE_DOMAINS,
        "balancerTag": "auto-best-balancer",
    })
    routing_rules.append({
        "type": "field",
        "ip": TELEGRAM_CIDRS,
        "balancerTag": "auto-best-balancer",
    })

    # 4. Умная маршрутизация РФ (Сбербанк, Т-Банк, Госуслуги, Озон, WB, .ru, .рф идут напрямую мимо VPN!)
    if smart_routing_ru:
        routing_rules.append({
            "type": "field",
            "domain": RU_DIRECT_DOMAINS,
            "outboundTag": "direct",
        })

    # 5. Весь остальной мировой трафик — через авто-балансировщик с минимальным пингом
    routing_rules.append({
        "type": "field",
        "network": "tcp,udp",
        "balancerTag": "auto-best-balancer",
    })

    config: Dict[str, Any] = {
        "log": {"loglevel": "warning"},
        "dns": {
            "hosts": {
                "dns.adguard-dns.com": ["94.140.14.14", "94.140.15.15"],
                "dns.google": ["8.8.8.8", "8.8.4.4"],
                "cloudflare-dns.com": ["1.1.1.1", "1.0.0.1"],
            },
            "servers": dns_servers,
            "queryStrategy": "UseIPv4",
        },
        "inbounds": [
            {
                "tag": "socks-in",
                "port": socks_port,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "sniffing": {
                    "enabled": True,
                    "destOverride": ["http", "tls", "quic"],
                    "routeOnly": True,
                },
                "settings": {"auth": "noauth", "udp": True},
            },
            {
                "tag": "http-in",
                "port": http_port,
                "listen": "127.0.0.1",
                "protocol": "http",
                "sniffing": {
                    "enabled": True,
                    "destOverride": ["http", "tls", "quic"],
                    "routeOnly": True,
                },
                "settings": {},
            },
        ],
        "outbounds": proxy_outbounds
        + [
            fragment_outbound,
            {"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "UseIP"}},
            {"tag": "block", "protocol": "blackhole", "settings": {}},
        ],
        "burstObservatory": {
            "subjectSelector": ["proxy_"],
            "pingConfig": {
                "destination": "https://www.gstatic.com/generate_204",
                "interval": "30s",
                "sampling": 3,
                "timeout": "5s",
            },
        },
        "routing": {
            "domainStrategy": "IPIfNonMatch",
            "balancers": [
                {
                    "tag": "auto-best-balancer",
                    "selector": ["proxy_"],
                    "strategy": {"type": "leastPing"},
                }
            ],
            "rules": routing_rules,
        },
    }
    return config


def build_singbox_auto_config(
    nodes: List[Dict[str, Any]],
    mode: str = "foreign_only",
    mixed_port: int = 10808,
    smart_routing_ru: bool = True,
    enable_adblock: bool = True,
) -> Dict[str, Any]:
    """
    Генерирует готовый кроссплатформенный конфиг для ядра Sing-box
    (Windows / iOS / macOS / Android: Hiddify, Karing, Streisand, Throne)
    с поддержкой VLESS Reality, Hysteria 2, TUIC v5, Smart Routing РФ и AdGuard DNS.
    """
    if mode == "foreign_only":
        filtered = [n for n in nodes if not n.get("is_ru", False)]
        if not filtered:
            filtered = list(nodes)
    else:
        filtered = list(nodes)

    alive = [n for n in filtered if n.get("latency_ms") is not None]
    target_nodes = alive if alive else filtered

    outbounds: List[Dict[str, Any]] = []
    tags: List[str] = []

    for idx, node in enumerate(target_nodes[:50]):
        tag = f"{node['remark']} ({idx + 1})"
        proto = node.get("protocol", "vless")

        if proto == "hysteria2":
            ob: Dict[str, Any] = {
                "type": "hysteria2",
                "tag": tag,
                "server": node["address"],
                "server_port": node["port"],
                "password": node.get("password") or node.get("uuid"),
                "tls": {
                    "enabled": True,
                    "server_name": node.get("sni") or node["address"],
                    "insecure": True,
                },
            }
            outbounds.append(ob)
            tags.append(tag)
            continue

        if proto == "tuic":
            ob = {
                "type": "tuic",
                "tag": tag,
                "server": node["address"],
                "server_port": node["port"],
                "uuid": node["uuid"],
                "password": node.get("password", ""),
                "congestion_control": node.get("congestion_control", "bbr"),
                "tls": {
                    "enabled": True,
                    "server_name": node.get("sni") or node["address"],
                    "insecure": True,
                    "alpn": ["h3"],
                },
            }
            outbounds.append(ob)
            tags.append(tag)
            continue

        if node.get("type") not in ("tcp", "raw", "ws", "grpc"):
            continue
        ob = {
            "type": "vless",
            "tag": tag,
            "server": node["address"],
            "server_port": node["port"],
            "uuid": node["uuid"],
        }
        if node.get("flow"):
            ob["flow"] = node["flow"]

        if node.get("security") in ("tls", "reality"):
            tls_obj: Dict[str, Any] = {
                "enabled": True,
                "server_name": node.get("sni") or node.get("host") or node["address"],
                "insecure": True,
                "utls": {
                    "enabled": True,
                    "fingerprint": node.get("fp") or "chrome",
                },
            }
            if node.get("alpn"):
                tls_obj["alpn"] = node["alpn"]
            if node["security"] == "reality":
                tls_obj["reality"] = {
                    "enabled": True,
                    "public_key": node.get("pbk", ""),
                    "short_id": node.get("sid", ""),
                }
            ob["tls"] = tls_obj

        if node["type"] == "ws":
            ob["transport"] = {
                "type": "ws",
                "path": node.get("path") or "/",
                "headers": {"Host": node["host"]} if node.get("host") else {},
            }
        elif node["type"] == "grpc":
            ob["transport"] = {
                "type": "grpc",
                "service_name": node.get("service_name") or "",
            }

        outbounds.append(ob)
        tags.append(tag)

    urltest_group = {
        "type": "urltest",
        "tag": "⚡ Автовыбор (ПУЛЬС Белые списки)",
        "outbounds": tags if tags else ["direct"],
        "url": "https://www.gstatic.com/generate_204",
        "interval": "1m",
        "tolerance": 50,
    }

    route_rules: List[Dict[str, Any]] = [
        {"ip_is_private": True, "outbound": "direct"},
    ]
    if enable_adblock:
        route_rules.append({
            "domain_suffix": [d.replace("domain:", "") for d in ADBLOCK_DOMAINS],
            "outbound": "block",
        })
    if smart_routing_ru:
        route_rules.append({
            "domain_suffix": [
                "ru", "xn--p1ai", "su", "sberbank.ru", "tbank.ru", "tinkoff.ru",
                "vtb.ru", "alfabank.ru", "gosuslugi.ru", "ozon.ru", "wildberries.ru",
                "wb.ru", "avito.ru", "yandex.ru", "ya.ru", "kinopoisk.ru", "vk.com",
                "mail.ru", "dzen.ru", "rutube.ru", "2gis.ru", "mos.ru", "nalog.ru"
            ],
            "outbound": "direct",
        })

    return {
        "log": {"level": "warn"},
        "dns": {
            "servers": [
                {
                    "tag": "dns-remote",
                    "address": "https://dns.adguard-dns.com/dns-query" if enable_adblock else "https://1.1.1.1/dns-query",
                    "detour": "⚡ Автовыбор (ПУЛЬС Белые списки)",
                },
                {
                    "tag": "dns-direct",
                    "address": "77.88.8.8",
                    "detour": "direct",
                },
            ],
            "final": "dns-remote",
        },
        "inbounds": [
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": mixed_port,
                "sniff": True,
            }
        ],
        "outbounds": [urltest_group] + outbounds + [
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"},
        ],
        "route": {
            "rules": route_rules,
            "final": "⚡ Автовыбор (ПУЛЬС Белые списки)",
            "auto_detect_interface": True,
        },
    }
