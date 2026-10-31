# Rockstat Android SDK: пример и модифицированный Mixpanel SDK

[🇬🇧 English](README.md) · 🇷🇺 Русский

> **В этом репозитории лежит модифицированная версия Mixpanel Android SDK, адаптированная под
> аналитический бэкенд Rockstat.** Это не официальный проект Mixpanel, Mixpanel его не
> поддерживает, и отправлять данные в сервис Mixpanel он не предназначен. Форк SDK находится в
> [`sdk/`](sdk/README.ru.md), Android-приложение в корне репозитория — отладочный стенд для него.

## Структура

| Путь | Что это |
| --- | --- |
| `/` (корень) | Отладочное приложение: кнопка на каждый вызов SDK, панель статуса с разрешённой конфигурацией и живое зеркало logcat-вывода SDK. |
| [`sdk/`](sdk/README.ru.md) | Форк [mixpanel/mixpanel-android](https://github.com/mixpanel/mixpanel-android) 8.12.0, добавлен как `git subtree`. Публикуется как `com.mixpanel.android:mixpanel-android:8.13.0-rockstat.1` (те же координаты, что у оригинала, подменяется без правок кода). |

## Что изменено в форке

Подробности: [`sdk/README.ru.md`](sdk/README.ru.md), [`sdk/analytics/CHANGELOG.md`](sdk/analytics/CHANGELOG.md),
[`sdk/context/AUTOTRACK.md`](sdk/context/AUTOTRACK.md).

1. **`$ae_session` переживает убийство процесса.** В оригинале событие отправляется из Runnable с
   задержкой 500 мс после паузы последней Activity; если процесс убит раньше (смахивание из
   «Недавних», нехватка памяти, краш), событие не уходит никогда. Форк сохраняет текущий
   foreground-отрезок и при следующем запуске досылает незавершённый с последним известным временем.
2. **`$mp_session_id` не рвётся на коротких уходах в фон и перезапусках.** В оригинале он
   пересоздаётся при каждом выходе на передний план. Форк сохраняет его, пока пауза короче нового
   ключа манифеста `com.mixpanel.android.MPConfig.SessionIdleTimeout` (по умолчанию 30 мин,
   `0` = поведение оригинала).
3. **Автоматические события по желанию** через `MixpanelOptions.Builder.autoTrackOptions(AutoTrackOptions)`
   по образцу автокапчи PostHog / Amplitude / Firebase / Segment (таблица ниже). Приложение включает
   все группы в `DebugApplication.java`.

### Автоматические события (`AutoTrackOptions`)

| Группа | События | Ключевые свойства |
| --- | --- | --- |
| lifecycle | `Application Installed`, `Application Updated`, `Application Opened`, `Application Backgrounded` | `version`, `build`, `from_background`, `session_length_s`, `previous_version`, `previous_build`; убийство процесса на переднем плане досылается с `reason=process_killed` |
| sessions | `Session Started`, `Session Ended` (датируется задним числом при таймауте или смерти процесса) | `reason`, `session_length_s`; супер-свойства `$session_id`, `$session_start` у каждого события |
| screens | `$screen`, `Screen Left` | `$screen_name`, `$screen_class`, `duration_s` |
| deep links | `Deep Link Opened` | `url`, `referrer` |
| system | `OS Updated`, `Memory Warning`, `Configuration Changed`, `Network Status Changed`, `Time Zone Changed`, `Battery Low` / `Battery Okay` | версия ОС и патч, уровни памяти, ориентация / тёмная тема / локаль / масштаб шрифта, тип сети и metered, часовой пояс, процент заряда |
| ANR | `ANR Detected` (главный поток заблокирован дольше 5 с по умолчанию) | `blocked_ms`, `main_thread_stack` |
| install referrer | `Install Attributed` (нужна библиотека `com.android.installreferrer:installreferrer`, в приложении она есть) | `referrer`, `referrer_click_ts`, `install_begin_ts` |

Плюс события самого SDK: `$ae_first_open`, `$ae_updated`, `$ae_session`, `$ae_crashed`
(`TRACK_AUTOMATIC_EVENTS`) и `$mp_click` / rage / dead clicks (`ENABLE_AUTOCAPTURE`).
`MixpanelAPI.getAutoTrackSessionId()` возвращает текущий `$session_id`, он показан в панели статуса.

Что не собирается и почему: удаление приложения и очистка данных (определяются только на сервере),
push-уведомления (в примере нет FCM), экраны фрагментов (фрагментов здесь нет; подключите
`FragmentLifecycleCallbacks`, если они у вас есть), сетевые запросы (нужен OkHttp-интерцептор),
покупки (нужен Play Billing).

## Отладочное приложение

| Секция | Кнопки | Вызовы SDK |
| --- | --- | --- |
| Events | Track event, Start/Stop timed event, Flush | `track()`, `timeEvent()`, `flush()` |
| Identity | Identify, Reset, Opt out / Opt in | `identify()`, `reset()`, `optOutTracking()` / `optInTracking()` |
| People | Update profile, Track charge | `People.set()`, `People.increment()`, `People.trackCharge()`, `registerSuperProperties()` |
| Feature flags | Load flags | `getFlags().loadFlags()`, `getFlags().getAllVariantsSync()` |

Свойства события вводятся как JSON-объект; свойство `counter` добавляется автоматически. Нижняя
панель показывает все строки logcat с тегами `MixpanelAPI.*`, так что запросы, ответы и
автособытия видны без отладчика.

### Конфигурация

* **Токен проекта**: `DebugApplication.MIXPANEL_API_TOKEN`.
* **Всё остальное** задаётся через `<meta-data>` в `AndroidManifest.xml`: отладочный лог, интервал
  отправки 5 с, порог пакета 5 записей, включённый `$app_open` и endpoint'ы приёмника Rockstat в
  `EventsEndpoint` / `PeopleEndpoint` / `GroupsEndpoint` (раскомментируйте `FlagsEndpoint`, чтобы
  проксировать и фичефлаги). Ключ `UseIpAddressForGeolocation` оставьте незаданным: когда он есть,
  SDK дописывает `?ip=0|1` к кастомным URL, когда его нет, URL используются как есть.

## Сборка

Требования: JDK 17, Android SDK platform 35 и build-tools 35.0.0 (сам форк SDK собирается под 34).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # или путь к вашему JDK 17
(cd sdk && ./gradlew :analytics:publishToMavenLocal)   # 1. собрать форк в ~/.m2
./gradlew assembleDebug                                 # 2. собрать приложение против него
adb install -r build/outputs/apk/debug/sample-android-mixpanel-integration-debug.apk
```

В `local.properties` параметр `sdk.dir` должен указывать на ваш Android SDK.

Подтянуть изменения оригинального Mixpanel SDK в форк:

```sh
git subtree pull --prefix=sdk https://github.com/mixpanel/mixpanel-android.git master --squash
```

## История

Репозиторий начинался как `sample-android-mixpanel-integration` от Mixpanel для SDK 5.x
(push-уведомления, in-app сообщения, endpoint `decide`). Эти возможности исчезли из SDK в 6.0 / 7.0,
поэтому Firebase, `TakeoverInAppActivity` и `MixpanelFCMMessagingService` удалены
(`google-services.json` больше не используется и игнорируется git). Затем пример был переведён на SDK 8.12.0, превращён в отладочный стенд и, наконец,
объединён с форком SDK под Rockstat в `sdk/`.

## Лицензия

Apache 2.0, как и у оригинала. Mixpanel — торговая марка Mixpanel, Inc.; проект не связан с
Mixpanel и не одобрен ею.
