# Авторы и происхождение

**DysseuX / DysseuX Group** — собственные приложения проекта, интерфейсы, интеграция компонентов и изменения для exstereo.

Плеер основан на [Fossify Music Player 1.8.1](https://github.com/FossifyOrg/Music-Player/tree/1.8.1) и использует [Fossify Commons](https://github.com/FossifyOrg/Commons). exstereo — отдельная модификация; это не официальный выпуск Fossify.

Основные сторонние компоненты:

- **Christian Kothe**, участники Dolphin — [FreeSurround](https://github.com/dolphin-emu/dolphin/tree/233b2dfe6d6427bed6874d641dfab04ccac2974e/Externals/FreeSurround).
- **Mark Borgerding** — Kiss FFT.
- **Valve Corporation** и участники — [Steam Audio](https://github.com/ValveSoftware/steam-audio).
- **Chris Robinson** и участники — [OpenAL Soft](https://github.com/kcat/openal-soft).
- Участники [FFmpeg](https://ffmpeg.org), [AndroidX Media3](https://github.com/androidx/media) и [libmysofa](https://github.com/hoene/libmysofa).
- **Orchisama Das** — [StereoWidener](https://github.com/orchidas/StereoWidener), источник адаптированных allpass- и velvet-noise-алгоритмов.
- **Moitah / J. D. Purcell**, алгоритм **Avery Lee / VirtualDub** — [CenterCut](https://www.moitah.net/). Подход к маршрутизации SurroundUpmix описан автором **dietboby**; исходный PowerShell-скрипт в эту публикацию не включён.
- **Alexandre Défossez**, Meta и участники — [Demucs](https://github.com/adefossez/demucs), используемый отдельной программой Upmixer.
- **Riccardo Miccini и Simone Spagnol** — [ITSADIVE Hybrid Structural Model](https://github.com/miccio-dk/itsadive_hybrid_structural_model), предобученные модели для оценки ушных фильтров.
- **Simone Spagnol, Riccardo Miccini и Rúnar Unnthórsson** — [Viking HRTF dataset v2](https://doi.org/10.5281/zenodo.4160401). Опорные данные адаптированы для акустического расчёта; получаемые профили не являются исходными измерениями VIKING.
- **University of York** — [SADIE HRTF](https://sofacoustics.org/data/database/sadie/). У включённых H3 и H10 разные лицензии.
- **Bill Gardner и Keith Martin** — [MIT KEMAR](https://sofacoustics.org/data/database/mit/), Gardner and Martin (1995).
- **Microsoft Corporation** и участники — [ONNX Runtime](https://github.com/microsoft/onnxruntime).

Приложения HRTF изменяют исследовательский конвейер ITSADIVE: обработка двух ушей, преобразование моделей для Android, синтез FIR и расчёт задержек. Упоминание авторов исследований не означает, что они одобряют или поддерживают эти приложения.

Полные copyright, тексты лицензий и дополнительные участники сохранены в исходных файлах, каталогах `third_party` и лицензиях Android-приложений. Области действия лицензий — в [LICENSING.md](LICENSING.md).
