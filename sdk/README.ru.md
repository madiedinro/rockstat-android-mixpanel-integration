# Rockstat Android SDK (модифицированный Mixpanel Android SDK)

[🇬🇧 English](README.md) · 🇷🇺 Русский

> **Это модифицированная версия Mixpanel Android SDK, адаптированная только под аналитический
> бэкенд Rockstat. С сервисом Mixpanel она не используется и Mixpanel её не поддерживает.**
>
> Форк отправляет события, свойства и сессии, о которых Mixpanel ничего не знает (`$screen`,
> `Session Started`, `$session_id`, проставленное задним числом `time` у `Application Backgrounded`
> и т. д.), меняет поведение `$ae_session` и `$mp_session_id` и настраивается через кастомные
> `EventsEndpoint` / `PeopleEndpoint` / `GroupsEndpoint`, указывающие на Rockstat. Работа с
> `api.mixpanel.com` не тестировалась и не поддерживается. Не отправляйте issue по этому форку в
> Mixpanel; оригинал — [mixpanel/mixpanel-android](https://github.com/mixpanel/mixpanel-android).

Форк сделан от upstream **8.12.0**. Артефакт: `com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1`
(координаты те же, что у оригинала, поэтому подменяется без правок кода; публикуется в локальный
или приватный Maven, никогда в Maven Central).

Пример приложения, использующий все возможности ниже:
[rockstat-android-mixpanel-integration](https://github.com/rockstat/rockstat-android-mixpanel-integration)
(этот форк лежит там в каталоге `sdk/`).

## Что изменено

### 1. `$ae_session` переживает убийство процесса

В оригинале `$ae_session` отправляется из Runnable, поставленного с задержкой 500 мс после паузы
последней Activity. Если процесс убит раньше (смахивание из «Недавних», нехватка памяти, краш),
сессия не попадает в аналитику. `MixpanelActivityLifecycleCallbacks` в форке сохраняет текущий
foreground-отрезок (`start`, `last_activity`, `in_foreground`) на каждом resume и pause, а при
следующем запуске досылает незавершённый отрезок как `$ae_session` со временем последней активности.
Счётчики профиля `$ae_total_app_sessions` / `$ae_total_app_session_length` увеличиваются как обычно.

### 2. `$mp_session_id` сохраняется через короткий фон и перезапуски

В оригинале сессия в `$mp_metadata` пересоздаётся при каждом выходе на передний план. Форк
сохраняет её, пока пауза с последней активности короче нового ключа манифеста

```xml
<meta-data android:name="com.mixpanel.android.MPConfig.SessionIdleTimeout" android:value="1800000" />
```

(по умолчанию 30 минут; `0` возвращает поведение оригинала). Идентификатор сессии, время старта и
счётчики последовательности сохраняются, поэтому перезапуск процесса внутри окна продолжает ту же
сессию. `SessionMetadata` стал потокобезопасным.

### 3. Автоматические события по желанию (`AutoTrackOptions`)

```java
MixpanelOptions options = new MixpanelOptions.Builder()
        .autoTrackOptions(new AutoTrackOptions.Builder()   // по умолчанию включены все группы
                .systemEvents(true)
                .anrTimeoutMs(5000)
                .sessionIdleTimeoutMs(30 * 60 * 1000)
                .build())
        .build();
MixpanelAPI mp = MixpanelAPI.getInstance(context, TOKEN, trackAutomaticEvents, options);
```

| Группа (переключатель билдера) | События | Ключевые свойства |
| --- | --- | --- |
| `lifecycleEvents` | `Application Installed`, `Application Updated`, `Application Opened`, `Application Backgrounded` | `version`, `build`, `from_background`, `session_length_s`, `previous_version`, `previous_build`; убийство процесса на переднем плане досылается с `reason=process_killed` и прошедшим `time` |
| `sessionEvents` | `Session Started`, `Session Ended` | `reason` (`first_launch`, `timeout`, `reset`), `session_length_s`; `Session Ended` датируется последней активностью, если таймаут замечен позже. Супер-свойства `$session_id`, `$session_start` у каждого события |
| `screenEvents` | `$screen`, `Screen Left` | `$screen_name` (label Activity), `$screen_class`, `duration_s` |
| `deepLinkEvents` | `Deep Link Opened` | `url`, `referrer` |
| `systemEvents` | `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low`, `Battery Okay` | `previous_os_version`/`os_version`/`security_patch`; `level_name`/`available_mb`/`heap_used_mb`; `changed[]`/`orientation`/`dark_mode`/`locale`/`font_scale`; `online`/`transport`/`previous_transport`/`metered`/`validated`; `previous_timezone`/`timezone`; `battery_percent`/`charging` |
| `anrTimeoutMs` (0 = выкл.) | `ANR Detected` | `blocked_ms`, `main_thread_stack` (верхние 12 кадров); не срабатывает при подключённом отладчике |
| `installReferrerEvent` | `Install Attributed` | `referrer`, `referrer_click_ts`, `install_begin_ts`, `google_play_instant`, `install_version`; нужна библиотека `com.android.installreferrer:installreferrer` в приложении (SDK зависит от неё только как `compileOnly` и проверяет наличие класса в рантайме) |

Это обычные вызовы `track()`: флаг `trackAutomaticEvents` на них не влияет, они несут все
супер-свойства и уходят на тот `EventsEndpoint`, который настроен. `MixpanelAPI.reset()`
завершает текущую автоматическую сессию и начинает новую. `MixpanelAPI.getAutoTrackSessionId()`
возвращает текущий `$session_id` (или `null`, если события сессий выключены).

Всё, кроме `AutoTrackOptions` и `getAutoTrackSessionId()`, package-private. Внутреннее устройство и
файлы хранения описаны в [context/AUTOTRACK.md](context/AUTOTRACK.md); конечный автомат сессии
(`AutoTrackSession`) написан на чистой Java и покрыт юнит-тестами в `analytics/src/test`.

### 4. Всё остальное как в оригинале

Фичефлаги, автокапча (`$mp_click`, rage/dead clicks), People, Groups, мост session replay и события
`$ae_*` не изменились относительно 8.12.0. См. [analytics/CHANGELOG.md](analytics/CHANGELOG.md).

## Сборка

Нужны JDK 17 и Android SDK 34 (полный список команд в `CLAUDE.md`).

```sh
./gradlew :analytics:publishToMavenLocal   # ~/.m2/.../mixpanel-android/8.13.0-rockstat.1
./gradlew :analytics:test                  # JVM-тесты (AutoTrackSessionTest и тесты оригинала)
./gradlew :analytics:connectedAndroidTest  # инструментальные тесты, нужно устройство
```

Потребители добавляют `mavenLocal()` (или свой приватный репозиторий) и зависят от
`com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1`.

## Синхронизация с оригиналом

```sh
git remote add upstream https://github.com/mixpanel/mixpanel-android.git
git fetch upstream
git rebase upstream/master   # изменения форка — один коммит поверх оригинала
```

Файлы, затронутые форком: `MixpanelAPI.java`, `MixpanelOptions.java`, `MPConfig.java`,
`SessionMetadata.java`, `MixpanelActivityLifecycleCallbacks.java`, новые `AutoTrack*.java`,
`SystemEventTracker.java`, `AnrWatchdog.java`, `InstallReferrerTracker.java`, `analytics/build.gradle`,
`analytics/gradle.properties`, `analytics/CHANGELOG.md`, `context/AUTOTRACK.md`.

## Модули

| Модуль | Артефакт | Примечание |
| --- | --- | --- |
| [analytics](analytics/README.md) | `com.mixpanel.android:mixpanel-android` | здесь живут изменения форка |
| [common](common/README.md) | `com.mixpanel.android:mixpanel-android-common` | без изменений, берётся из Maven Central |
| [openfeature-provider](openfeature-provider/README.md) | `com.mixpanel.android:mixpanel-android-openfeature` | без изменений |

## Лицензия

Apache 2.0 — см. [LICENSE](LICENSE). Mixpanel — торговая марка Mixpanel, Inc.; форк не связан с
Mixpanel и не одобрен ею.
