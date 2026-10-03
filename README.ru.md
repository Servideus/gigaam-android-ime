[English documentation](README.md).

# GigaAM Android IME

Android-клавиатура (IME) для офлайн-диктовки на русском языке на базе GigaAM v3 e2e-CTC.

## Проверенная бета

Версия 0.1.2 исправляет переключение клавиатуры на Android 8. Сборки debug/release и Android lint прошли; остаются 71 предупреждение lint. Диктовка с моделью int8 проверена на Infinix X6833B: пользователь подтвердил появление текста в поле ввода. Подробности и границы проверки: [протокол](docs/verification.md).

Код приложения распространяется по [MIT](LICENSE). Авторство GigaAM и лицензии зависимостей описаны в [THIRD_PARTY.md](THIRD_PARTY.md). Модели скачиваются отдельно и не входят в APK.

## Что реализовано

- Собственное IME-приложение на Android (`InputMethodService`).
- Клавиатура с раскладками RU/EN, переключением языка, `Shift`, `Backspace`, `Enter`, цифрами и символами.
- Голосовой ввод с кнопкой микрофона:
  - старт записи,
  - стоп записи,
  - распознавание и вставка текста в активное поле.
- Экран настроек:
  - выбор модели (`int8` / `full`),
  - скачивание/удаление модели,
  - установка активной модели,
  - переключение профиля производительности,
  - аппаратное ускорение (экспериментально),
  - режим прогрева модели.
- Загрузка моделей с проверкой SHA-256.
- Нативное Rust-ядро (`native/gigaam_core`) + JNI-мост для инференса.

## Модели

- `gigaam-v3-e2e-ctc-int8`
  - `v3_e2e_ctc.int8.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`
- `gigaam-v3-e2e-ctc`
  - `v3_e2e_ctc.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`

Каталог моделей (URL, SHA-256, размер) задан в:

- `app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt`

## Требования для сборки

- Android Studio / Android SDK
- JDK 17
- Rust (stable)
- `cargo-ndk`
- Android SDK Platform 35 и NDK 27.2.12479018
- Rust target `aarch64-linux-android` (`rustup target add aarch64-linux-android`)

Установка `cargo-ndk`:

```bash
cargo install cargo-ndk
```

## Сборка

### Вариант 1 (рекомендуется): через Gradle

```powershell
.\gradlew.bat assembleDebug
```

Gradle сам вызывает сборку Rust на этапе `preBuild`.

На Windows задайте `ANDROID_HOME` на свой SDK и `ANDROID_NDK_HOME` на его каталог `ndk/27.2.12479018`. Для полной проверки используйте:

```powershell
./scripts/verify-build.ps1
```

Скрипт собирает debug/release и запускает lint. Он задаёт отдельный временный каталог сокетов Java, чтобы обойти ошибку `Unable to establish loopback connection` при коротком имени Windows в `TEMP`, и восстанавливает переменную окружения после проверки.

### Вариант 2: вручную собрать Rust `.so`, потом Gradle

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-rust-android.ps1 -Abi arm64-v8a -Profile release
.\gradlew.bat assembleDebug -PskipRustBuild=true
```

APK после сборки:

- `app/build/outputs/apk/debug/app-debug.apk`

## Установка и запуск на устройстве

1. Установите APK на телефон.
2. Откройте приложение настроек GigaAM IME.
3. Выдайте доступ к микрофону.
4. Скачайте выбранную модель и сделайте её активной.
5. В системных настройках Android включите клавиатуру `GigaAM Keyboard`.
6. Выберите её через переключатель клавиатур и начните ввод.

## Параметры сборки

- Пропустить сборку Rust:
  - `-PskipRustBuild=true`
- Выбрать ABI:
  - `-PrustAbi=arm64-v8a`
- Профиль Rust:
  - `-PrustProfile=release`
  - `-PrustProfile=debug`

Настройка находится в:

- `app/build.gradle.kts`

## Структура проекта

- `app/` — Android-приложение (UI, IME-сервис, настройки, загрузка моделей).
- `native/gigaam_core/` — Rust-ядро распознавания.
- `scripts/build-rust-android.ps1` — скрипт сборки Rust-библиотек под Android.

## Ограничения MVP

- Основная цель: `arm64-v8a`.
- Модели скачиваются после установки приложения и хранятся во внутреннем хранилище приложения.
