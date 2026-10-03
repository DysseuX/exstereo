# Происхождение нативных зависимостей

Зависимости хранятся локально: CMake не скачивает код во время сборки.
Связка с приложением описана в `app/src/main/cpp/CMakeLists.txt`.

## AndroidX Media3 FFmpeg audio decoder

`media3-ffmpeg-1.9.2/` — четыре Java-класса и JNI официального аудиодекодера
AndroidX Media3 1.9.2, Apache 2.0. Источники, способ получения, хеши
адаптированных файлов и изменения описаны в `UPSTREAM.json` и
`LOCAL_CHANGES.md` этой папки. Нативная часть использует уже закреплённый
FFmpeg 8.1.3. В минимальную сборку добавлены только AAC/ALAC/FLAC/Opus/
Vorbis/MP3/AC-3/E-AC-3 декодеры; контейнеры по-прежнему разбирает Media3. Сборка модулей
не скачивает сторонние файлы. APK включает `libffmpegJNI.so` для обеих ABI.

## FreeSurround из Dolphin

- Источник: [Dolphin, Externals/FreeSurround](https://github.com/dolphin-emu/dolphin/tree/233b2dfe6d6427bed6874d641dfab04ccac2974e/Externals/FreeSurround).
- Зафиксированная ревизия Dolphin: `233b2dfe6d6427bed6874d641dfab04ccac2974e`.
- Локальные файлы: `freesurround/source/`, `freesurround/include/`,
  `freesurround/CMakeLists.txt`, `freesurround/COPYING`.
- Автор FreeSurround: Christian Kothe; лицензия **GPL-2.0-or-later**, см.
  [COPYING](freesurround/COPYING) и уведомления в исходных файлах.
- Включённый Kiss FFT: Mark Borgerding, **BSD-3-Clause**; полный текст условий
  сохранён в заголовках файлов Kiss FFT.

Локальная правка в `freesurround/source/FreeSurroundDecoder.cpp`:
при сдвиге перекрывающихся участков выходного буфера вызов
`memcpy(&outbuf[0], &outbuf[C * N / 2], N * C * 4)` заменён на `memmove`
с теми же аргументами. Это устраняет неопределённое поведение копирования;
формулы разделения каналов не менялись. Остальная адаптация — порядок каналов,
буферизация, JNI и параметры обработки — находится в `app/src/main/cpp/`.
Исходный CMake-файл Dolphin сохранён для происхождения; приложение использует
собственную сборку перечисленных исходников.

## Steam Audio 4.8.1

- Источник SDK и готовых библиотек: [официальный выпуск Valve v4.8.1](https://github.com/ValveSoftware/steam-audio/releases/tag/v4.8.1).
- Исходный код выпуска: [ValveSoftware/steam-audio, v4.8.1](https://github.com/ValveSoftware/steam-audio/tree/v4.8.1).
- Версия подтверждается `steam-audio/include/phonon_version.h`: **4.8.1**.
- SHA-256 архива SDK, зафиксированный при подключении:
  `4a0aa5ec1176f38f0b0993a37c2259d9e86f27e22d5e24f83ec4c3cb9a1d5449`.
- Основная лицензия Steam Audio: **Apache-2.0**, см.
  [LICENSE.md](steam-audio/LICENSE.md). У включённых сторонних компонентов
  свои условия, сохранённые в [THIRDPARTY.md](steam-audio/THIRDPARTY.md).

Здесь сохранены заголовки C API и готовые `libphonon.so` для Android arm64-v8a,
Android x86_64 и Linux x86_64. Linux-библиотека нужна только для локальных
проверок DSP; в APK попадают Android-библиотеки. Библиотеки взяты из SDK,
в рамках этого проекта они ещё не пересобирались из исходного кода.

## Контрольные суммы локальных файлов

SHA-256 рассчитаны по файлам в этом каталоге 22 сентября 2026 года. Они
фиксируют используемые байты, включая локальную правку FreeSurround, и сами
по себе не являются результатом аудита безопасности или доказательством
воспроизводимости готовых библиотек Valve.

```text
05b953634ad38fb6b1307cadd4d3be78e61c07205e38db958789ca14f4c70487  steam-audio/lib/android/arm64-v8a/libphonon.so
35a3221019823541eb981f8c6a117b65218ffe5cb5232d56b4835984036d06c2  steam-audio/lib/android/x86_64/libphonon.so
faf7c4d69e0f104366f6553cda38d3f946f761f7db223baf12663f6fbf2387da  steam-audio/lib/linux-x64/libphonon.so
cfab67684fe1ed75a16bb9bccb81c31276b6fb3bad904073407db900045229e7  steam-audio/include/phonon.h
ed3a14daa8a32c5884400ff12fe9acaee80b324279450f16083519cfc25270b1  steam-audio/include/phonon_version.h
7aa7538f8660a480d1f7f325aa9c093e18e6cbfa2bdd1e6008fd550e5b0d0497  freesurround/source/FreeSurroundDecoder.cpp
```

Источники, условия использования и контрольные суммы SOFA-профилей HRTF
находятся отдельно в `app/src/main/assets/hrtf/README.txt` и `sources.json`.

## Дополнительные движки, 22.09.2026

- OpenAL Soft 1.25.2: исходники `openal-soft/`, SHA и локальная CMake-правка
  в `openal-soft/SPATIAL_PROVENANCE.md`. Статический software loopback со
  встроенным HRTF, без аппаратных backend. Описание: `../OPENAL_BACKEND.md`.
- FFmpeg 8.1.3 и libmysofa 1.3.5: локальные исходники с сохранёнными
  лицензиями, архивы в `archives/`. Подпись FFmpeg проверена. Версии, SHA,
  состав и три локальных исправления: `../FFMPEG_BACKEND.md`.
  Минимальные статические сборки для Linux/arm64-v8a/x86_64 производятся
  `tools/build_filter_deps.py`; результаты `.native-deps/` — кэш текущего ПК.
- PAE/PAD — собственная реализация опубликованной Wiener-модели на
  имеющемся KissFFT. Формулы, источники и отличия: `../PAE_PAD.md`.

## StereoWidener, 28.09.2026

- Автор: Orchisama Das; источник [orchidas/StereoWidener](https://github.com/orchidas/StereoWidener/tree/8964075a97e6aa3b2b5264b93fb2f84847581295).
- Зафиксирован commit `8964075a97e6aa3b2b5264b93fb2f84847581295`, 10.05.2025.
- Полный небольшой снимок исходников: `stereowidener/`; SHA каждого из
  43 файлов — `stereowidener/UPSTREAM.json`. Архив codeload SHA-256:
  `af832989a03647ab199879646701bc9de14a1ced7fb0764a3fdb033112cb7d79`.
- CC0-1.0 сохранена в `stereowidener/LICENSE`.
- В Android собирается только адаптированный DSP
  `app/src/main/cpp/stereo_widener.cpp`. JUCE, Python, notebooks и
  установщики upstream не запускаются и в APK не входят.
- Адаптация, исходные формулы и исправления: `../STEREOWIDENER_PORT.md`.
  Результат статического аудита: `../STEREOWIDENER_SECURITY.md`.
- FFmpeg whitelist дополнен локальными аудиофильтрами `extrastereo`,
  `stereowiden`, `haas`; декодеры, программы и сеть этим не включаются.
