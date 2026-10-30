#!/usr/bin/env bash
# ATS Telegram bot installer / updater.
# Run on the VPN server (where 3x-ui is installed):
#   curl -fsSL https://raw.githubusercontent.com/designbild-maker/ats-android-source/main/bot/install.sh | sudo bash
# Running it again updates the bot and keeps the settings in /opt/ats-bot/.env.
set -euo pipefail

SRC="https://raw.githubusercontent.com/designbild-maker/ats-android-source/main/bot/ats_bot.py"
DIR=/opt/ats-bot
ENV_FILE="$DIR/.env"
SERVICE=/etc/systemd/system/ats-bot.service

if [ "$(id -u)" -ne 0 ]; then
  echo "Запустите от root (через sudo)." >&2
  exit 1
fi

if ! command -v python3 >/dev/null; then
  echo "Устанавливаю python3..."
  apt-get update -qq && apt-get install -y -qq python3
fi
python3 - <<'PY' || { echo "Нужен Python 3.9 или новее." >&2; exit 1; }
import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)
PY

id atsbot >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin atsbot
mkdir -p "$DIR"

echo "Скачиваю бота..."
curl -fsSL "$SRC" -o "$DIR/ats_bot.py.new"
python3 -m py_compile "$DIR/ats_bot.py.new"
mv "$DIR/ats_bot.py.new" "$DIR/ats_bot.py"

ask() { # ask VAR "question" default [secret]
  local __var=$1 __q=$2 __def=${3:-} __secret=${4:-} __ans
  if [ -n "$__secret" ]; then
    read -r -s -p "$__q: " __ans </dev/tty; echo
  else
    read -r -p "$__q${__def:+ [$__def]}: " __ans </dev/tty
  fi
  printf -v "$__var" '%s' "${__ans:-$__def}"
}

# Re-enter all settings:  curl -fsSL .../install.sh | sudo RECONFIGURE=1 bash
if [ "${RECONFIGURE:-0}" = "1" ]; then rm -f "$ENV_FILE"; fi

if [ ! -f "$ENV_FILE" ]; then
  echo
  echo "Первичная настройка. Пароли и токен вводятся невидимо и хранятся только на этом сервере."
  IP_GUESS=$(hostname -I 2>/dev/null | awk '{print $1}')
  ask BOT_TOKEN   "Токен бота от @BotFather" "" secret
  ask ADMIN_ID    "Ваш Telegram ID (от @userinfobot)" ""
  ask PANEL_URL   "Адрес панели 3x-ui на этом сервере (http://127.0.0.1:ПОРТ/ПУТЬ)" ""
  ask PANEL_USER  "Логин панели 3x-ui" ""
  ask PANEL_PASS  "Пароль панели 3x-ui" "" secret
  ask SERVER_IP   "IP-адрес сервера (как в ссылке подписки)" "$IP_GUESS"
  ask SUB_PATH    "Путь подписки (часть ссылки между портом и ключом)" "sub"
  SUB_PATH=${SUB_PATH#/}; SUB_PATH=${SUB_PATH%/}
  ask SUB_BASE_URL "Начало ссылки подписки" "https://$SERVER_IP:2096/$SUB_PATH/"
  ask INBOUND_PORTS "Порты подключений ATS (через запятую)" "443,8443"
  ask DEVICE_LIMIT "Устройств на один ключ" "3"
  umask 077
  cat >"$ENV_FILE" <<EOF
BOT_TOKEN=$BOT_TOKEN
ADMIN_ID=$ADMIN_ID
PANEL_URL=$PANEL_URL
PANEL_USER=$PANEL_USER
PANEL_PASS=$PANEL_PASS
SUB_BASE_URL=$SUB_BASE_URL
INBOUND_PORTS=$INBOUND_PORTS
DEVICE_LIMIT=$DEVICE_LIMIT
EOF
  echo "Настройки сохранены в $ENV_FILE"
else
  echo "Настройки уже есть ($ENV_FILE), оставляю как есть."
fi

chown -R atsbot:atsbot "$DIR"
chmod 700 "$DIR"
chmod 600 "$ENV_FILE"

cat >"$SERVICE" <<EOF
[Unit]
Description=ATS Telegram bot
After=network-online.target x-ui.service
Wants=network-online.target

[Service]
User=atsbot
WorkingDirectory=$DIR
ExecStart=/usr/bin/env python3 $DIR/ats_bot.py
Restart=always
RestartSec=10
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$DIR
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable ats-bot >/dev/null 2>&1
systemctl restart ats-bot
sleep 4
echo
echo "=== Последние строки журнала бота ==="
journalctl -u ats-bot -n 15 --no-pager || true
echo
if systemctl is-active --quiet ats-bot; then
  echo "Готово: бот работает. Напишите ему в Telegram /admin."
else
  echo "Бот не запустился — пришлите строки журнала выше." >&2
fi
