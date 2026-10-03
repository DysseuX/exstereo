# Источники SurroundUpmix / CenterCut

Исходные URL и SHA-256 зафиксированы в `sources.json`.

- `SurroundUpmix-v3.1.ps1` — скрипт dietboby из
  [авторской темы](https://forum.videohelp.com/threads/420376-SurroundUpmix-Free-Stereo-to-5-1-7-1-Surround-Upmixer).
  В репозиторий не включён: в опубликованном скрипте не указана отдельная
  явная лицензия. Ссылка и SHA-256 сохранены для атрибуции маршрутизации и
  коэффициентов. Приложение и тесты не читают и не исполняют этот файл.
- `centercut/dsp_centercut.cpp` и `GPL.txt` — исходный Center Cut DSP 1.4.0
  Moitah / J. D. Purcell (2004–2007), алгоритм Avery Lee / VirtualDub.
  [Официальная страница автора](https://www.moitah.net/).
  GPL v2 или более поздняя версия. Исходник сохранён без изменений;
  Python-перенос `centercut.py` и производный численный C++-эталон также
  GPL-2.0-or-later.

`CenterCutCL.exe` версии 1.6.0 опубликован автором в `Center_Cut_GUI.zip`
как .NET 2.0 AnyCPU. Архив GUI не содержит C#-исходников. Для сверки
прочитаны IL методов `CenterCutHelper.RunCenterCut`, `CenterCut.Init`,
`CenterCut.Run`, `CreateRaisedCosineWindow`: окно 8192, перекрытие 4,
полусэмпловый Hann, степень выходного окна 2, порог мощности 1e-15,
неограниченный alpha и нулевые DC/Nyquist. Эти формулы совпадают с
открытым ядром; отличаются нормировка и FFT-библиотека. В runtime нет
.NET, Windows EXE, внешней загрузки кода или заимствованного thread pool.

Входная формула окна: `0.5*(1-cos(2*pi*(n+0.5)/8192))`.
Спектральный центр:
`(L+R) * (0.5 - 0.5*sqrt(|L-R|²/|L+R|²))`, если `|L+R|² > 1e-15`.
Затем IFFT и `Hann² / 1.25`, overlap-add с шагом 2048.
Остатки: `L-center`, `R-center`. **Alpha не обрезается до нуля**:
такой clamp изменил бы оригинальный алгоритм.

`tests/centercut_reference.cpp` использует оригинальный Hartley FFT из
открытого исходника как независимый численный эталон для NumPy FFT.
Это не запуск исходного .NET файла и не побитовое сравнение его полного
конвейера. Описание обработки и команды проверок: [README](../README.md).

Коэффициенты SoX сверены с исходником версии 14.4.2:
[biquads.c](https://github.com/chirlu/sox/blob/sox-14.4.2/src/biquads.c),
[biquad.c](https://github.com/chirlu/sox/blob/sox-14.4.2/src/biquad.c).
Lowpass/highpass по умолчанию Q=sqrt(0.5), treble slope=0.5. В исходном
скрипте `allpass frequency 1.0` не указывает единицу: SoX использует
**ширину 1 Гц**, а не Q=1. Порт сохраняет именно это поведение, хотя
широкополосная фазовая коррекция Q=1 могла бы быть другим вариантом звука.
