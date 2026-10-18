# Сторонние компоненты

Nova распространяется на условиях GNU General Public License v3.0 (см. [LICENSE](LICENSE)).
Перечисленные ниже компоненты остаются под собственными лицензиями своих авторов —
все они совместимы с GPL-3.0.

| Компонент | Лицензия |
| --- | --- |
| [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — каталог `tools/xray-core` | Mozilla Public License 2.0 |
| [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) | MIT (WireGuard LLC) |
| [warp-plus](https://github.com/bepass-org/warp-plus) | MIT |
| [usque](https://github.com/Diniboy1123/usque) — клиент MASQUE для Cloudflare WARP | MIT |
| [connect-ip-go](https://github.com/Diniboy1123/connect-ip-go) — CONNECT-IP поверх HTTP/3 | MIT |
| [quic-go](https://github.com/quic-go/quic-go) | MIT |
| [uTLS](https://github.com/refraction-networking/utls) | BSD-3-Clause |
| [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — слой совместимости прозрачного ретранслятора Telegram | MIT |
| [opera-proxy](https://github.com/Snawoot/opera-proxy) (Snawoot) | MIT |
| [opera-proxy-android-wrapper](https://github.com/SLY-F0X/opera-proxy-android-wrapper) (SLY-F0X) | MIT |
| [tun2proxy](https://github.com/tun2proxy/tun2proxy) | MIT |
| [tor-android](https://github.com/guardianproject/tor-android) (Guardian Project) — сам `tor`, собранный для Android | BSD-3-Clause |
| [tor](https://gitlab.torproject.org/tpo/core/tor) | BSD-3-Clause |
| [jtorctl](https://github.com/guardianproject/jtorctl) — управляющее соединение с `tor` | BSD-3-Clause |
| [lyrebird](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird) — клиенты obfs4, webtunnel и meek_lite | BSD-2-Clause |
| [goptlib](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib) | CC0-1.0 |
| OkHttp, AndroidX, Material Components, Apache Commons Compress | Apache License 2.0 |
| Bouncy Castle | MIT |
| XZ for Java | Public Domain |

Файлы Xray-core остаются под MPL-2.0. MPL — пофайловая лицензия: изменения именно в этих
файлах публикуются на её условиях. MPL-2.0 совместима с GPL-3.0, поэтому распространение
приложения целиком под GPL-3.0 этому не противоречит.

## Шрифты (`app/src/main/res/font`)

Все шрифты ниже распространяются под SIL Open Font License 1.1 (текст лицензии — в каждом
файле, в таблице имён). Файлы не менялись, кроме отмеченных как «подмножество»: из них
оставлены латиница и кириллица, остальные глифы удалены, имена и лицензия сохранены.

| Файл | Семейство | Тема | Источник | Примечание |
| --- | --- | --- | --- | --- |
| `ptmono_regular.ttf` | PT Mono (ParaType) | Matrix | https://fonts.google.com/specimen/PT+Mono | без изменений; RFN «PT Mono» |
| `cormorant_sc_regular.ttf`, `cormorant_sc_bold.ttf` | Cormorant SC (The Cormorant Project Authors) | Path of Exile 1 | https://github.com/CatharsisFonts/Cormorant | подмножество |
| `forum_regular.ttf` | Forum (Denis Masharov) | Path of Exile 2 | https://fonts.google.com/specimen/Forum | подмножество |
| `old_standard_regular.ttf`, `old_standard_bold.ttf` | Old Standard TT (The Old Standard Project Authors) | Diablo II | https://github.com/google/fonts/tree/main/ofl/oldstandardtt | подмножество |
| `play_regular.ttf`, `play_bold.ttf` | Play (Jonas Hecksher, Playtypes; кириллица Cyreal) | Cyber neon | https://fonts.google.com/specimen/Play | без изменений; RFN «Play» |
| `rubik_mono_one.ttf` | Rubik Mono One (The Rubik Project Authors) | GTA VI (только статус и кнопка подключения) | https://fonts.google.com/specimen/Rubik+Mono+One | подмножество |
| `roboto_*.ttf` | Roboto (Google) | по умолчанию и GTA VI | https://fonts.google.com/specimen/Roboto | Apache License 2.0 |

Выбор — ближайшие по духу открытые аналоги к шрифтам игр (Fontin, Exocet, Pricedown-подобный
гротеск), а не сами шрифты игр: их лицензии не открытые.
