# Лицензии exstereo

Репозиторий объединяет четыре программы. Публичный доступ к исходникам не означает, что на все файлы распространяется одна лицензия. Краткая карта находится в корневом [LICENSE](LICENSE); ниже указаны области действия. Тексты лицензий и уведомления в конкретных файлах сохраняют силу.

| Часть | Лицензия собственного кода и изменений |
| --- | --- |
| `android-player/` — плеер на основе Fossify Music Player | [GNU GPL version 3](LICENSES/GPL-3.0.txt) |
| `kubuntu-upmixer/` — подготовка стерео и 7.1, включая перенос CenterCut | [GNU GPL version 2 или более поздняя версия](LICENSES/GPL-2.0.txt) |
| `android-hrtf/` — приложение HRTF для Android | [PolyForm Noncommercial 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt) |
| `kubuntu-hrtf/` — приложение HRTF для ПК | [PolyForm Noncommercial 1.0.0](LICENSES/PolyForm-Noncommercial-1.0.0.txt) |
| Общие скрипты в `scripts/` и собственная общая документация | [GNU GPL version 3](LICENSES/GPL-3.0.txt) |

Эта таблица не перелицензирует сторонние файлы в `third_party`, библиотеки, предобученные модели или данные. Автономный bootstrap HRTF относится к собственному HRTF-коду под PolyForm Noncommercial. Общие сборочные скрипты являются отдельными инструментами и сохраняют GPLv3. Дополнительную область действия определяет корневой LICENSE либо уведомление в самом файле.

## GPL-программы

GPL разрешает использование, изменение и распространение, включая коммерческое использование и продажу, при соблюдении её условий. Запрета продавать GPL-код плеера или Upmixer здесь нет.

При распространении сборок нужно обеспечить предусмотренный GPL доступ к соответствующим исходникам, сохранить лицензии и авторские уведомления, обозначить изменения. Ссылка только на исходный Fossify не заменяет исходники изменений exstereo. Практический способ — выпускать APK и ссылку на исходники той же версии вместе.

## Собственный код HRTF

Собственный код двух HRTF-приложений предоставляется по PolyForm Noncommercial 1.0.0. Разрешённые цели и условия распространения определяет [полный текст лицензии](LICENSES/PolyForm-Noncommercial-1.0.0.txt), включая перечисленные в нём личные и организационные применения. При передаче копий сохраняются условия и строки `Required Notice:`.

Это **source-available с некоммерческими условиями**, а не OSI open source. Если предполагаемое использование не покрывается лицензией, требуется отдельное разрешение правообладателя собственного кода. Обращение — к DysseuX / DysseuX Group через страницу проекта.

PolyForm не заменяет MIT у ITSADIVE, CC BY 4.0 у VIKING и лицензии других сторонних компонентов. Она не устанавливает запрет коммерческого использования кода GPL-программ из соседних каталогов.

## Библиотеки, модели и данные

| Компонент | Условия и сохранённые уведомления |
| --- | --- |
| Fossify Music Player / Commons | GPL-3.0; исходное авторство Fossify и участников |
| FreeSurround из Dolphin | GPL-2.0-or-later; Christian Kothe и участники соответствующих проектов |
| CenterCut | GPL-2.0-or-later; Moitah / J. D. Purcell, алгоритм Avery Lee; лицензия в `kubuntu-upmixer/third_party/centercut/` |
| Steam Audio | Apache-2.0 и уведомления зависимостей SDK |
| OpenAL Soft | LGPL-2.0-or-later и отдельные лицензии встроенных зависимостей |
| FFmpeg, Jaudiotagger | LGPL-2.1-or-later; условия конкретной сборки и copyright сохранены |
| Kiss FFT, libmysofa / Speex resampler | BSD-3-Clause |
| StereoWidener | CC0-1.0; происхождение и автор Orchisama Das указаны |
| ITSADIVE, включая используемые исходные модели | MIT; Riccardo Miccini и Simone Spagnol |
| ONNX Runtime | MIT; Microsoft, плюс неизменённые ThirdPartyNotices |
| VIKING HRTF v2 и производные опорные данные | CC BY 4.0; сохраняются авторство, ссылка на источник и указание внесённых изменений |
| SADIE H3 | Некоммерческая лицензия University of York: распространение без платы или иной финансовой выгоды, с копией условий и copyright |
| SADIE H10 | Apache-2.0; University of York |
| MIT KEMAR | В метаданных разрешено использование без ограничений; запрошена ссылка на Gardner and Martin (1995) при исследовательском и коммерческом применении |

Ограничение SADIE H3 относится к этому набору данных. Оно не превращает лицензии остальных файлов в некоммерческие. Наличие H3 в готовом комплекте нужно учитывать отдельно от лицензии исходного кода.

Полные уведомления и перечни:

- [Лицензии компонентов плеера](android-player/app/src/main/assets/licenses/catalog.json) и [происхождение native-зависимостей](android-player/third_party/README.md).
- [Уведомления Android HRTF](android-hrtf/app/src/main/assets/licenses/THIRD-PARTY-ATTRIBUTION.txt), включая изменения данных VIKING и преобразование ITSADIVE в ONNX.
- [ITSADIVE MIT](kubuntu-hrtf/third_party/itsadive/LICENSE) и [закреплённые модели](kubuntu-hrtf/third_party/itsadive/models.json).
- [Происхождение компонентов Upmixer](kubuntu-upmixer/third_party/README.md).

Зависимости, устанавливаемые отдельно через пакетные менеджеры, также сохраняют собственные условия. Авторские уведомления сторонних компонентов не заменяются подписью разработчика exstereo.
