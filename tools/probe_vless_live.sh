#!/usr/bin/env bash
#
# Живая проба узлов VLESS с устройства.
#
# Конфигурации берутся те же, что уходят в приложение (`VlessXrayConfig.build`,
# см. LiveConfigExport). На устройстве поднимается обычный бинарник Xray с
# socks-инбаундом, порт пробрасывается на компьютер через `adb forward`, и запрос
# идёт через него. Наружу соединение открывает телефон, то есть проверяется та
# самая сеть, где приложение и работает.
#
# VPN Nova на время пробы должен быть выключен, иначе трафик Xray уйдёт в туннель
# и проверка потеряет смысл.
#
# Подготовка:
#   cd tools/xray-core && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o xray ./main
#   adb push xray /data/local/tmp/xray && adb shell chmod 755 /data/local/tmp/xray
#   ./gradlew testDebugUnitTest --tests '*LiveConfigExport*'
#   adb push tools/probe/live-configs /data/local/tmp/vlesscfg
#
# Использование:
#   tools/probe_vless_live.sh <serial>

set -uo pipefail

SERIAL="${1:?укажите серийный номер устройства}"
PROBE_URL="${PROBE_URL:-https://www.gstatic.com/generate_204}"
REMOTE_DIR="/data/local/tmp/vlesscfg"
INDEX="$(dirname "${BASH_SOURCE[0]}")/probe/live-configs/index.tsv"

if [ ! -f "$INDEX" ]; then
    echo "Нет $INDEX — сначала прогоните LiveConfigExport." >&2
    exit 1
fi

adb -s "$SERIAL" shell "pkill -f /data/local/tmp/xray" >/dev/null 2>&1

while IFS=$'\t' read -r name port target network remark; do
    [ -n "${name:-}" ] || continue
    # stdin у adb и curl закрыт намеренно: иначе они вычитывают остаток index.tsv,
    # и цикл заканчивается после первой строки.
    adb -s "$SERIAL" shell "nohup /data/local/tmp/xray run -c $REMOTE_DIR/$name.json >/data/local/tmp/xray.log 2>&1 &" >/dev/null 2>&1 </dev/null
    sleep 2
    adb -s "$SERIAL" forward "tcp:$port" "tcp:$port" >/dev/null 2>&1 </dev/null
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 12 \
        --proxy "socks5h://127.0.0.1:$port" "$PROBE_URL" 2>/dev/null </dev/null)"
    adb -s "$SERIAL" forward --remove "tcp:$port" >/dev/null 2>&1 </dev/null
    adb -s "$SERIAL" shell "pkill -f /data/local/tmp/xray" >/dev/null 2>&1 </dev/null
    if [ "$code" = "204" ] || [ "$code" = "200" ]; then
        echo "OK      $name  $target  $network  $remark"
    else
        echo "нет     $name  $target  $network  (код: ${code:-нет ответа})"
    fi
done < "$INDEX"
