# DICOM Kit — результат этапа 6

Дата: 20.10.2026. Ветка `fix/stage-5-1-storage-safety`. База `ef2264496f3e117074a1a98e27aef186702a42f3`. Версия 1.3.1 / code 1, applicationId `ru.medeng.dicomtool`, signing не менялись. Только локальные коммиты, без main/push/merge/rebase/PR/Antigravity. Реальные исследования и каталог testdicom не использовались.

Все шесть задач реализованы. Окончательные проверки пройдены; требуется ручная приёмка кандидата на телефоне. Проверенный код и APK: `9516aed55e13343346b978720d3d417e64d73515`. Последующий коммит документации не меняет исходники APK; его SHA сообщается при сдаче этапа.

## 1. Журнал и пациенты

Обнаружено: в кратких карточках дублировался результат, отсутствовали структурированные пациенты и поиск по ФИО.

Изменения: удалена строка «Результат». Добавлены PN и ключ различения PatientID/Issuer в приватной записи, PN разбирается по компонентам `^` и альтернативным группам `=`. Учитываются пустые значения, отсутствие компонентов и исходный регистр; отображение не исправляет DICOM. Один/два пациента показаны именами, три и более — счётчиком с раскрытием. C-ECHO не получает поле пациента. Поиск работает по отображаемому полному имени и его частям. Исторические TSV с 9/10 колонками читаются без нового поля; добавлена 11-я колонка без переписывания истории. Безопасные экспорт и копирование не включают пациентов автоматически; hash идентичности не считается анонимизацией.

Основные файлы: `domain/model/DicomModels.kt`, `dicom/charset/DicomPersonName.kt`, `data/logging/DicomLogPatient.kt`, `PersistentDicomTechnicalLogger.kt`, `DicomSessionSummary.kt`, `SafeTechnicalSummary.kt`, `ui/screens/log/DicomLogScreen.kt`, SCP/SCU writers.

Проверки: Stage6JournalPatientsTest (3), JournalSecurityReliabilityTest; PN/группы/пустые поля, совпадающее ФИО разных PatientID, история/reload, поиск, safe export. Целевой прогон задачи 1: 29/29 PASS. Финальная регрессия включает эти тесты.

## 2. Достоверность SCP

Обнаружено: локальное хранение, DIMSE Status и запись ответа смешивались в одном результате, отсутствие отдельного ACK могло восприниматься как ошибка.

Изменения: отдельные Storage Result VERIFIED/FAILED, actual DIMSE Status и состояние записи RSP, связывание по Message ID. Краткий журнал отражает проверенное число сохранённых/несохранённых файлов, частичный успех, Bxxx Warning и реальные транспортные ошибки. Нет вымышленного ACK C-STORE-RSP; завершённая локальная запись не доказывает получение ответа удалённым узлом. Неизвестное историческое хранение не выдумывается. В подробном режиме сохранены фактические сетевые этапы; wire-семантика DICOM не подменялась ради цвета карточек.

Основные файлы: `dicom/scp/DicomStorageScpServer.kt`, `data/logging/DicomSessionSummary.kt`, `DicomDiagnosticHandbook.kt`, `data/logging/DicomSessionSummary.kt` (см. полный перечень diff).

Проверки: Stage6ScpOutcomesTest (4), ScpLifecycleStage55Test, DicomJournalStage432Test; success/failure/warning, несколько файлов и повторный UID с разными Message ID, разрыв/RSP-write failure, реальный ECHO/STORE/association/release. Целевой прогон: 32/32 PASS. Отдельный Orthanc принимает три исходных Transfer Syntax, включая Deflated; сохранённые объекты независимо прочитаны pydicom с точным PN и Pixel Data.

## 3. Ошибки чтения архива

Обнаружено: глобальный суммарный текст без источника/стадии, устаревающие и смешанные предупреждения.

Изменения: структурированная диагностика по source URI/path (не basename), имя/стадия/категоризированная причина; нет raw exception или stack trace в плашке. Полное обновление пересчитывает актуальные ошибки, incremental read убирает успешно прочитанную. Ошибки видны только в «Принятых», доступны раскрытие и повтор чтения. Charset warning остаётся отдельной диагностикой успешно прочитанного объекта. Закрытие плашки оставляет постоянную кнопку диагностики. Исходные файлы не исправляются.

Основные файлы: `data/storage/ArchiveIndexState.kt`, `DicomStudyRepository.kt`, `ui/navigation/DicomToolNavigation.kt`, `ui/screens/received/ReceivedStudiesScreens.kt`.

Проверки: Stage6ArchiveDiagnosticsTest (3), ArchiveStage56Test (4), 7/7 PASS в целевом прогоне. Проверены одинаковые имена разных источников, повторный refresh, удаление/успешное повторное чтение, отделение charset и скрытие технического исключения.

## 4. Общий Viewer и живой WW/WL

Обнаружено: дублирующиеся layouts/presets/navigation; при частых регулировках требовалось ограничить очередь работ и повторное чтение.

Изменения: один DicomViewerWorkspace для принятых и внешних источников. На широком экране изображение слева, справа «Просмотр и WW/WL» / «DICOM-теги»; на узком экране адаптивное размещение. Preset-кнопки удалены из UI; исходные параметры, существующие LUT/rescale и safe default не переписаны. Кадры и экземпляры разделены, доступны оба уровня одновременно. Инверсия и отдельный «Сбросить WW/WL» под sliders; сброс окна сохраняет zoom/pan/frame/inversion. «Сброс вида» отдельно обозначает более широкое действие.

Изменения WW/WL поступают при onValueChange: текущий render завершается, хранится только последняя ожидающая настройка; source/frame switch отменяет старый запрос. Кэш декодированного кадра сохраняется. Диапазоны sliders не уменьшаются вместе с WW/WL во время drag. Декодер не переписан, Pixel Data не меняются.

Основные файлы: `ui/components/DicomViewerWorkspace.kt`, `DicomSharedViewportBox.kt`, `ui/screens/received/ReceivedStudiesScreens.kt`, `ui/screens/tags/DicomTagsScreen.kt`, `ui/viewmodel/DicomToolViewModel.kt`, navigation.

Проверки: 42 JVM Viewer/charset tests PASS в целевом прогоне. API35: 12/12 ViewerSafety tests, затем 1/1 тест двух уровней навигации PASS; добавлена проверка закрытия charset notice. 4000×3000: bitmap обновляется ДО отпускания пальца, последний pixel соответствует итоговому окну, provider не открывается повторно, SHA источника совпадает. Проверены fractional windows, MONOCHROME/LUT/rescale, memory budget, source switching/recreation, frame-specific defaults, поиск/copy тегов. Синтетические wide/narrow screenshots получены и визуально осмотрены: toolbar переносится по строкам без горизонтально обрезанных кнопок; информационная панель прокручивается вертикально.

## 5. Закрытие уведомлений

Обнаружено: плашки результатов и charset невозможно закрыть; идентичный текст не различал новые события.

Изменения: AppNotice/NoticeVisibility с event ID, кнопкой X и presentation-only SaveableState. Success скрывается через 6 секунд; warning/error вручную. Новое событие с тем же текстом снова видно. Закрытие не удаляет результат, диагностику, журнал или DICOM. Диагностика архива и состояние журнала доступны постоянными действиями; warning журнала также открывает подробности по нажатию. Android foreground notification не заменялось закрываемой in-app плашкой. Устаревший итог пакета не определяет цвет следующего C-ECHO.

Основные файлы: `ui/components/AppNotice.kt`, MainActivity, модели, ViewModel, SCP error IDs, archive operations, экраны send/tags/receiver/received/settings/log.

Проверки: Stage6NoticesInstrumentedTest 2/2 PASS (реальный C-ECHO, новый одинаковый текст, таймер, manual dismiss, навигация и recreation); closingCharsetNotice... 1/1 PASS, исходные байты/теги/диагностика сохраняются. JVM регрессии задачи 5: 40/40 PASS. Первый полный Android-прогон выявил утраченное действие нажатия на warning журнала; обработчик восстановлен, старый regression test сохранён и дополнен доступом к подробностям после закрытия плашки.

## 6. Кодировка синтетического DICOM

Обнаружено: генератор имел только UTF-8.

Изменения: UTF-8/ISO_IR 192 по умолчанию, ISO-8859-5/ISO_IR 144, строгий ASCII без 0008,0005; экспериментальный UTF-8 без объявления и нестандартный CP1251 без фиктивного ISO_IR 144. CharsetEncoder REPORT не подменяет неподдерживаемые символы вопросами; неподходящие метаданные блокируют подготовку/отправку и не оставляют старый объект как текущий. Кодировка сохраняется между запусками, применяется только генератором, не пересылкой исходных файлов. Предупреждения объясняют экспериментальные режимы.

Библиотека действительно пишет нужные bytes через setBytes. Выявлена и исправлена отдельная мутация Attributes при чтении имени/ID для журнала и сообщений: чтение из snapshot предотвращает изменение wire bytes при повторной отправке.

Основные файлы: `dicom/scu/SyntheticDicomEncoding.kt`, `DicomSecondaryCaptureGenerator.kt`, `DicomScuClient.kt`, `data/logging/DicomLogPatient.kt`, ScuSendConfig, ViewModel, SendScuScreens.

Проверки: Stage6SyntheticEncodingTest (7): actual Part10 bytes независимым minimal explicit-VR parser, charset/PN/LO, UTF-8/ISO-8859-5, ASCII rejection, malformed Unicode/control, CP1251 без тега, одинаковые pixels, UID/retry. Реальные два C-STORE каждого из пяти режимов: wire dataset совпадает до/после журнала и при повторе. Независимый pydicom 3.0.2 прочитал 3 стандартных режима с точным PN/charset и одинаковым SHA Pixel Data. API35 selector/ASCII/null prepared/persistence/recreation 1/1 PASS. Пересылка оригиналов дополнительно покрыта ScuTransmissionSafetyTest (Deflated, mixed TS, original hashes, cancel, unknown RSP).

Нормативная сверка кодировок: [DICOM PS3.18, Table D-1](https://dicom.nema.org/medical/dicom/current/output/chtml/part18/chapter_D.html). PN: [DICOM PS3.5, 6.2](https://dicom.nema.org/medical/DICOM/current/output/chtml/part05/sect_6.2.html).

## Итоговые проверки и артефакты

| Проверка | Фактический итог | Доказательство |
|---|---|---|
| testDebugUnitTest | **228/228 PASS**, 0 failures/errors/skipped, 22 класса | app/build/stage6/final-build-verified.log; final-jvm-result.json |
| connectedDebugAndroidTest | **49/49 PASS**, 0 failures/errors/skipped, API35, отдельный emulator-5556, isolated package | app/build/stage6/final-android-after-lint.log; after-lint-final-android-results/; final-android-result.json |
| assembleDebug | **PASS**, обычный ru.medeng.dicomtool | app/build/stage6/final-build-verified.log |
| assembleRelease | **PASS**, unsigned, существующая signing-конфигурация не менялась | app/build/outputs/apk/release/app-release-unsigned.apk |
| lintDebug | **PASS: 0 errors, 42 warnings, 1 information** | app/build/reports/lint-results-debug.html и .xml |
| Orthanc + independent stored-object read | **PASS**, 3 synthetic Part10 objects: Implicit LE, Explicit LE, Deflated | app/build/stage6/orthanc-independent.json |
| Independent charset parser | **PASS**, pydicom 3.0.2: UTF-8 / ISO-8859-5 / ASCII, exact PN/charset и одинаковый Pixel Data | app/build/stage6/encoding-independent.json |
| Signature / package | **PASS**, apksigner v2, 1 signer; package/version проверены aapt | app/build/stage6/final-apksigner-verified.log; reports/stage6/aapt-badging.txt |
| Source provenance | **PASS**, 126 tracked build inputs SHA-256, source commit 9516aed55e13343346b978720d3d417e64d73515 | app/build/reports/stage6/build-provenance.json |

Обычный debug APK сохранён отдельно от instrumentation-сборки:

`C:\Users\Eskimos\Documents\ChatGPT\Dicom Tool\app\build\reports\stage6\dicom-kit-stage-6-debug.apk`

- Размер: 18547941 байт.
- SHA-256: `061a09ddbdd9f15d68aa748afc924179b06126b3d3e402adfef57e1061234bef`.
- Sidecar: `C:\Users\Eskimos\Documents\ChatGPT\Dicom Tool\app\build\reports\stage6\dicom-kit-stage-6-debug.apk.sha256`.
- Версия: 1.3.1 / code 1; minSdk26 / targetSdk35. JDK21.0.8, Gradle8.10.2, AGP8.6.1, Kotlin1.9.24, ComposeCompiler1.5.14, BuildTools36.0.0.
- Все 18 новых JVM и 7 новых Android-тестов используют только синтетические объекты; итоговые числа — уникальные test cases, повторные прогоны не суммировались.
- Дополнительно напрямую am instrument выполнены 2 проверки последнего Viewer для получения synthetic screenshots; они уже входят в 49 уникальных Android-тестов. Wide/narrow screenshots осмотрены, папка app/build/stage6/screenshots.

**Предупреждения не скрывались.** Финальный lint: GradleDependency 33, AndroidGradlePluginVersion 3, IconLauncherShape 5, OldTargetApi 1; отдельно Information AutoboxingStateCreation 1. Два новых ModifierParameter устранены перестановкой optional parameter, без suppression/baseline. Прежняя UnusedBoxWithConstraintsScope была исправлена в 5.6 и в этом прогоне не возникает. В компиляционных логах остаются unused variables/parameters, shadowed window, deprecated InsertDriveFile/readDataset и избыточный Elvis в renderer; это отдельные compiler warnings, не lint errors.

**Промежуточные ошибки и повторные проверки.** Первые целевые прогоны обнаружили компиляционные imports/название pan-метода, ошибку тестового wm resize, влияние presentation UUID на equality архива, изменение prepared Attributes при чтении PN/ID и потерю clickable действия warning журнала. Они исправлены, регрессии сохранены. Усиленный WW/WL-тест проверяет ненасыщенный pixel и оба sliders; диапазон не уменьшается под пальцем. Полный последний 49/49 прогон и 228/228 JVM выполнены после последних source changes. Предыдущие неудачные логи сохранены в app/build/stage6, в том числе task6-wire-initial.log и final-android.log. Первый apksigner invocation не стартовал из-за неустановленного JAVA_HOME в новом shell; повтор с явным JDK прошёл, APK не переподписывался.

**Git.** Проверенный код `9516aed55e13343346b978720d3d417e64d73515`, ветка fix/stage-5-1-storage-safety. Все исходники закоммичены. Отчёт, статус аудита и приёмка фиксируются отдельным local commit. Tracking branch остаётся позади локальной ветки; push/merge/rebase/squash/cherry-pick/force push и изменения main не выполнялись. Существующие untracked audit files и патчи оставлены нетронутыми. Итоговый STAGE_6.patch — локальный diff от ef22644, без APK, реальных исследований и секретов.


## Ограничения и ручная приёмка

- Устройства OEM, длительный фон/Doze, физический SD/cloud SAF, vendor PACS и GPU на телефоне требуют ручной проверки. Эмулятор не заменяет эту приёмку.
- Закрытие плашек хранит последние 256 event IDs в виде hash; после более 256 других показанных событий старое предупреждение при возвращении может появиться заново. Обычная рекомпозиция/rotation не восстанавливает закрытую текущую плашку.
- Новые codecs/транскодирование Viewer не добавлены. Нестандартные исходники не исправляются.
- CP1251 и не-ASCII без объявления намеренно нестандартны; успешный C-STORE не гарантирует правильное отображение текста на любом PACS.
- При сохранённом ASCII и исходных тестовых кириллических полях новый запуск безопасно требует изменить поля/кодировку; автоматическая подмена текста не выполняется.
- Длительное блокирующее чтение стороннего SAF provider может задержать завершение отмены; интерфейс сохраняет ожидание освобождения ресурсов.
- Старые записи журнала без пациентов не получают ФИО задним числом. При отсутствии PatientID/Issuer одинаковые PN могут быть неразличимы; hash ключа не является анонимизацией. Приватная история содержит чувствительные данные, safe export использует закрытую проекцию.
- P2-02 (полная декомпозиция ViewModel / восстановление transient state после процесса) остаётся частично исправленным долгом предыдущего этапа.
- Масштаб/сдвиг/инверсия/frame сохраняются при сбросе WW/WL; полный «Сброс вида» имеет отдельную семантику. Ручные sliders используют целые значения, исходные fractional DICOM defaults в рендеринге сохранены.

Ручная проверка: [STAGE_6_ACCEPTANCE_TEST_PLAN.md](STAGE_6_ACCEPTANCE_TEST_PLAN.md). До неё и отдельного разрешения пользователя ветка не объединяется с main.

## Полный перечень изменённых файлов

- `app/src/androidTest/java/ru/medeng/dicomtool/JournalSafetyInstrumentedTest.kt`
- `app/src/androidTest/java/ru/medeng/dicomtool/Stage6GeneratorInstrumentedTest.kt`
- `app/src/androidTest/java/ru/medeng/dicomtool/Stage6NoticesInstrumentedTest.kt`
- `app/src/androidTest/java/ru/medeng/dicomtool/ViewerSafetyInstrumentedTest.kt`
- `app/src/androidTest/java/ru/medeng/dicomtool/storagefixtures/Stage51DocumentsProvider.java`
- `app/src/main/java/ru/medeng/dicomtool/MainActivity.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/logging/DicomDiagnosticHandbook.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/logging/DicomLogPatient.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/logging/DicomSessionSummary.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/logging/PersistentDicomTechnicalLogger.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/logging/SafeTechnicalSummary.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/storage/ArchiveIndexState.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/storage/ArchiveOperations.kt`
- `app/src/main/java/ru/medeng/dicomtool/data/storage/DicomStudyRepository.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/charset/DicomPersonName.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomSecondaryCaptureGenerator.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/scu/ScuFileBatchSender.kt`
- `app/src/main/java/ru/medeng/dicomtool/dicom/scu/SyntheticDicomEncoding.kt`
- `app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/components/AppNotice.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/components/DicomSharedViewportBox.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/components/DicomViewerWorkspace.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/navigation/DicomToolNavigation.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/received/ReceivedStudiesScreens.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/receiver/ReceiverScpScreen.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/send/SendScuScreens.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/settings/SettingsAndCleanupScreen.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/tags/DicomTagsBrowserComponent.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/screens/tags/DicomTagsScreen.kt`
- `app/src/main/java/ru/medeng/dicomtool/ui/viewmodel/DicomToolViewModel.kt`
- `app/src/test/java/ru/medeng/dicomtool/ArchiveStage56Test.kt`
- `app/src/test/java/ru/medeng/dicomtool/ScpLifecycleStage55Test.kt`
- `app/src/test/java/ru/medeng/dicomtool/ScuTransmissionSafetyTest.kt`
- `app/src/test/java/ru/medeng/dicomtool/Stage6ArchiveDiagnosticsTest.kt`
- `app/src/test/java/ru/medeng/dicomtool/Stage6JournalPatientsTest.kt`
- `app/src/test/java/ru/medeng/dicomtool/Stage6ScpOutcomesTest.kt`
- `app/src/test/java/ru/medeng/dicomtool/Stage6SyntheticEncodingTest.kt`
- `docs/ANDROID_ACCEPTANCE_TEST_PLAN.md`
- `docs/AUDIT_FINDINGS_STATUS.md`
- `docs/STAGE_6_ACCEPTANCE_TEST_PLAN.md`
- `docs/STAGE_6_RESULT.md`
- `docs/STAGE_6_WORK_LOG.md`
