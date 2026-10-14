# DICOM Kit — результаты этапа 5.4

Дата: 15 октября 2026 года. Ветка: `fix/stage-5-1-storage-safety`. База: `bfd363781349bca3dd5bf5ef15abd3199120ea59`. Исходный аудит, таблица всех 26 находок, результаты 5.3 и перечисленные пользователем исходные файлы изучены. Отчёт входит в отдельный локальный коммит 5.4; его хеш доступен через `git log --grep='stage 5.4' -1`. Main не изменяется; push, merge, rebase, squash и force push не выполняются.

## Исправленные дефекты

| ID | Изменение | Доказательство |
|---|---|---|
| P1-09 | Явный `ViewerSource.None/Received/External`; сохранённый external picker URI не имеет приоритета над выбранным Received. Оба экрана активируют свой источник и используют общий viewport | Android: внешний→принятый→внешний, сравнение реальных пикселей и path; fullscreen/back сохраняют выбранный объект |
| P1-10 | Отдельные generation guards для metadata, render и received tags; старые jobs отменяются; старые summary/tags/bitmap убираются сразу при выборе нового файла. Worker проверяет актуальность перед публикацией и при чтении | Задержанный SAF A, выбор B до возврата A, нет прежних данных во время Loading, поздний A не заменяет B. Cache имеет отдельную generation-защиту |
| P1-11 | Разрешение Modality/VOI macros для конкретного frame в Shared/Per-frame Functional Groups, применение frame-specific Slope/Intercept/Window/LUT. Переход кадра сбрасывает окно к параметрам этого кадра | Два Enhanced frame с разными transforms/VOI, shared Modality + per-frame VOI, production ViewModel navigation, отсутствие необоснованного наследования top-level transforms |
| P1-12 | Одна color mapping function для всех размеров; RGB не получает монохромное WW/WL, user invert применяется одинаково при subsampling и обычном размере | Chunky/planar/short reads, RGB шириной 5001, bitmap и per-pixel inversion, сохранение исходного aspect ratio viewport |
| P1-02, Viewer | Byte budget до выделения decoded buffer, один frame cache, serial decode/metadata/render, bounded bitmap и row writes вместо второго полноразмерного ARGB. Отменённые unpublished bitmaps освобождаются | Огромные размеры отклоняются до allocation, repeated cache/frame/window tests; Android repeated opening 7.7 MB RGB и bitmap budget/cancellation |
| P1-18 | Убрана подмена корректного ISO_IR 144 эвристикой CP1251/UTF-8; объявленная успешно декодируемая кодировка соблюдается. Ошибочный UTF-8/необъявленные 8-bit bytes остаются диагностическими предположениями; manual override сохранён | ISO-8859-5 с Ю/Я, UTF-8, ошибочная кодировка, PN `^`/`=`, charset inheritance и локальный charset вложенного SQ |
| P2-03, tags | Общий ограниченный reader для внешних и принятых файлов; глубина/число/размер проверяются до recursion/allocation. Полное дерево SQ хранится без повторного разворачивания всех значений в строку каждого родителя | Deep nesting, count/aggregate/value limits, Private/UN binary preview, recursive search, clipboard exact value, повреждённый/обрезанный файл, post-Pixel tags с кириллицей |
| P1-06, только Viewer tags | Received tag parsing перенесён из синхронной композиции UI в guarded IO job с cache по выбранному path | Общая навигация подписывается на StateFlow; source switch сбрасывает tag cache. Полная индексация архива и другие UI I/O остаются на 5.6 |

## Выбор источника и lifecycle

Один активный источник определяет source, frame, metadata и Ready bitmap. Выбор принятого study переключает источник независимо от ранее открытого внешнего файла. Внешние metadata хранятся отдельно для возврата на вкладку Tags; они не используются для управления принятым изображением. Удалённый выбранный study не подменяется другим объектом молча: render сообщает недоступность.

Отмена Job не считается достаточным доказательством остановки стороннего provider. Generation ID проверяется независимо; некооперативное чтение может задержать новую работу, но поздний ответ не публикуется. Mutex ограничивает число одновременно выполняемых тяжёлых операций одного ViewModel. Cache put имеет свой ticket: результат старого decode не может заполнить cache после clear. WW/WL/Invert получают snapshot параметров запроса, а публикация проверяет актуальность. Zoom/Pan меняют transform, не запускают новый decode.

Дробные DICOM defaults/presets WW/WL сохраняются как Double отдельно от целочисленных UI controls, поэтому invert/redraw/reset не округляют применяемое окно. Android-регрессия проверяет реальные pixels для WC=10.5 / WW=2.5 до и после invert/reset; ручная целочисленная настройка остаётся явным пользовательским выбором.

Full screen использует `DicomSharedViewportBox`, как и оба обычных экрана. Чёрная подложка, светлая Material 3 тема, навигация, PN formatting, frame controls сохранены. Navigation sub-screen/tab и Viewer state сохраняются при пересоздании Activity; отдельный Android-тест также меняет ориентацию устройства в fullscreen. Восстановление после смерти процесса этим этапом не обещается и остаётся частью P2-02.

## Pixel pipeline и поддерживаемые границы

Modality и VOI разрешаются независимо. Shared macro относится ко всем кадрам, Per-frame — к Item выбранного кадра. Один macro одновременно в Shared и Per-frame отклоняется как противоречие; неполный macro не заменяется top-level значениями. Если functional groups присутствуют, отсутствующий macro означает отсутствие соответствующего frame transform, а не наследование произвольного legacy top-level window. При отсутствии groups у classic DICOM продолжают использоваться top-level параметры. Не проводится полная проверка всех IOD.

Основание: [PS3.3 C.7.6.16](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_C.7.6.16.html), [Common Functional Group Macros](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_C.7.6.16.2.html), [PS3.5 Sequence Inheritance](https://dicom.nema.org/medical/dicom/current/output/chtml/part05/sect_7.5.3.html). Использованы правила области действия macro и наследования charset, а не эвристическое исправление файла.

Отсутствующие обязательные Image Pixel fields и RGB Planar Configuration не угадываются. Некорректные Frame Index/NumberOfFrames, Bits Stored/High Bit/Pixel Representation, incomplete Pixel Data, invalid Rescale/Window/LUT и неподдерживаемая VOI function дают явную ошибку без изображения. Real World Value Mapping и известные Enhanced CT/MR/PET без functional groups сообщают ограничение. Сжатые TS, Deflated, Explicit Big Endian, float Pixel Data и другие неподдерживаемые pixel formats не транскодируются и не показываются как достоверный bitmap; технические сведения доступны в тегах при допустимом размере metadata.

MONOCHROME1/2 polarity, signed/unsigned и shifted High Bit сохранены. RGB — только unsigned 8-bit, 3 samples, Planar 0/1. Нативные цвета сохраняются независимо от mono WW/WL, инверсия меняет RGB channels. Планарные каналы читаются одним chunk buffer вместо трёх полноразмерных planes. При уменьшении отображения UI сохраняет aspect ratio исходной матрицы; сам исходный файл не уменьшается.

Decoded frame budget — не более **48 MiB и 1/4 Java max heap**. Bitmap — не более **4,194,304 pixels и 1/8 max heap** в ARGB_8888, размеры не больше texture limit 4096. Рендер пишет bitmap по строкам с одним row buffer и LUT; при превышении лимитов decoded frame выводится ограничение. Большой снимок может отображаться с меньшим разрешением, чем оригинал. Эти пределы уменьшают риск OOM, но не гарантируют отсутствие OOM при внешнем давлении на память. Exception/OOM показываются явно, не выдаются за Success.

Публикуемый bitmap принадлежит UI и не recycle-ится немедленно при замене: Compose может ещё рисовать его. После исчезновения ссылок его освобождает Android. Непубликуемые bitmap при cancel/error recycle-ятся в finally. Cache хранит только один decoded frame; новых неограниченных collections нет.

Pixel mapper использует primitive SAM interface, чтобы не создавать boxed Integer на каждый пиксель. Encapsulated Pixel Data с undefined length под native Transfer Syntax явно отклоняется: Item header не отображается как пиксели. Tag browser показывает undefined length как неизвестный Encapsulated размер, а не как 4294967295 bytes; оба сценария имеют регрессионные JVM-тесты.

## Tags и charset

Parser limits: depth 16, 20000 elements/items, value 1 MiB, aggregate metadata **8 MiB или 1/16 max heap**. Проверки находятся в DicomInputHandler до стандартного readValue, поэтому ограничение после полного разбора дерева не является основной защитой. Превышение сообщает причину; файлы не исправляются. Pixel Data пропускается потоково без materialization, parser продолжает до post-Pixel attributes в том же charset scope. Большой non-Pixel value отклоняется с объяснением вместо обещания неограниченного просмотра. Binary preview ограничен 32 bytes, текстовые значения в пределах лимитов сохраняются для просмотра/копирования.

Корректная объявленная ISO_IR 144 не заменяется другим языково правдоподобным результатом. Для single-byte charset невозможно доказать ошибочную декларацию по похожести текста; пользователь может выбрать manual override, который меняет только отображение. Некорректный UTF-8 и отсутствие declaration при high bytes продолжают сопровождаться предупреждением. PN separators/representations и локальная SQ charset inheritance сохранены. Export security 5.3 этим не меняется.

Оба tag screens используют одну extraction implementation. Recursive search не зависит от дублирования child values в SQ summary; совпадение имени самой sequence сохраняет её содержимое, поиск nested value сохраняет нужную ветвь. При смене tags сбрасываются детали/раскрытие прежнего документа. Старый SCP-тест теперь проверяет вложенный UID в `sequenceItems`, а не его копию в строке родительского SQ; сами значения не потеряны.

## Файлы

Пути относительно `app/src/main/java/ru/medeng/dicomtool/`:

- `domain/model/ViewerSource.kt` — source model и latest-request guard.
- `ui/viewmodel/DicomToolViewModel.kt` — guards, source selection, serial work, frame defaults, received tags IO, bitmap ownership.
- `ui/navigation/DicomToolNavigation.kt`, `ui/components/DicomSharedViewportBox.kt` — activation, StateFlow, original aspect ratio, RGB indication.
- `dicom/pixel/DicomPixelDataDecoder.kt` — frame transforms, validation, streaming planar data, budgets, unified rendering/cancellation, guarded cache.
- `dicom/charset/DicomCharsetDecoder.kt` — declared ISO_IR 144 wins over heuristic.
- `dicom/tags/BoundedDicomRead.kt`, `DicomFileReader.kt` — pre-allocation limits, bounded SQ summaries, post-Pixel tags, explicit malformed raw-stream errors.
- `data/storage/DicomStudyRepository.kt` — bounded indexing and shared reader; удалён дублирующий recursive tag converter.
- `ui/screens/tags/DicomTagsBrowserComponent.kt` — recursive search и stale detail reset.
- `app/src/test/.../ViewerStage54Test.kt` — 30 новых JVM tests; `Dcm4cheCompatibilityTest.kt` — structural SQ assertion.
- `app/src/androidTest/.../ViewerSafetyInstrumentedTest.kt` — 8 новых Android tests; тестовый `Stage51DocumentsProvider.java` — deterministic delayed read controls, только androidTest APK.
- `docs/AUDIT_FINDINGS_STATUS.md`, этот отчёт.

## Проверки и ограничения

Все исследования, имена и идентификаторы синтетические. Чтение/decode/render не пишет в исходный поток. JVM byte comparison и Android readback после frame navigation/WW changes подтверждают неизменность оригинала.

| Проверка | Итог |
|---|---|
| `testDebugUnitTest` | **PASS: 178 tests, 0 failures/errors/skipped**, включая 30 новых и регрессии 5.1–5.3 |
| `assembleDebug` | **PASS**; обычный applicationId `ru.medeng.dicomtool`, version 1.3.1 / code 1 |
| `lintDebug` | **FAIL: прежний UnusedBoxWithConstraintsScope, 49 warnings**; ошибка в `DicomToolNavigation.kt:248` (245 в 5.3), suppression/baseline не добавлялись |
| `connectedDebugAndroidTest -Pstage51Isolated=true` | **PASS: 27 tests, 0 failures/errors/skipped** на чистом API 35 DicomKit_Stage54/emulator-5556; 8 новых и 19 прежних регрессий, финальный прогон после всех исходных изменений |
| Поворот fullscreen | Отдельный повторный Android-тест PASS после восстановления исходной orientation в finally |
| Orthanc regression | PASS: 3 синтетических объекта Explicit / Implicit / Deflated, отдельный сервер TEST_PACS на 59690 с HTTP off и изолированными storage/index |

Сохранён обычный APK `app/build/reports/stage54/dicom-kit-stage-5-4-debug.apk` до instrumentation сборки с отдельным applicationId. SHA-256: `55A1C10357AB2F22138C45E5C75AA8D83F3A2AE220633A280C8E11C5FD7F104D`. Это промежуточная сборка этапа, а не финальная приёмка 5.6.

Доказательства: `app/build/stage54-build.log`, `stage54-android.log`, `stage54-orientation.log`, XML/HTML в `app/build/test-results/testDebugUnitTest`, `app/build/reports/tests/testDebugUnitTest`, `app/build/outputs/androidTest-results/connected`, `app/build/reports/androidTests/connected/debug`. Полные lint reports — `app/build/reports/lint-results-debug.html` / `.xml`. Объединённая Gradle-команда имеет ненулевой код из-за lint, его результат не считается успешным при успешных tests/assemble.

На исходном Pixel_Tablet/emulator-5554 повторные общие прогоны давали непостоянные ошибки clipboard: диалог закрывался, но последующее чтение тестом иногда возвращало пустую строку; в диагностике также наблюдалась потеря window focus. Ожидание, observer и фоновые test permissions не устранили проблему надёжно. Эти экспериментальные изменения `JournalSafetyInstrumentedTest` полностью откатились: в коммит входит исходный тест 5.3 без ослабления assertions. Точная причина нестабильности исходного AVD не подтверждена.

Для независимой проверки создан чистый `DicomKit_Stage54` (API 35, Pixel Tablet profile, emulator-5556, headless/no-snapshot, только синтетические данные). Все пять исходных JournalSafetyInstrumentedTest на нём прошли; затем финальный полный прогон завершился **27/27 PASS**. Дополнительные доказательства: `app/build/stage54-journal-fresh.log`, `stage54-android-fresh.log`. Исходный emulator-5554 не выключается и не очищается. Создание AVD сообщило старый cmdline-tools/SDK XML compatibility warning, но завершилось успешно; SDK/dependencies не переустанавливались. После проверок останавливаются только созданный emulator-5556 с проверкой AVD name и изолированный Orthanc с проверкой PID/process name/listening port. Тестовый AVD остаётся для воспроизведения в `app/build/stage54-avd`, реальных исследований в нём нет.

Промежуточные результаты не скрываются: сначала исправлены duplicated null Pixel Data descriptor, старая SQ summary assertion, Android fixture cache-path/semantics ошибки и сортировка выбранных тегов dcm4che (последняя приводила к потере Window metadata). Проверка orientation сначала дала teardown failure ActivityScenario с состоянием PAUSED; test fixture теперь возвращает исходную orientation в finally и ждёт перехода, отдельный повтор прошёл. Первый полный JVM-прогон пропускал optional Orthanc; финальный выполнен с отдельным тестовым сервером, без skips. Непереносимые JDK/SDK настройки не менялись: в текущей среде используются JDK 21.0.8 и ANDROID_HOME, общий P2-01 остаётся на 5.6.

Ограничения: compressed pixel codecs не добавлены; не все Enhanced macros/IOD поддержаны; большие значения/deep trees имеют явные пределы; blocking SAF может задержать отмену; масштабированный bitmap не является полноразмерным экспортом. Файлы с metadata сверх parser limits сохраняются без удаления/исправления, но могут не индексироваться в списке архива: просмотр внешнего файла сообщает ограничение; диагностика общего archive indexing остаётся на 5.6. Автотесты не доказывают отсутствие всех device/GPU leaks или универсальную совместимость PACS/нестандартных объектов. Принятые tags теперь background, но общая индексация/другие UI I/O, SCP lifecycle и process death остаются отдельной работой 5.5/5.6. Требуются ручные проверки целевого телефона, длительные Zoom/Pan/WW gestures, GPU heap и файлов на медленном SD/cloud provider.

PR #1 остаётся OPEN, auto-merge выключен, remote HEAD по-прежнему `788e6a773f97dcfbc79c03488948fed34dcf6bda`; репозиторий PRIVATE. Review diff: `STAGE_5_4.patch` от базы 5.3; он генерируется после локального коммита и не входит в него. Этап 5.5 не начат.
