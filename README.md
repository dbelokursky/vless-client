# VLESS Client

<p align="center">
  <img src="src/main/resources/icons/app-icon-256.png" width="128" alt="VLESS Client icon"/>
</p>

Кроссплатформенный клиент (macOS / Windows / Linux) для
VLESS/VMess/Trojan/Shadowsocks на JavaFX — оборачивает
[sing-box](https://github.com/SagerNet/sing-box) и даёт ему удобный GUI с живой
статистикой трафика, импортом share-ссылок, подписками, правилами маршрутизации
и иконкой в трее/меню-баре.

## Платформы

| | macOS | Windows | Linux |
|---|---|---|---|
| Установка | DMG | MSI (per-user) | DEB (`apt install ./vless-client_*.deb`) |
| System Proxy режим | автоматически | автоматически (WinINET) | автоматически на GNOME; на других DE — прокси вручную (локальные порты работают везде) или TUN |
| TUN режим | sudo-NOPASSWD (один пароль) или osascript-промпт | UAC-промпт на каждый Connect | одноразовый `setcap` через PolicyKit (дальше без промптов) или pkexec-промпт на Connect |
| Автостарт | LaunchAgent | реестр Run (нативный exe) | XDG autostart (`~/.config/autostart`) |
| Трей | меню-бар | системный трей | там, где трей есть (KDE/XFCE/…); на стоковом GNOME трея нет — закрытие окна завершает приложение |
| Данные | `~/Library/Application Support/VlessClient` | `%APPDATA%\VlessClient` | `~/.local/share/vless-client` |

Установщики не подписаны: macOS Gatekeeper попросит правый клик → Open,
Windows SmartScreen — «Run anyway», deb — обычное подтверждение apt.

---

## Возможности

- **Протоколы:** VLESS, VMess, Trojan, Shadowsocks (через sing-box)
- **Транспорты:** TCP, WebSocket, gRPC, HTTP/2
- **TLS / Reality / XTLS-Vision**
- **Режимы прокси:**
    - **System Proxy** — sing-box слушает SOCKS/HTTP порт, macOS-настройки
      прокси переключаются автоматически (без прав администратора)
    - **TUN** — полный транспарентный перехват трафика (требует ввода пароля
      для создания TUN-интерфейса)
- **Подписки** — автообновление списков серверов по URL
- **Routing rules** — правила маршрутизации (geoip, geosite, domain, ruleset)
- **Share-ссылки** — импорт/экспорт `vless://`, `vmess://`, `trojan://`, `ss://`
- **Латентность** — замер пинга до всех серверов одной кнопкой
- **Трафик** — живая статистика upload/download через Clash API
- **Меню-бар (tray)** — иконка в menu bar с быстрыми действиями: подключиться,
  выбрать сервер, показать окно, выйти
- **Хоткеи** — `⌘N` добавить сервер, `⌘K` подключиться/отключиться и т.д.
- **Темы** — светлая / тёмная
- **Языки** — русский / английский

---

## Установка и первый запуск

### Быстрый старт (для разработчиков)

```bash
git clone <repo-url>
cd vless-client
mvn clean javafx:run
```

При первой сборке Maven автоматически скачает `sing-box` (версия пиннится в
[singbox.properties](src/main/resources/singbox.properties)) для обеих
архитектур (arm64 + amd64) в `target/classes/native/darwin-{arch}/` с проверкой
SHA-256. Эти бинари бандлятся в jar и извлекаются при первом запуске.

### Если бандл недоступен

Если приложение запущено без build-time бандлинга (например, голый jar без
`generate-resources`), при старте появится модальный диалог, который скачает
`sing-box` с GitHub Releases и закэширует в
`~/Library/Application Support/VlessClient/bin/sing-box`. Загрузка защищена
SHA-256 проверкой.

### Если нет сети

В диалоге-установщике есть подсказка с командой:

```bash
brew install sing-box
```

После ручной установки перезапусти приложение или нажми **Retry download** в
оранжевом баннере на Dashboard — оно подхватит бинарь из стандартных
Homebrew-путей (`/opt/homebrew/bin`, `/usr/local/bin`) или из `$PATH`.

---

## Как пользоваться

### 1. Добавить сервер

**Вариант A — вручную.** Открой вкладку **Servers**, нажми **Add Server**,
заполни форму (address, port, UUID, transport, TLS и т.д.) и сохрани.

**Вариант B — через share-ссылку.** На вкладке **Servers** нажми **Import Link**
и вставь `vless://...` / `vmess://...` / `trojan://...` / `ss://...` — поля
заполнятся автоматически.

**Вариант C — через подписку.** Открой вкладку **Subscriptions**, добавь URL
подписки. Список серверов обновится автоматически и будет периодически
пересинхронизироваться.

### 2. Выбрать активный сервер

На вкладке **Servers** кликни по серверу в списке — он получит бейдж
**ACTIVE**. Это и есть сервер, который будет использован при нажатии
Connect.

Правый клик по серверу даёт контекстное меню: **Edit**, **Duplicate**,
**Copy Share Link**, **Delete**.

### 3. Выбрать режим прокси

На вкладке **Dashboard** выпадающий список **Mode**:

- **System Proxy** (по умолчанию) — sing-box открывает SOCKS5/HTTP прокси,
  настройки macOS прокси переключаются автоматически. Работает без пароля.
  Приложения, которые уважают системный прокси (Safari, Chrome, большинство
  CLI-инструментов), начнут ходить через туннель.

- **TUN** — sing-box создаёт виртуальный сетевой интерфейс `utun*`. **Весь**
  трафик компьютера идёт через туннель (включая приложения, которые игнорируют
  системный прокси). Требует пароля администратора — macOS покажет промпт через
  `osascript`.

### 4. Подключиться

Большая кнопка **Connect** на Dashboard — одно нажатие. Цвет индикатора:
- 🟢 зелёный = подключено
- 🟠 оранжевый = подключается
- 🔴 красный = ошибка
- ⚪ серый = отключено

Хоткей: `⌘K`.

### 5. Мониторинг

**Dashboard** показывает живую статистику:
- Upload / Download speed
- Total Up / Total Down
- Кнопка **Test Latency** — замеряет пинг до всех серверов

**Logs** — вкладка со стримом логов sing-box (с фильтром по уровню).

### 6. Меню-бар

Иконка в меню-баре macOS даёт быстрые действия без открытия главного окна:
- Show window
- Connect / Disconnect
- Select server
- Quit

Закрытие главного окна (`⌘W` или красный кружок) НЕ выходит из приложения —
оно продолжает жить в меню-баре. Выход — через `Quit` в меню-баре или `⌘Q`.

---

## Горячие клавиши

| Hotkey | Действие                                |
|--------|-----------------------------------------|
| `⌘K`   | Connect / Disconnect                    |
| `⌘N`   | Add server                              |
| `⌘1`   | Dashboard                               |
| `⌘2`   | Servers                                 |
| `⌘3`   | Subscriptions                           |
| `⌘4`   | Routing                                 |
| `⌘5`   | Logs                                    |
| `⌘,`   | Settings                                |
| `⌘W`   | Hide window (продолжает работать в tray)|
| `⌘Q`   | Quit                                    |

---

## Настройки (Settings)

| Поле                     | Описание                                              |
|--------------------------|-------------------------------------------------------|
| Theme                    | Light / Dark                                          |
| Language                 | Russian / English                                     |
| Mixed port               | Порт SOCKS/HTTP прокси (по умолчанию `2080`)          |
| Clash API port           | Порт для статистики трафика (по умолчанию `9090`)     |
| Auto-connect on start    | Подключаться к активному серверу при запуске          |
| Check for updates        | Периодическая проверка обновлений приложения          |
| Allow LAN                | Разрешить коннекты к прокси из локальной сети         |

Данные хранятся в `~/Library/Application Support/VlessClient/`:
- `settings.json` — настройки
- `servers.json` — список серверов
- `subscriptions.json` — подписки
- `routing.json` — правила маршрутизации
- `bin/sing-box` — кэш авто-загруженного бинаря (если был)

---

## Разработка

### Требования

- JDK 25 (проект использует preview features)
- Maven 3.9+
- bash + curl + tar (стандартно для macOS) — нужны для `generate-resources`
  чтобы скачать sing-box

### Команды

```bash
mvn clean javafx:run        # запуск в dev-режиме
mvn clean package           # сборка shade-jar (с бандлом sing-box)
mvn test                    # все тесты
mvn test -Dtest=SingBoxInstallerTest   # один тест-класс
mvn validate                # checkstyle
```

### Регенерация иконки

```bash
java --source 25 scripts/GenerateAppIcon.java
```

Генерирует PNG 16/32/64/128/256/512/1024 в `src/main/resources/icons/`.
Правь дизайн в [GenerateAppIcon.java](scripts/GenerateAppIcon.java).

### Обновление sing-box

Версия и SHA-256 живут в одном файле —
[singbox.properties](src/main/resources/singbox.properties). Его читают
pom.xml (properties-maven-plugin), [scripts/bundle-singbox.sh](scripts/bundle-singbox.sh)
и SingBoxInstaller, так что разъехаться они не могут. Бамп — одна команда:

```bash
scripts/bump-singbox.sh 1.13.14   # качает tarballs, сверяет SHA-256 с digest из GitHub API, обновляет properties
mvn clean verify -Psmoke          # полные тесты + smoke на реальном бинарнике
```

Smoke-профиль (`-Psmoke`,
[SingBoxRealBinarySmokeTest](src/test/java/com/vlessclient/service/SingBoxRealBinarySmokeTest.java))
прогоняет реальный бинарник: точное совпадение версии с пином,
`sing-box check` для всех протоколов × режимов × роутинг-пресетов и живой
`run` с проверкой clash_api и http-inbound. CI гоняет его на каждом PR и
перед сборкой DMG.

Минорные обновления sing-box (1.13 → 1.14) ломают схему конфига — сначала
мигрируй [SingBoxConfigGenerator.java](src/main/java/com/vlessclient/service/SingBoxConfigGenerator.java)
по [миграционному гайду](https://sing-box.sagernet.org/migration/), потом бампай.

Пользователи получают патчи ядра и без релиза приложения — через
Settings → About → «Проверить обновления» (обновления в пределах минорной
ветки, с валидацией `sing-box check` и откатом).

### Структура

```
src/main/java/com/vlessclient/
├── app/            # Launcher, VlessClientApp, ServiceLocator, I18n, AppVersion
├── model/          # POJOs: ServerConfig, AppSettings, Subscription, Routing...
├── service/        # SingBoxEngine, SingBoxInstaller, ConfigStore,
│                   # SubscriptionService, RoutingService, LatencyTester,
│                   # TrafficMonitor, TrayIconService, UpdateManager, ...
└── ui/view/        # JavaFX контроллеры для каждой вкладки

src/main/resources/
├── fxml/           # FXML-разметка
├── css/            # light.css, dark.css
├── i18n/           # messages_en.properties, messages_ru.properties
└── icons/          # app-icon-{16..1024}.png

scripts/
├── bundle-singbox.sh       # скачивает sing-box на этапе mvn generate-resources
└── GenerateAppIcon.java    # генератор иконки приложения
```

---

## Траблшутинг

**«sing-box binary not found» при запуске**
Используется старая сборка без bundling. Запусти `mvn clean compile` (это
запустит `generate-resources` и скачает sing-box) или дождись установщика при
первом запуске.

**Connect кнопка недоступна**
Нет активного сервера — проверь, что на вкладке Servers один из серверов помечен
бейджем **ACTIVE** (клик по строке сервера).

**«Process exited unexpectedly (code N)»**
sing-box упал. Посмотри вкладку **Logs** — там будет причина. Частые ошибки:
неверный UUID, неподходящий transport, недоступный сервер, конфликт портов.

**TUN-режим запрашивает пароль каждый раз**
Так и должно быть — создание TUN-интерфейса требует root. Пароль запрашивает
стандартный macOS `osascript`.

**Порт 2080 занят**
Смени `Mixed port` в настройках на свободный.

---

## Лицензия

TBD. sing-box лицензируется под [GPL-3.0](https://github.com/SagerNet/sing-box/blob/main/LICENSE).
