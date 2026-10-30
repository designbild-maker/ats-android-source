#!/usr/bin/env python3
"""
ATS Telegram bot: hands out ATS access keys from a 3x-ui panel.

Flow: a user asks for access -> the admin gets a request with "1 / 3 / 6 / 12 months"
buttons -> the bot creates (or extends) the user's clients in the 3x-ui inbounds, all
sharing one subscription ID, with a device limit -> the user gets the subscription link.
Users can check their subscription, download the app and write to support; the admin
answers support messages by replying to them. Payments (Telegram Stars) come later.

Standard library only (Python 3.9+). Configuration comes from /opt/ats-bot/.env.
"""

import html
import json
import os
import random
import secrets
import sqlite3
import string
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.cookiejar import CookieJar

# --------------------------------------------------------------------------- config


def load_env(path):
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


HERE = os.path.dirname(os.path.abspath(__file__))
load_env(os.path.join(HERE, ".env"))

BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
ADMIN_ID = int(os.environ.get("ADMIN_ID", "0") or 0)
PANEL_URL = os.environ.get("PANEL_URL", "").rstrip("/")
PANEL_USER = os.environ.get("PANEL_USER", "")
PANEL_PASS = os.environ.get("PANEL_PASS", "")
# 3x-ui v3+: API token from Settings -> Security -> API Token (preferred over login/password).
PANEL_TOKEN = os.environ.get("PANEL_TOKEN", "")
SUB_BASE_URL = os.environ.get("SUB_BASE_URL", "")
if SUB_BASE_URL and not SUB_BASE_URL.endswith("/"):
    SUB_BASE_URL += "/"
INBOUND_PORTS = [int(p) for p in os.environ.get("INBOUND_PORTS", "443,8443").split(",") if p.strip()]
DEVICE_LIMIT = int(os.environ.get("DEVICE_LIMIT", "3"))
DB_PATH = os.environ.get("DB_PATH", os.path.join(HERE, "ats_bot.db"))
REMIND_DAYS = (3, 1)

PLANS = [1, 3, 6, 12]  # months
DAY_MS = 24 * 3600 * 1000

# --------------------------------------------------------------------------- helpers


def log(*args):
    print(datetime.now().strftime("%Y-%m-%d %H:%M:%S"), *args, flush=True)


def esc(text):
    return html.escape(str(text or ""), quote=False)


def months_word(n):
    if n % 10 == 1 and n % 100 != 11:
        return f"{n} месяц"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return f"{n} месяца"
    return f"{n} месяцев"


def days_word(n):
    if n % 10 == 1 and n % 100 != 11:
        return f"{n} день"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return f"{n} дня"
    return f"{n} дней"


def plan_ms(months):
    return (365 if months == 12 else 30 * months) * DAY_MS


def fmt_date(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).astimezone().strftime("%d.%m.%Y")


def now_ms():
    return int(time.time() * 1000)


def new_sub_id():
    alphabet = string.ascii_lowercase + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(16))


# --------------------------------------------------------------------------- storage


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS users (tg_id INTEGER PRIMARY KEY, username TEXT, "
            "name TEXT, sub_id TEXT, created INTEGER, reminded TEXT)"
        )
        self.db.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)")
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS support (admin_msg_id INTEGER PRIMARY KEY, tg_id INTEGER)"
        )
        self.db.commit()

    def upsert_user(self, tg_id, username, name):
        self.db.execute(
            "INSERT INTO users (tg_id, username, name, created) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(tg_id) DO UPDATE SET username=excluded.username, name=excluded.name",
            (tg_id, username, name, int(time.time())),
        )
        self.db.commit()

    def user(self, tg_id):
        row = self.db.execute(
            "SELECT tg_id, username, name, sub_id, reminded FROM users WHERE tg_id=?", (tg_id,)
        ).fetchone()
        if not row:
            return None
        return dict(zip(("tg_id", "username", "name", "sub_id", "reminded"), row))

    def users_with_keys(self):
        rows = self.db.execute(
            "SELECT tg_id, username, name, sub_id, reminded FROM users WHERE sub_id IS NOT NULL"
        ).fetchall()
        return [dict(zip(("tg_id", "username", "name", "sub_id", "reminded"), r)) for r in rows]

    def set_sub_id(self, tg_id, sub_id):
        self.db.execute("UPDATE users SET sub_id=? WHERE tg_id=?", (sub_id, tg_id))
        self.db.commit()

    def set_reminded(self, tg_id, value):
        self.db.execute("UPDATE users SET reminded=? WHERE tg_id=?", (value, tg_id))
        self.db.commit()

    def get(self, key):
        row = self.db.execute("SELECT v FROM kv WHERE k=?", (key,)).fetchone()
        return row[0] if row else None

    def put(self, key, value):
        self.db.execute(
            "INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (key, value)
        )
        self.db.commit()

    def link_support(self, admin_msg_id, tg_id):
        self.db.execute(
            "INSERT OR REPLACE INTO support (admin_msg_id, tg_id) VALUES (?, ?)", (admin_msg_id, tg_id)
        )
        self.db.commit()

    def support_user(self, admin_msg_id):
        row = self.db.execute("SELECT tg_id FROM support WHERE admin_msg_id=?", (admin_msg_id,)).fetchone()
        return row[0] if row else None


# --------------------------------------------------------------------------- 3x-ui panel


class PanelError(Exception):
    pass


class Panel:
    """Minimal 3x-ui API client (login with a session cookie, inbounds and clients)."""

    def __init__(self, base, user, password, token=""):
        self.base = base
        self.user = user
        self.password = password
        self.token = token
        self.csrf = None
        self.v3 = None
        self.jar = CookieJar()
        # The panel listens on localhost: never send this traffic through a proxy.
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar)
        )
        self.logged_in = False

    def _call(self, method, path, form=None, body=None):
        url = self.base + path
        headers = {"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        elif self.csrf and method != "GET":
            headers["X-CSRF-Token"] = self.csrf
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=20) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raise PanelError(f"HTTP {e.code} on {path}")
        except urllib.error.URLError as e:
            raise PanelError(f"panel unreachable: {e.reason}")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            raise PanelError(f"not JSON on {path} (wrong PANEL_URL or not logged in)")

    def _fetch_csrf(self):
        """3x-ui v3 protects cookie sessions with a CSRF token from GET /csrf-token."""
        try:
            res = self._call("GET", "/csrf-token")
        except PanelError:
            return None
        obj = res.get("obj") if isinstance(res, dict) else None
        if isinstance(obj, str):
            return obj
        for src in (obj, res):
            if isinstance(src, dict):
                for key in ("csrfToken", "token", "csrf"):
                    if isinstance(src.get(key), str):
                        return src[key]
        return None

    def login(self):
        if self.token:
            self.logged_in = True
            return
        self.csrf = self._fetch_csrf()
        creds = {"username": self.user, "password": self.password}
        try:
            res = self._call("POST", "/login", body=creds)
        except PanelError:
            # Older 3x-ui versions expect a form-encoded login.
            res = self._call("POST", "/login", form=creds)
        if not res.get("success"):
            raise PanelError("login failed: " + str(res.get("msg", "")))
        self.logged_in = True
        # Some versions issue a fresh CSRF token bound to the new session.
        self.csrf = self._fetch_csrf() or self.csrf

    def api(self, method, path, body=None):
        for attempt in (1, 2):
            if not self.logged_in:
                self.login()
            try:
                res = self._call(method, path, body=body)
            except PanelError:
                if attempt == 1:
                    self.logged_in = False
                    continue
                raise
            if not res.get("success"):
                if attempt == 1:
                    self.logged_in = False
                    continue
                raise PanelError(f"{path}: {res.get('msg', 'error')}")
            return res.get("obj")
        raise PanelError(path)

    @staticmethod
    def _as_dict(value):
        """3x-ui v2 returns settings as JSON strings, v3 as objects."""
        if isinstance(value, dict):
            return value
        if isinstance(value, str) and value.strip():
            try:
                return json.loads(value)
            except json.JSONDecodeError:
                return {}
        return {}

    def is_v3(self):
        """v3 has a separate clients API (one client attached to several inbounds)."""
        if self.v3 is None:
            try:
                self.api("GET", "/panel/api/clients/list")
                self.v3 = True
            except PanelError:
                self.v3 = False
        return self.v3

    def inbounds(self):
        """The inbounds ATS uses (matched by port). In v2 also their client lists."""
        result = []
        for ib in self.api("GET", "/panel/api/inbounds/list") or []:
            if ib.get("port") not in INBOUND_PORTS:
                continue
            settings = self._as_dict(ib.get("settings"))
            stream = self._as_dict(ib.get("streamSettings"))
            result.append({"id": ib["id"], "port": ib["port"], "remark": ib.get("remark", ""),
                           "clients": settings.get("clients") or [], "stream": stream})
        result.sort(key=lambda x: INBOUND_PORTS.index(x["port"]))
        return result

    @staticmethod
    def stream_flow(inbound):
        stream = inbound["stream"]
        if stream.get("network") in ("tcp", "raw") and stream.get("security") == "reality":
            return "xtls-rprx-vision"
        return ""

    @classmethod
    def default_flow(cls, inbound):
        for c in inbound["clients"]:
            if "flow" in c:
                return c.get("flow", "")
        return cls.stream_flow(inbound)

    def check_summary(self):
        """Lines for the admin /check command."""
        ibs = self.inbounds()
        v3 = self.is_v3()
        counts = {}
        if v3:
            for c in self.api("GET", "/panel/api/clients/list") or []:
                for iid in c.get("inboundIds") or []:
                    counts[iid] = counts.get(iid, 0) + 1
        lines = [f"Панель: OK ({'3x-ui v3' if v3 else '3x-ui v2'}). Подключения ATS ({len(ibs)} из {len(INBOUND_PORTS)}):"]
        for ib in ibs:
            n = counts.get(ib["id"], 0) if v3 else len(ib["clients"])
            lines.append(f"• порт {ib['port']} «{ib['remark']}», клиентов: {n}")
        return lines

    # ----- create / extend

    def grant(self, tg_id, sub_id, months, comment):
        """Create or extend the user's access in every ATS inbound. Returns the new expiry (ms)."""
        inbounds = self.inbounds()
        if not inbounds:
            raise PanelError(f"no inbounds on ports {INBOUND_PORTS}")
        if self.is_v3():
            return self._grant_v3(tg_id, sub_id, months, comment, inbounds)
        return self._grant_v2(tg_id, sub_id, months, comment, inbounds)

    def _v3_get(self, email):
        try:
            obj = self.api("GET", f"/panel/api/clients/get/{urllib.parse.quote(email)}")
        except PanelError:
            return None, []
        if not obj:
            return None, []
        client = obj.get("client") if isinstance(obj.get("client"), dict) else obj
        return client, obj.get("inboundIds") or client.get("inboundIds") or []

    def _v3_flow(self, inbounds):
        """Flow used by existing clients of these inbounds (e.g. the admin's own key)."""
        ids = {ib["id"] for ib in inbounds}
        try:
            for c in self.api("GET", "/panel/api/clients/list") or []:
                if ids.issubset(set(c.get("inboundIds") or [])) and c.get("email"):
                    full, _ = self._v3_get(c["email"])
                    if full and "flow" in full:
                        return full.get("flow") or ""
        except PanelError:
            pass
        return next((self.stream_flow(ib) for ib in inbounds if self.stream_flow(ib)), "")

    def _grant_v3(self, tg_id, sub_id, months, comment, inbounds):
        email = f"ats{tg_id}"
        inbound_ids = [ib["id"] for ib in inbounds]
        existing, attached = self._v3_get(email)
        current = int((existing or {}).get("expiryTime") or 0)
        expiry = max(current, now_ms()) + plan_ms(months)
        if existing:
            client = {k: v for k, v in existing.items() if k not in ("inboundIds", "traffic", "uuid")}
            client.setdefault("id", existing.get("uuid"))
            client.update({"expiryTime": expiry, "enable": True, "limitIp": DEVICE_LIMIT})
            self.api("POST", f"/panel/api/clients/update/{urllib.parse.quote(email)}", client)
            missing = [i for i in inbound_ids if i not in attached]
            if missing:
                try:
                    self.api("POST", f"/panel/api/clients/{urllib.parse.quote(email)}/attach",
                             {"inboundIds": missing})
                except PanelError as e:
                    log("attach failed:", e)
        else:
            client = {
                "email": email,
                "subId": sub_id,
                "id": str(uuid.uuid4()),
                "flow": self._v3_flow(inbounds),
                "totalGB": 0,
                "expiryTime": expiry,
                "limitIp": DEVICE_LIMIT,
                "tgId": int(tg_id),
                "comment": comment[:60],
                "enable": True,
            }
            self.api("POST", "/panel/api/clients/add", {"client": client, "inboundIds": inbound_ids})
        return expiry

    def _grant_v2(self, tg_id, sub_id, months, comment, inbounds):
        current = 0
        for ib in inbounds:
            for c in ib["clients"]:
                if c.get("subId") == sub_id:
                    current = max(current, int(c.get("expiryTime") or 0))
        expiry = max(current, now_ms()) + plan_ms(months)
        for ib in inbounds:
            email = f"ats{tg_id}-{ib['port']}"
            existing = next((c for c in ib["clients"] if c.get("email") == email), None)
            if existing:
                client = dict(existing)
                client.update({"expiryTime": expiry, "enable": True, "subId": sub_id,
                               "limitIp": DEVICE_LIMIT})
                key = client.get("id") or client.get("password") or email
                self.api("POST", f"/panel/api/inbounds/updateClient/{urllib.parse.quote(str(key))}",
                         {"id": ib["id"], "settings": json.dumps({"clients": [client]})})
            else:
                client = {
                    "id": str(uuid.uuid4()),
                    "flow": self.default_flow(ib),
                    "email": email,
                    "limitIp": DEVICE_LIMIT,
                    "totalGB": 0,
                    "expiryTime": expiry,
                    "enable": True,
                    "subId": sub_id,
                    "comment": comment[:60],
                    "reset": 0,
                }
                self.api("POST", "/panel/api/inbounds/addClient",
                         {"id": ib["id"], "settings": json.dumps({"clients": [client]})})
        return expiry

    # ----- status

    def status(self, sub_id):
        """Expiry (ms, 0 = none) and used bytes for a subscription, or None if not found."""
        if self.is_v3():
            return self._status_v3(sub_id)
        found = False
        expiry = 0
        emails = []
        for ib in self.inbounds():
            for c in ib["clients"]:
                if c.get("subId") == sub_id:
                    found = True
                    expiry = max(expiry, int(c.get("expiryTime") or 0))
                    emails.append(c.get("email"))
        if not found:
            return None
        used = 0
        for email in emails:
            try:
                t = self.api("GET", f"/panel/api/inbounds/getClientTraffics/{urllib.parse.quote(email)}")
                if t:
                    used += int(t.get("up") or 0) + int(t.get("down") or 0)
            except PanelError:
                pass
        return {"expiry": expiry, "used": used}

    def _status_v3(self, sub_id):
        try:
            rows = self.api("GET", f"/panel/api/clients/get/subId/{urllib.parse.quote(sub_id)}") or []
        except PanelError:
            return None
        if isinstance(rows, dict):
            rows = [rows]
        if not rows:
            return None
        expiry = 0
        used = 0
        for row in rows:
            client = row.get("client") if isinstance(row.get("client"), dict) else row
            expiry = max(expiry, int(client.get("expiryTime") or 0))
            ut = row.get("usedTraffic")
            if isinstance(ut, (int, float)):
                used += int(ut)
            elif isinstance(ut, dict):
                used += int(ut.get("up") or 0) + int(ut.get("down") or 0)
            elif client.get("email"):
                try:
                    t = self.api("GET", f"/panel/api/clients/traffic/{urllib.parse.quote(client['email'])}")
                    if t:
                        used += int(t.get("up") or 0) + int(t.get("down") or 0)
                except PanelError:
                    pass
        return {"expiry": expiry, "used": used}


# --------------------------------------------------------------------------- Telegram


class Telegram:
    def __init__(self, token):
        api = os.environ.get("TELEGRAM_API", "https://api.telegram.org").rstrip("/")
        self.base = f"{api}/bot{token}/"

    def call(self, method, **params):
        data = json.dumps({k: v for k, v in params.items() if v is not None}).encode()
        req = urllib.request.Request(self.base + method, data=data,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=70) as resp:
                res = json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            try:
                res = json.loads(e.read().decode())
            except Exception:
                res = {"ok": False, "description": f"HTTP {e.code}"}
        if not res.get("ok"):
            log("telegram", method, "error:", res.get("description"))
            return None
        return res.get("result")

    def send(self, chat_id, text, keyboard=None, inline=None):
        markup = None
        if inline is not None:
            markup = {"inline_keyboard": inline}
        elif keyboard is not None:
            markup = {"keyboard": [[{"text": t} for t in row] for row in keyboard], "resize_keyboard": True}
        return self.call("sendMessage", chat_id=chat_id, text=text, parse_mode="HTML",
                         disable_web_page_preview=True, reply_markup=markup)


# --------------------------------------------------------------------------- bot logic

BTN_GET = "Получить доступ"
BTN_MY = "Моя подписка"
BTN_APP = "Скачать приложение"
BTN_HELP = "Поддержка"
MENU = [[BTN_GET, BTN_MY], [BTN_APP, BTN_HELP]]


class Bot:
    def __init__(self):
        self.tg = Telegram(BOT_TOKEN)
        self.panel = Panel(PANEL_URL, PANEL_USER, PANEL_PASS, PANEL_TOKEN)
        self.store = Store(DB_PATH)
        self.offset = None
        self.last_reminder_check = 0

    # ----- user side

    def sub_link(self, sub_id):
        return SUB_BASE_URL + sub_id

    def key_message(self, sub_id, expiry):
        return (
            "<b>Ваш ключ ATS готов.</b>\n"
            f"Действует до <b>{fmt_date(expiry)}</b>, до {DEVICE_LIMIT} устройств.\n\n"
            "Ваша ссылка-ключ (нажмите, чтобы скопировать):\n"
            f"<code>{esc(self.sub_link(sub_id))}</code>\n\n"
            "Как подключить:\n"
            "1. Установите ATS (кнопка «Скачать приложение»).\n"
            "2. Скопируйте ссылку выше.\n"
            "3. В ATS нажмите «Вставить ключ из буфера», затем большую кнопку.\n\n"
            "Ключ личный — не пересылайте его другим."
        )

    def on_start(self, chat_id, user):
        self.tg.send(
            chat_id,
            f"Здравствуйте, {esc(user.get('first_name'))}!\n\n"
            "Это бот <b>ATS</b> — личного VPN. Российские сайты работают как обычно, "
            "остальные — через защищённый сервер.\n\n"
            "Нажмите «Получить доступ», чтобы запросить ключ.",
            keyboard=MENU,
        )

    def on_get_access(self, chat_id, user):
        rec = self.store.user(user["id"])
        if rec and rec.get("sub_id"):
            st = self.safe_status(rec["sub_id"])
            if st and (st["expiry"] == 0 or st["expiry"] > now_ms()):
                self.tg.send(chat_id, self.key_message(rec["sub_id"], st["expiry"] or now_ms()),
                             keyboard=MENU)
                self.tg.send(chat_id, "Ключ уже действует. Чтобы продлить, нажмите кнопку ниже.",
                             inline=[[{"text": "Продлить", "callback_data": "renew"}]])
                return
        self.request_access(chat_id, user, renew=bool(rec and rec.get("sub_id")))

    def request_access(self, chat_id, user, renew):
        name = " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x)
        uname = f"@{user['username']}" if user.get("username") else "без username"
        kind = "продление" if renew else "новый доступ"
        buttons = [[{"text": months_word(m), "callback_data": f"ok:{user['id']}:{m}"} for m in PLANS[:2]],
                   [{"text": months_word(m), "callback_data": f"ok:{user['id']}:{m}"} for m in PLANS[2:]],
                   [{"text": "Отклонить", "callback_data": f"no:{user['id']}"}]]
        self.tg.send(ADMIN_ID, f"<b>Запрос: {kind}</b>\n{esc(name)} ({esc(uname)}), id <code>{user['id']}</code>",
                     inline=buttons)
        self.tg.send(chat_id, "Запрос отправлен администратору. Как только он подтвердит, "
                              "я пришлю ваш ключ сюда.", keyboard=MENU)

    def on_my_sub(self, chat_id, user):
        rec = self.store.user(user["id"])
        if not rec or not rec.get("sub_id"):
            self.tg.send(chat_id, "У вас пока нет ключа. Нажмите «Получить доступ».", keyboard=MENU)
            return
        st = self.safe_status(rec["sub_id"])
        if st is None:
            self.tg.send(chat_id, "Не удалось получить данные о подписке. Попробуйте позже "
                                  "или напишите в поддержку.", keyboard=MENU)
            return
        if st["expiry"] == 0:
            line = "Подписка <b>без срока окончания</b>."
        elif st["expiry"] <= now_ms():
            line = f"Подписка <b>закончилась</b> {fmt_date(st['expiry'])}."
        else:
            left = max(0, -(-(st["expiry"] - now_ms()) // DAY_MS))
            line = f"Осталось <b>{days_word(left)}</b> — до {fmt_date(st['expiry'])}."
        used_gb = st["used"] / 1024 ** 3
        self.tg.send(
            chat_id,
            f"{line}\nУстройств по ключу: до {DEVICE_LIMIT}.\nТрафик: {used_gb:.2f} ГБ.\n\n"
            f"Ваша ссылка-ключ:\n<code>{esc(self.sub_link(rec['sub_id']))}</code>",
            inline=[[{"text": "Продлить", "callback_data": "renew"}]],
        )

    def on_app(self, chat_id):
        file_id = self.store.get("apk_file_id")
        if not file_id:
            self.tg.send(chat_id, "Файл приложения скоро появится здесь. Пока напишите в поддержку.",
                         keyboard=MENU)
            return
        self.tg.call("sendDocument", chat_id=chat_id, document=file_id,
                     caption="ATS для Android. Откройте файл и разрешите установку из этого источника.")

    def on_help(self, chat_id):
        self.tg.send(chat_id, "Напишите ваш вопрос одним сообщением — я передам его администратору, "
                              "ответ придёт сюда.", keyboard=MENU)

    def forward_to_admin(self, msg, user):
        uname = f"@{user['username']}" if user.get("username") else f"id {user['id']}"
        header = self.tg.send(ADMIN_ID, f"<b>Поддержка</b> от {esc(user.get('first_name'))} ({esc(uname)}). "
                                        "Ответьте на сообщение ниже, чтобы написать пользователю.")
        fwd = self.tg.call("forwardMessage", chat_id=ADMIN_ID, from_chat_id=msg["chat"]["id"],
                           message_id=msg["message_id"])
        for m in (header, fwd):
            if m:
                self.store.link_support(m["message_id"], user["id"])
        self.tg.send(msg["chat"]["id"], "Сообщение передано в поддержку.", keyboard=MENU)

    # ----- admin side

    def approve(self, tg_id, months, admin_cb):
        rec = self.store.user(tg_id)
        if not rec:
            return "Пользователь не найден (пусть нажмёт /start)."
        sub_id = rec.get("sub_id") or new_sub_id()
        comment = f"tg {rec.get('username') or rec.get('name') or tg_id}"
        expiry = self.panel.grant(tg_id, sub_id, months, comment)
        self.store.set_sub_id(tg_id, sub_id)
        self.store.set_reminded(tg_id, None)
        self.tg.send(tg_id, self.key_message(sub_id, expiry), keyboard=MENU)
        return f"Выдано: {months_word(months)}, до {fmt_date(expiry)}."

    def on_callback(self, cq):
        data = cq.get("data", "")
        user = cq["from"]
        msg = cq.get("message") or {}
        if data == "renew":
            self.tg.call("answerCallbackQuery", callback_query_id=cq["id"])
            self.request_access(user["id"], user, renew=True)
            return
        if user["id"] != ADMIN_ID:
            self.tg.call("answerCallbackQuery", callback_query_id=cq["id"])
            return
        parts = data.split(":")
        try:
            if parts[0] == "ok":
                result = self.approve(int(parts[1]), int(parts[2]), cq)
            elif parts[0] == "no":
                self.tg.send(int(parts[1]), "К сожалению, запрос отклонён. Если это ошибка — "
                                            "напишите в поддержку.", keyboard=MENU)
                result = "Отклонено."
            else:
                result = None
        except PanelError as e:
            result = f"Ошибка панели: {e}"
        self.tg.call("answerCallbackQuery", callback_query_id=cq["id"], text=(result or "")[:190])
        if result and msg:
            self.tg.call("editMessageText", chat_id=msg["chat"]["id"], message_id=msg["message_id"],
                         text=(msg.get("text") or "") + "\n\n" + result)

    def on_admin_command(self, chat_id, text, msg):
        cmd, _, arg = text.partition(" ")
        if cmd == "/check":
            try:
                lines = [esc(x) for x in self.panel.check_summary()]
                lines.append(f"Ссылки подписки: {esc(SUB_BASE_URL)}…")
                self.tg.send(chat_id, "\n".join(lines))
            except PanelError as e:
                self.tg.send(chat_id, f"Панель недоступна: {esc(e)}")
            return True
        if cmd == "/users":
            users = self.store.users_with_keys()
            if not users:
                self.tg.send(chat_id, "Пока никому не выдан ключ.")
                return True
            lines = []
            for u in users:
                st = self.safe_status(u["sub_id"])
                exp = "нет в панели" if st is None else ("бессрочно" if st["expiry"] == 0 else fmt_date(st["expiry"]))
                who = f"@{u['username']}" if u.get("username") else (u.get("name") or "")
                lines.append(f"• {esc(who)} <code>{u['tg_id']}</code> — до {exp}")
            self.tg.send(chat_id, "\n".join(lines[:80]))
            return True
        if cmd == "/give":
            try:
                tg_id, months = (int(x) for x in arg.split())
                self.tg.send(chat_id, self.approve(tg_id, months, None))
            except ValueError:
                self.tg.send(chat_id, "Формат: /give <id> <месяцев>")
            except PanelError as e:
                self.tg.send(chat_id, f"Ошибка панели: {esc(e)}")
            return True
        if cmd in ("/help", "/admin"):
            self.tg.send(chat_id, "Команды администратора:\n/check — проверить связь с панелью\n"
                                  "/users — кому выданы ключи\n/give &lt;id&gt; &lt;месяцев&gt; — выдать/продлить вручную\n"
                                  "Чтобы бот раздавал приложение, пришлите сюда APK-файл.\n"
                                  "Чтобы ответить в поддержку — ответьте на пересланное сообщение.")
            return True
        return False

    # ----- dispatch

    def safe_status(self, sub_id):
        try:
            return self.panel.status(sub_id)
        except PanelError as e:
            log("panel status error:", e)
            return None

    def on_message(self, msg):
        chat_id = msg["chat"]["id"]
        user = msg.get("from") or {}
        if msg["chat"].get("type") != "private" or not user:
            return
        self.store.upsert_user(user["id"], user.get("username"),
                               " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x))
        text = (msg.get("text") or "").strip()
        is_admin = user["id"] == ADMIN_ID

        if is_admin:
            doc = msg.get("document")
            if doc and (doc.get("file_name") or "").lower().endswith(".apk"):
                self.store.put("apk_file_id", doc["file_id"])
                self.tg.send(chat_id, f"Приложение сохранено: {esc(doc.get('file_name'))}. "
                                      "Теперь бот отправляет его по кнопке «Скачать приложение».")
                return
            reply = msg.get("reply_to_message")
            if reply:
                target = self.store.support_user(reply["message_id"])
                if target:
                    self.tg.call("copyMessage", chat_id=target, from_chat_id=chat_id, message_id=msg["message_id"])
                    self.tg.send(target, "— ответ поддержки ATS", keyboard=MENU)
                    self.tg.send(chat_id, "Ответ отправлен.")
                    return
            if text.startswith("/") and self.on_admin_command(chat_id, text, msg):
                return

        if text.startswith("/start"):
            self.on_start(chat_id, user)
        elif text == BTN_GET:
            self.on_get_access(chat_id, user)
        elif text == BTN_MY or text == "/my":
            self.on_my_sub(chat_id, user)
        elif text == BTN_APP:
            self.on_app(chat_id)
        elif text == BTN_HELP or text == "/help":
            self.on_help(chat_id)
        elif not is_admin:
            self.forward_to_admin(msg, user)

    def check_reminders(self):
        for u in self.store.users_with_keys():
            st = self.safe_status(u["sub_id"])
            if not st or st["expiry"] == 0:
                continue
            left = -(-(st["expiry"] - now_ms()) // DAY_MS)
            if left <= 0:
                continue
            sent = set(filter(None, (u.get("reminded") or "").split(",")))
            for d in sorted(REMIND_DAYS):
                if left <= d and f"{st['expiry']}:{d}" not in sent:
                    self.tg.send(u["tg_id"], f"Подписка ATS закончится через <b>{days_word(left)}</b> "
                                             f"({fmt_date(st['expiry'])}). Нажмите «Продлить», чтобы не остаться без доступа.",
                                 inline=[[{"text": "Продлить", "callback_data": "renew"}]])
                    # Mark this and all longer reminders as sent for this expiry date.
                    sent.update(f"{st['expiry']}:{x}" for x in REMIND_DAYS if x >= d)
                    self.store.set_reminded(u["tg_id"], ",".join(sorted(sent)))
                    break

    def run(self):
        missing = [k for k, v in (("BOT_TOKEN", BOT_TOKEN), ("ADMIN_ID", ADMIN_ID), ("PANEL_URL", PANEL_URL),
                                  ("PANEL_LOGIN", PANEL_TOKEN or (PANEL_USER and PANEL_PASS)),
                                  ("SUB_BASE_URL", SUB_BASE_URL)) if not v]
        if missing:
            log("missing settings in .env:", ", ".join(missing))
            sys.exit(1)
        me = self.tg.call("getMe")
        if not me:
            log("cannot reach Telegram or wrong BOT_TOKEN")
            sys.exit(1)
        log(f"started as @{me.get('username')}")
        try:
            log(" / ".join(self.panel.check_summary()))
        except Exception as e:  # never let a panel problem stop the bot
            log("panel check failed:", repr(e))
        self.tg.send(ADMIN_ID, "Бот ATS запущен. Команды администратора: /admin")
        while True:
            try:
                updates = self.tg.call("getUpdates", offset=self.offset, timeout=50,
                                       allowed_updates=["message", "callback_query"]) or []
                for upd in updates:
                    self.offset = upd["update_id"] + 1
                    try:
                        if "message" in upd:
                            self.on_message(upd["message"])
                        elif "callback_query" in upd:
                            self.on_callback(upd["callback_query"])
                    except Exception:
                        log("handler error:", traceback.format_exc())
                if time.time() - self.last_reminder_check > 3600:
                    self.last_reminder_check = time.time()
                    self.check_reminders()
            except Exception:
                log("loop error:", traceback.format_exc())
                time.sleep(5 + random.random() * 5)


if __name__ == "__main__":
    Bot().run()
