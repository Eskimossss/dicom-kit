# DICOM Kit — результаты этапа 5.2

Дата: 14 октября 2026 года. Рабочая ветка: `fix/stage-5-1-storage-safety`. База: `788e6a773f97dcfbc79c03488948fed34dcf6bda`. Исправления и документы предназначены для одного отдельного локального коммита этапа 5.2. Main не изменялся, merge/rebase/squash/force push/push не выполнялись; PR #1 остаётся открытым на опубликованном коммите 5.1, auto-merge выключен.

План, таблица статусов и исходный независимый аудит доступны в рабочем каталоге и изучены. Значимых расхождений текущего кода с планом перед реализацией не обнаружено. Первый пользовательский текст был оборван; реализация продолжена после полного задания. Работа ограничена 5.2 и связанными ошибками SCU-журналирования, этап 5.3 не начат.

## Исправления

| Находка | Что изменилось | Доказательство |
|---|---|---|
| P0-04 | `OriginalDicomDataset` читает границу FMI из исходного потока, проверяет длины/VR/TS, затем отдаёт неизменённый encoded Data Set. DicomInputStream остаётся только для metadata validation. Writer не распаковывает и не транскодирует Deflated | Точное сравнение wire bytes с независимым offset oracle, receiver decode под Deflated UID, hash оригинала; Android ContentResolver; Orthanc |
| P1-07 | Собственный cancellation handle закрывает только сокет текущего запуска. Запрос отмены отличается от завершения cleanup; UI остаётся busy. Finally сбрасывает флаги. Scan читает собственную Job и защищён generation ID от устаревших результатов | Cancel до connect, непосредственно перед командой, blocked payload write, ожидание RSP, между файлами; Android отмена→повтор и scan cancel→new scan |
| P1-08 | Snapshot очереди, локальные/global индексы не смешиваются; каждый элемент получает единственный terminal result. Итог считается из результатов, а не callbacks. Остаток разорванной ассоциации отмечается Skipped, не остаётся Pending | 121 файл / 2 ассоциации, отказ второй; partial/all failure/warning-only, missing RSP, отмена; инвариант суммы всех исходов |
| P1-13 | SCU file result events являются источником краткого итога. Legacy pairing по Message ID, два варианта ключа ответа и fallback только при однозначности. Повторные SOP UID не подменяют ответы друг друга; warning-only не является Failure | Production events из loopback sender и отдельный legacy test; нет ложного missing RSP при multi-file success |
| P1-15, часть SCU | Release ожидает dcm4che socket close с проверкой exception; RP не записывается после одного release(). Local socket close проверяется фактически, вспомогательные executors останавливаются; timeout/cleanup warning сохраняется отдельно от DIMSE result | Proxy удаляет только реальный A-RELEASE-RP; C-STORE Success сохраняется, отсутствует фиктивный RP, предупреждение видно. Общие C-ECHO/generated C-STORE baseline tests проходят |
| P1-17, связанные рекомендации | B000 = Coercion, B006 = Elements Discarded; missing RSP не доказывает доставку команды или сохранение. 0001 не выдаётся за стандартное подтверждение C-STORE. Для legacy status поддержан явный `0000H`, отсутствие кода не превращается в Success | Handbook assertions, missing-status legacy regression, обновление старого теста с Failure на Unknown по новому контракту |

Короткие session labels `SCU-1`, `SCU-2` и т. д. сохранены через существующий DicomSessionManager; внутренние session UID остаются уникальными. Для каждой ассоциации есть отдельное завершение и для всего пакета — итог. В журнале подготовка association/request, попытка C-STORE, реально полученный ответ и локальное закрытие не выдаются друг за друга. Отказ Presentation Context — локальный Failed до C-STORE, без выдуманного RSP.

## Результат каждого файла

`ScuFileResult` содержит global file index/ID, SOP Instance UID, Transfer Syntax UID, association label/session UID, Message ID, terminal status, причину, фактический Status/ErrorComment подтверждённого RSP и счётчик записанных dataset bytes. Исходы:

- **Success / Warning** — валидный соответствующий C-STORE-RSP.
- **Failed** — подтверждённый Failure RSP либо ошибка подготовки/отказ context до команды; причина явно различает их, RSP fields во втором случае отсутствуют.
- **Skipped** — не отправлялся из-за недоступной/разорванной ассоциации.
- **Cancelled** — отменён до отправки команды.
- **Unconfirmed** — команда могла отправиться, но валидный соответствующий RSP не получен. PACS мог сохранить объект; автоматического retry нет.

После окончания нет Pending/Sending. Уже подтверждённые результаты не теряются при отмене или ошибке release. Request cancellation не является сообщением об остановке: после запроса ожидаются завершение worker и cleanup. Повторный запуск до этого блокируется. После завершения возможна новая ручная отправка; её token и результаты независимы. Если текущая ассоциация потеряна, её неотправленный остаток отмечается Skipped; следующие заранее выделенные ассоциации могут открываться заново, если нет отмены. Сам in-flight Unconfirmed объект автоматически не повторяется.

## Изменённые файлы

- `dicom/scu/OriginalDicomDataset.kt` — bounded FMI reader, исходный encoded stream и потоковый copy.
- `dicom/scu/ScuCancellation.kt` — отдельный token/сокет операции; запрос закрытия без выдуманного A-ABORT от peer.
- `dicom/scu/ScuFileBatchSender.kt` — production transport за InputStream boundary, negotiation, per-file outcomes, cleanup, timeouts, журнал.
- `dicom/scu/DicomScuClient.kt` — Android ContentResolver adapter, результат batch, общие release helpers, send timeout и canonical response key.
- `domain/model/DicomModels.kt` — Cancelled/Unconfirmed и полные счётчики progress.
- `ui/viewmodel/DicomToolViewModel.kt` — guards, snapshot, cancellation lifecycle, scan generation, finally и итоговая классификация.
- `ui/screens/send/SendScuScreens.kt` — состояние отмены/неизвестных исходов и progress для всех результатов; нет Success по одному failedCount==0.
- `data/logging/DicomSessionSummary.kt` — correlation, современные file outcomes, Unknown/Warning и отсутствие default Success для RSP без кода.
- `data/logging/DicomDiagnosticHandbook.kt` — согласованные связанные рекомендации и legacy hex parsing.
- `src/test/.../ScuTransmissionSafetyTest.kt` — 25 новых JVM tests.
- `src/test/.../DicomJournalStage432Test.kt` — ожидаемый Unknown для неподтверждённой передачи.
- `src/androidTest/.../ScuSafetyInstrumentedTest.kt` — 4 новых Android tests.
- `docs/AUDIT_FINDINGS_STATUS.md`, `docs/STAGE_5_2_PLAN.md`, этот отчёт — все 26 статусов, сохранённый исходный план и результат.

Пути production-файлов выше относительно `app/src/main/java/ru/medeng/dicomtool/`, test paths — относительно `app/`. Storage production files этапа 5.1 не менялись.

## Финальная проверка

Среда: Windows, JDK 21.0.8, SDK 35 / Build Tools 36.0.0; Pixel Tablet Emulator Android 15 / API 35, `emulator-5554`. Только синтетические DICOM, реальные исследования не использованы.

| Команда | Результат |
|---|---|
| `gradlew.bat testDebugUnitTest` | PASS: **122 tests**, 0 failures/errors/skipped; baseline 97 + 25 новых |
| `gradlew.bat assembleDebug` | PASS: normal debug `ru.medeng.dicomtool`, версия 1.3.1 / code 1 |
| `gradlew.bat lintDebug` | FAIL: **1 существующая ошибка, 49 warnings**; baseline/suppression не добавлялись |
| `gradlew.bat connectedDebugAndroidTest -Pstage51Isolated=true` | PASS: **14 tests**, 0 failures/errors/skipped; baseline 10 + 4 новых |
| `git diff --check` / staged check | PASS перед коммитом |

Первые три Gradle задачи запускались одной командой. Общий exit code 1 вызван lintDebug, а не скрыт успешными задачами. Существующая ошибка: `UnusedBoxWithConstraintsScope` в `app/src/main/java/ru/medeng/dicomtool/ui/navigation/DicomToolNavigation.kt:244`. Среди warnings — прежние категории и уже добавленные в 5.1 предупреждения о новых версиях тестовых runner/ext:junit. Новых storage/SCU lint errors нет. Прежние AGP/SDK XML предупреждения среды остаются.

Промежуточные проверки находили ошибки нового test harness (неверный начальный TS writer при создании FMI, API service override, создание ещё не материализованного provider root, отсутствие java.desktop в Android compilation bootclasspath); они исправлены. Legacy test с `0000H` выявил реальную неполноту status parsing, которая тоже исправлена. Эти промежуточные failures не выдаются за успешные проверки; финальные результаты приведены в таблице.

Проверены синтетические Part 10 Implicit LE / Explicit LE / Explicit BE / Deflated, raw LE, короткие reads/no mark, malformed FMI length и TS mismatch, JPEG Baseline/RLE encapsulated fragments без decoder, rejected context, Success/Warning/Failure RSP, malformed RSP, отсутствие ответа, repeated UID, 121-file association split, отмена до/во время/между отправками, retry, dropped release RP, legacy pairing/нет статуса, параметры endpoint. Android tests используют отдельный test DocumentsProvider через настоящий persistable URI grant, проверяют byte preservation, original preservation, потерю доступа к источнику, ViewModel cancellation/cleanup/retry и scan generation. Все ранее исправленные storage regressions входят в общий прогон, включая keep.txt, .part, same-size different pixels, parallel UID и повтор миграции при отказе удаления.

### Orthanc

На машине доступен Orthanc. Для проверки запущен отдельный экземпляр `C:/Program Files/Orthanc Server/Orthanc.exe` с новой базой в `app/build/stage52-orthanc/`, HTTP выключен, плагины не загружены, AET `TEST_PACS`, выделенный DICOM port 53914. Существующая служба и её данные не изменялись. Переданы три синтетических объекта: Explicit LE, Implicit LE и Deflated, все получили `0x0000`. Тест `isolatedOrthancAcceptsSyntheticDeflatedAndUncompressedObjects` выполнен, не skipped.

Для повторения Orthanc test нужен отдельно запущенный изолированный synthetic-only экземпляр и env `STAGE52_ORTHANC_PORT`. Без env этот optional integration test честно skipped через Assume; остальные tests не зависят от Orthanc. Финальный JVM-прогон здесь имел установленный env и ноль skipped. Сетевые точные bytes подтверждены независимым controlled peer, а не одним Orthanc RSP. Запущенный для задачи экземпляр остановлен после проверок; синтетические артефакты оставлены в ignored build directory.

### APK и артефакты

Normal debug APK сохранён отдельно **до** instrumented build с suffix, чтобы его не заменить тестовым package: [dicom-kit-stage-5-2-debug.apk](../app/build/reports/stage52/dicom-kit-stage-5-2-debug.apk).

SHA-256: `C65F74BBD8A3DEED12B2A528037469B4D8E4B93D4A82D4E0B23291BDB99EC12A`.

Отчёты: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, `app/build/reports/lint-results-debug.html`. Логи финальных запусков: `app/build/stage52-build.log`, `app/build/stage52-android.log`; Orthanc startup log/config — `app/build/stage52-orthanc/`. Build artifacts не включены в Git-коммит и могут быть удалены следующей clean-сборкой. Это APK этапа 5.2, а не финальная приёмочная версия после 5.6.

## Ограничения и последующая ручная проверка

1. Readback/передача подтверждает согласованный wire TS и наблюдаемый DIMSE response, не физическую долговечность хранения PACS. Unconfirmed не доказывает отказ или отсутствие объекта на peer.
2. Приём C-STORE, cancel во время blocked socket write и RSP wait проверены. Отмена непосредственно во время TCP connect/negotiation ограничена connect/accept timeout и закрытием сокета после его создания; точные сетевые условия реального Wi-Fi/маршрутизатора ещё требуют ручной проверки.
3. Некооперативный SAF provider может блокировать open/read в IPC: закрытие сетевого сокета и запрос coroutine cancel сами по себе не гарантируют прерывание чужого provider. Worker/UI не объявляют cleanup завершённым до возврата чтения. Для таких providers срок завершения не подтверждён; сообщения честно показывают ожидание. Произвольный внешний писатель также может изменить файл между открытиями/во время чтения: metadata/TS перепроверяются, распределённого file lock/snapshot против внешнего приложения нет.
4. FMI reader консервативен: неподдержанные VR/небезопасная длина, неверная граница/group length или TS mismatch приводят к локальному отказу без изменения исходника. Part 10 с неоднозначной структурой не «ремонтируется». Raw compressed/Deflated dataset без FMI не принимается без достоверного TS. Проверены raw LE; raw BE и vendor non-Part-10 compressed требуют отдельной проверки.
5. JPEG Baseline и RLE fixture transport проверены. JPEG-LS/JPEG 2000 и весь спектр SOP/TS, реальные vendor PACS и decoder interoperability не объявлены проверенными. Поддержка исходных encapsulated bytes не требует decoder, но не является доказательством валидности любого внешнего compressed codestream.
6. После network failure неотправленный остаток ассоциации — Skipped, другие заранее выделенные ассоциации могут выполняться. Автоматического retry in-flight Unconfirmed нет. Пользователь должен свериться с PACS перед ручным повтором, поскольку объект мог сохраниться.
7. Безопасность PHI экспорта/маскирования, очередь/ротация и потери logger остаются задачами 5.3. При потере записей P1-16 краткий журнал не может восстановить отсутствующие доказательства. Исправленная SCU correlation не закрывает P1-16. SCP session identity/send/flush/stop и Foreground Service остаются в 5.5; Viewer/charset/tag/SQ — в 5.4; lint/toolchain и другие P1/P2 — в 5.6.
8. Нужны ручные проверки на Android пользователя: реальные PACS/SAF, mixed TS, большой batch, cancel на разных фазах и повтор, сравнение краткого/подробного журнала, C-ECHO/generated C-STORE, storage/миграция/очистка. Только после 5.6 и результатов ручного тестирования можно отдельно разрешить merge.

Все 26 находок учтены в [AUDIT_FINDINGS_STATUS.md](AUDIT_FINDINGS_STATUS.md): 10 исправлено, 3 частично исправлено, 13 не исправлено; ручная приёмка впереди. Следующий этап автоматически не начинается.
