# Деплой сигналинг-сервера

Сигналинг-сервер развёрнут на VPS **your-vps** и работает постоянно
(systemd, автозапуск при загрузке, авто-рестарт при падении).

| Параметр | Значение |
|----------|----------|
| Хост | `your-vps` (`root@localhost`) |
| ОС | Ubuntu 20.04 x86_64 |
| Java | Temurin 21 в `/opt/java21` |
| Приложение | `/opt/sertas-signaling/` |
| systemd-сервис | `sertas-signaling` |
| URL | `ws://localhost:8080/signal` |

## Управление сервисом (на VPS)

```bash
systemctl status sertas-signaling      # статус
systemctl restart sertas-signaling     # перезапуск
systemctl stop sertas-signaling        # остановить
journalctl -u sertas-signaling -f      # живые логи
```

## Обновление (выкатить новую версию сервера)

С локальной машины:

```bash
cd ~/Desktop/sertas
./gradlew :signaling-server:installDist
tar -czf /tmp/sertas-signaling.tar.gz -C signaling-server/build/install signaling-server
scp /tmp/sertas-signaling.tar.gz your-vps:/tmp/
ssh your-vps '
  systemctl stop sertas-signaling
  rm -rf /opt/sertas-signaling /tmp/signaling-server
  tar -xzf /tmp/sertas-signaling.tar.gz -C /tmp
  mv /tmp/signaling-server /opt/sertas-signaling
  systemctl start sertas-signaling
'
```

## Безопасность (текущее состояние и что усилить)

- Сервер **без аутентификации** и на **plain WS** (не WSS). Кто угодно, зная код
  комнаты, может войти. Медиа при этом всегда шифруется (DTLS), но сам сигналинг
  идёт без TLS.
- Усиление на будущее: общий секрет/токен при `join`, перевод на **WSS** (домен +
  Let's Encrypt + reverse-proxy nginx/caddy), при желании — TURN-сервер (coturn)
  на этом же VPS для надёжного NAT-traversal между удалёнными участниками.
