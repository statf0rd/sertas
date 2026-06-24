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

URL не хранится в коде. Задайте один из источников (см. README):
`-Dsertas.server=ws://HOST:8080/signal`, env `SERTAS_SERVER`, или файл
`~/.sertas/server`.

## Безопасность (что усилить)

- Сервер **без аутентификации** и на **plain WS** (не WSS). Кто угодно, зная код
  комнаты, может войти. Медиа при этом всегда шифруется (DTLS), но сам сигналинг
  идёт без TLS.
- Усиление: общий секрет/токен при `join`; **WSS** (домен + Let's Encrypt +
  reverse-proxy nginx/caddy); при необходимости — TURN-сервер (coturn) для
  надёжного NAT-traversal между удалёнными участниками.
