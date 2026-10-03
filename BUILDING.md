# Сборка и запуск

Автор оригинального кода и модификаций: DysseuX / DysseuX Group.
Лицензии компонентов перечислены в [LICENSING.md](LICENSING.md).

## Android

Текущие версии: exstereo **1.8.1-i18n1 / 32** и HRTF по фото **0.2.0 / 2**.
Оба приложения автоматически используют русский интерфейс, если первый язык
в системном списке Android — `ru`, и английский во всех остальных случаях.
Ручного переключателя языка нет.

Поддерживаемая среда сборки — Linux с systemd user manager. Нужны Git,
Python 3, JDK 17 или новее, make, pkg-config и стандартные util-linux/coreutils.
Установите через Android SDK Manager:

- Android SDK Platform 36 и Build Tools 36.0.0;
- Android NDK 28.2.13676358;
- CMake 3.22.1 и Android SDK Platform Tools.

Каждый блок команд ниже выполняйте из корня репозитория. Сначала задайте свой SDK и соберите плеер:

```bash
python3 scripts/setup-android.py --sdk "$HOME/Android/Sdk"
(cd android-player && bash build-limited.sh :app:assembleFossDebug)
```

Плеер: `android-player/app/build/outputs/apk/foss/debug/musicplayer-32-foss-debug.apk`.
Первый запуск собирает FFmpeg и другие native-зависимости из включённых исходников.
Gradle/Maven скачивают недостающие библиотеки. Операции идут последовательно с
ограничениями из [RESOURCE_LIMITS.md](RESOURCE_LIMITS.md).

Отдельное приложение HRTF, после настройки SDK тем же скриптом:

```bash
(cd android-hrtf && bash build-limited.sh :app:assembleDebug)
```

Результат: `android-hrtf/app/build/outputs/apk/debug/app-debug.apk`.
Модели ONNX и SOFA-шаблон уже включены; пересоздавать их для сборки не требуется.

Имена файлов в Releases: `exstereo-v32.apk` (тег `player-v32`) и `hrtf-photo-v2.apk`
(тег `hrtf-v0.2.0`). Это имена публикуемых копий; пути результатов Gradle указаны выше.

Debug-сборки подписываются локальным ключом Android SDK. Чужая самостоятельно
собранная версия обычно не устанавливается обновлением поверх APK автора:
ключи разные. Ключ подписи и пароли в репозиторий не добавляются.

## Проверки

Тесты и сборку APK лучше запускать отдельными командами: после тестов процесс
Gradle завершится и освободит память перед компиляцией native-библиотек.

Проверки интерфейса и DSP плеера без личных аудиофайлов:

```bash
(cd android-player && bash build-limited.sh :app:testFossDebugUnitTest --tests 'org.fossify.musicplayer.*')
```

HRTF:

```bash
(cd android-hrtf && bash build-limited.sh :app:testDebugUnitTest)
```

Для дополнительных декодерных тестов сначала создайте синтетические файлы
скриптами `android-player/tools/make_format_fixtures.py` и
`make_seek_fixtures.py`; эти проверки не входят в указанную команду плеера.
Физическое Android-устройство и прослушивание остаются отдельной проверкой.

## Kubuntu

Системные зависимости устанавливаются отдельно:

```bash
sudo apt install python3-venv python3-tk ffmpeg libsndfile1 build-essential pkg-config
```

Апмиксер (Python 3.10+):

```bash
bash kubuntu-upmixer/setup.sh
bash kubuntu-upmixer/launch.sh --check
bash kubuntu-upmixer/launch.sh
```

HRTF (Python 3.11+):

```bash
bash kubuntu-hrtf/setup.sh
bash kubuntu-hrtf/launch.sh --check
bash kubuntu-hrtf/launch.sh
```

Setup требует интернета и создаёт отдельные venv в каталоге данных текущего
пользователя. Demucs загружает веса при первом выборе модели. Музыка и фото
обрабатываются локально. Настройка HRTF не обновляет окружение Demucs.
Дополнительные параметры описаны в README соответствующей программы.
