# Деплой сигналинг-сервера

Сигналинг-сервер — чистая Java (Javalin/Jetty), нативного кода нет, поэтому
кроссплатформенно собирается на любой машине и запускается на любом Linux-VPS с
Java 21. Ниже — как поднять его как постоянный systemd-сервис.

Подставьте свои значения:

```bash
VPS=your-vps          # ssh-хост (alias из ~/.ssh/config или user@ip)
PORT=8080             # порт сигналинга
```

## 1. Java 21 на VPS (если нет)

```bash
ssh "$VPS" '
  mkdir -p /opt/java21
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse" -o /tmp/jre21.tar.gz
  tar -xzf /tmp/jre21.tar.gz -C /opt/java21 --strip-components=1
  /opt/java21/bin/java -version
'
```

## 2. Собрать и залить дистрибутив

```bash
./gradlew :signaling-server:installDist
tar -czf /tmp/sertas-signaling.tar.gz -C signaling-server/build/install signaling-server
scp /tmp/sertas-signaling.tar.gz "$VPS":/tmp/
ssh "$VPS" '
  rm -rf /opt/sertas-signaling /tmp/signaling-server
  tar -xzf /tmp/sertas-signaling.tar.gz -C /tmp
  mv /tmp/signaling-server /opt/sertas-signaling
'
```

## 3. systemd-сервис (автозапуск + авто-рестарт)

Создать `/etc/systemd/system/sertas-signaling.service` на VPS:

```ini
[Unit]
Description=sertas signaling server
After=network.target

[Service]
Type=simple
Environment=JAVA_HOME=/opt/java21
# Токен доступа: подключения без совпадающего ?token=... отклоняются.
# Сгенерировать: openssl rand -hex 16. Без этой строки сервер пускает всех.
Environment=SERTAS_TOKEN=ВАШ_ТОКЕН
ExecStart=/opt/sertas-signaling/bin/signaling-server 8080
Restart=always
RestartSec=3
User=root
SyslogIdentifier=sertas-signaling

[Install]
WantedBy=multi-user.target
```

```bash
ssh "$VPS" '
  systemctl daemon-reload
  systemctl enable --now sertas-signaling
  systemctl status sertas-signaling --no-pager
'
```

Откройте порт во внешнем фаерволе провайдера/ufw, если включён.

## Управление сервисом

```bash
systemctl status sertas-signaling      # статус
systemctl restart sertas-signaling     # перезапуск
journalctl -u sertas-signaling -f      # живые логи
```

## Указать сервер в приложении

URL не хранится в коде. Задайте один из источников (см. README). Если на сервере
включён `SERTAS_TOKEN`, добавьте токен в URL как query-параметр:

```
ws://HOST:8080/signal?token=ВАШ_ТОКЕН
```

Источники (в порядке приоритета): `-Dsertas.server=...`, env `SERTAS_SERVER`,
файл `~/.sertas/server`.

## Безопасность

- **Токен доступа (реализовано):** при заданном `SERTAS_TOKEN` сервер отклоняет
  подключения без совпадающего `?token=`. Токен едет в URL, в коде/репозитории
  его нет.
- **Шифрование:** медиа всегда шифруется (DTLS-SRTP). Сам сигналинг по умолчанию
  идёт по **plain WS** (не WSS). Для TLS нужен домен: указать его на IP VPS,
  поставить reverse-proxy (Caddy/nginx) с Let's Encrypt и проксировать на
  `localhost:8080`, в приложении использовать `wss://домен/signal?token=...`.
  (Для bare-IP без домена корректный публичный TLS-сертификат не выдаётся.)
- **NAT:** если удалённые участники не соединяются (статус застревает на
  «соединение…») — поднять TURN-сервер (coturn) на этом же VPS.
