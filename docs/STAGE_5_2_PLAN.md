# Этап 5.2 — план исправления SCU

Подготовка на коммите `788e6a773f97dcfbc79c03488948fed34dcf6bda`. Реализация не начата. Основание — исходный независимый аудит `DICOM_KIT_AUDIT_2026-10-14.md` и повторное чтение текущего production-кода. Общая таблица: [AUDIT_FINDINGS_STATUS.md](AUDIT_FINDINGS_STATUS.md).

## Git и границы

- Работать последовательно в существующей `fix/stage-5-1-storage-safety`; новые ветки от main не создавать. PR #1 остаётся открытым.
- Завершить отдельно разрешённый этап 5.2 отдельным коммитом с понятным названием, например `Fix stage 5.2 SCU transfer syntax, batch results and cancellation`.
- Не изменять main, не выполнять merge/rebase на main/squash/force push без разрешения. Публикацию будущего коммита и обновление описания общего PR согласовать с заданием на реализацию; подготовка их не выполняет.
- Главные цели: P0-04, P1-07, P1-08. Связанные части: P1-13 (correlation SCU), P1-15 (SCU release/close), P2-03 (production network tests). Общие logger/summary, SCP lifecycle и Viewer не объявлять исправленными этим этапом.
- Передача существующих объектов не изменяет исходный файл, UID, Pixel Data или TS и не транскодирует автоматически. Неоднозначные/неподдержанные исходники получают явный отказ до отправки, сохраняются без модификации.

## Подтверждённые места текущего кода

| Файл | Текущие строки / роль | План |
|---|---|---|
| `app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt` | 1375–1933 batch; 1722–1744 writer; 1869–1874 индексы остатка; 1936–2010 release/abort | Исходный encoded Data Set, результаты каждого объекта, cancellation/timeout/resource cleanup; общие helpers проверять также на C-ECHO и generated C-STORE |
| `app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomFileScanner.kt` | 88–196 detection/parsing; 319 и далее SAF scan | Согласованный контракт TS/FMI/raw dataset, достоверность определения, отмена собственного запуска и повторное открытие stream |
| `app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt` | 234–290 FileSendStatus, ScuCandidateFile, ScuBatchProgressState | Терминальные исходы, источник/TS confidence и счётчики; не смешивать unknown remote outcome с подтверждённым Failure |
| `app/src/main/java/ru/medeng/dicomtool/ui/viewmodel/DicomToolViewModel.kt` | 1055–1193 scan/batch/cancel; общие SCU trigger/result handlers | Собственные operation tokens, finally/reset, изоляция устаревших callbacks; связь отмены с текущим транспортом |
| `app/src/main/java/ru/medeng/dicomtool/ui/screens/send/SendScuScreens.kt` | 381–383, 1742–1759 totals/progress; 1903 и далее status rows | Отмена и неопределённый результат, полный progress включая Warning/Skipped/Cancelled, корректный общий итог |
| `app/src/test/java/ru/medeng/dicomtool/Stage42FileBatchScuTest.kt` | Сейчас scanner/model tests | Производственные batch/writer regression tests, не копия алгоритма в тесте |
| `app/src/test/java/ru/medeng/dicomtool/Stage3ScuAndGeneratorTest.kt` | 341 и далее настоящий loopback C-ECHO/generated C-STORE | Регрессии общего lifecycle/timeout/status mapping |
| `app/src/test/java/ru/medeng/dicomtool/Dcm4cheCompatibilityTest.kt` | SCP loopback и ошибки хранения | Регрессии при изменении общих association helpers/TS contracts |
| `app/src/main/java/ru/medeng/dicomtool/data/logging/DicomSessionSummary.kt` | 407–423 и 566–605 pairing/aggregate | Связанный потребитель: проверить реальными событиями SCU; если для закрытия SCU correlation нужен минимальный matcher change, явно включить его в объём 5.2 и regression tests, не закрывать всю безопасность журнала |
| `app/src/main/java/ru/medeng/dicomtool/data/logging/DicomSessionManager.kt`, `PersistentDicomTechnicalLogger.kt`, `DicomDiagnosticHandbook.kt` | Общие contracts/logger/status diagnostics | Зависимости для проверки, без планового широкого рефакторинга в 5.2; очередь/PHI/handbook относятся к 5.3, SCP identities — к 5.5 |

Возможные новые небольшие компоненты: reader границ исходного encoded dataset, транспортный cancellation handle и pure reducer результатов пакета. Названия и точное разбиение определить после failing regression tests. Для Android добавить отдельный SCU instrumentation test и при необходимости расширить существующий синтетический test provider. Строки приведены для текущего коммита, после реализации изменятся.

## Порядок реализации после отдельного задания

1. **Воспроизведение.** Добавить failing tests для Deflated, обрыва пакета, отказа второй ассоциации и отмены. Сохранить текущие 97 JVM/10 Android storage tests как baseline. Перед реализацией сверить HEAD и чистоту отслеживаемых файлов, не перезаписывать сторонние изменения.
2. **Контракт источника и TS.** Разделить разбор метаданных и передачу исходных encoded bytes. Определять начало Data Set с проверкой структуры FMI и без использования распакованного DicomInputStream как data source; не доверять одному group length. Использовать повторно открываемый поток SAF, учитывать буферизацию, короткие reads и отсутствие seek/reset. Для Part 10 проверить TS и согласованность SOP identity FMI/Data Set. Для raw dataset фиксировать уверенность определения: Implicit/Explicit LE проверять; неоднозначный BE/сжатый raw не выдавать за достоверно определённый синтаксис. Отказ не исправляет исходник.
3. **Writer и negotiation.** Потоково передавать encoded dataset без preamble/FMI под точно соответствующим согласованным SOP/TS. Deflated должен оставаться Deflated; encapsulated JPEG/JPEG-LS/JPEG 2000/RLE сохранять без декодирования/пересборки. Отклонённый context не даёт права на подмену TS. Проверять файл повторно перед отправкой относительно scanned identity/layout; определить безопасное поведение при изменении provider между scan и send. Использовать Long и фактически записанные dataset bytes для прогресса, не SIZE provider как доказательство передачи.
4. **Результат каждого объекта.** Ввести неизменяемый snapshot очереди и отдельные global/local indices. Каждому выбранному элементу назначить ровно один терминальный исход: подтверждённый Success, Warning, Failure, Skipped, Cancelled либо Unconfirmed. Названия enum допускают другое оформление, но семантика должна быть отдельной. Проверять Command Field, MessageIDBeingRespondedTo и доступную SOP identity ответа. Нет C-STORE-RSP — сохранение на принимающей стороне неизвестно; не утверждать, что PACS получил/отклонил/сохранил объект. Сетевой обрыв завершает остаток текущего batch; политику дальнейших ассоциаций сделать явной. Автоматически не повторять объект с неопределённым удалённым результатом.
5. **Отмена и тайм-ауты.** У каждого scan/send собственный token, а не чтение изменяемого поля scanJob. Отмена останавливает текущий connect/send/wait, закрывает или abort-ит соответствующий транспорт, освобождает потоки/executors и не подавляется как обычная ошибка. Задать ограниченные connect/accept/request/send/response/release ожидания. Для SAF blocking read честно указать границы возможности прерывания provider. UI reset в finally; callbacks прежнего запуска не меняют новый. Already confirmed outcomes не теряются, in-flight без RSP — Unconfirmed, неотправленный остаток — Cancelled. После отмены можно начать новую операцию.
6. **Lifecycle и события SCU.** Различать попытку release, наблюдаемый release response, timeout и фактическое закрытие. Событие «получен RP» не писать после одного release(). Correlation по session/association + DIMSE message ID, включая повторные SOP UID; проверить summary Success/Warning/Failure/Unconfirmed. Результат C-STORE не превращать в Failure только из-за последующего release timeout, если валидный Success RSP уже получен; ошибку завершения показывать отдельно. При общих helper changes проверять C-ECHO и generated C-STORE.
7. **UI, документация и проверки.** Финальные счётчики строить из результатов, не из числа callbacks. Итог «успешно» допустим только при полном подтверждённом покрытии; Warning, отмена и Unconfirmed видимы. Обновить status table, подготовить отчёт 5.2 с командами/результатами/ограничениями, собрать debug APK и отдельный коммит. Следующий этап не начинать.

## Матрица обязательных регрессий

| Группа | Синтетические сценарии и проверка |
|---|---|
| Encoded bytes / TS | Part 10 Implicit LE, Explicit LE, Explicit BE, Deflated; raw supported LE; малые/короткие reads, длинный/некорректный FMI, неверный group length, неизвестный TS. Проверять hash исходника до/после, network payload и разбор принимающим peer с negotiated TS |
| Encapsulated TS | JPEG/JPEG-LS/JPEG 2000/RLE синтетические корректные fixtures; transport tests сравнивают исходные фрагменты без codec. Валидный image fixture нужен для заявления об image interoperability; произвольные fragments доказывают только transport preservation |
| Negotiation | SOP/TS accepted/rejected, частично принятые contexts, неправильный called AE, rejected association; несколько ассоциаций при >120 уникальных пар SOP/TS (отказ второй после успешной первой) |
| RSP | 0000, B000/B006/B007, failure, отсутствующий/некорректный RSP, mismatch message ID/command, repeated SOP UID; не выдавать отсутствие подтверждения за success/failure PACS |
| Batch | Разрыв на первом/среднем/последнем файле; mixed status; все rejected; warning-only; local read failure; полный terminal coverage и сумма всех outcomes = размер snapshot; после завершения нет PENDING/SENDING |
| Cancellation | До connect, во время negotiation, большой send peer-у, который перестал читать, ожидание RSP, между batches; scan cancel→new scan; все UI flags сброшены, предыдущий callback не изменяет новый запуск, ресурсы освобождены |
| Closure | Normal release, missing release RP, remote abort/reset; нет фиктивного полученного RP/закрытого socket; confirmed DIMSE success сохраняется независимо от release warning |
| Android/SAF | Production SCU через ContentResolver и отдельный test provider: no seek, SIZE=0, permission revoked/read exception, cancel/retry UI; сырые bytes исходника неизменны |
| Stage 5.1 | Все storage tests, same-size/different-pixels, UID collision, concurrent C-STORE, отказ удаления источника и повтор миграции, keep.txt/unknown dirs/.part сохранны |

JVM loopback peer должен управлять реальным association/RSP/обрывом, а не подменять ожидаемый итог тестовым пересчётом. Для воспроизведения batches с >120 contexts использовать синтетические UID/controlled peer; это тест лимита/учёта, не заявление поддержки всех SOP Classes.

## Критерии завершения и команды

- Wire bytes соответствуют negotiated TS; исходник неизменён; неопределённая TS не угадывается молча.
- Каждый элемент snapshot имеет ровно один конечный исход; сумма исходов сходится; подтверждённые Success/Warning опираются на соответствующий RSP.
- Отмена гарантированно завершает собственную операцию/UI и освобождает доступные ресурсы в измеренный ограниченный срок; непроверенные provider ограничения перечислены явно.
- Production tests подтверждают основные дефекты, все ранее исправленные storage regressions проходят.
- Выполнить `gradlew.bat testDebugUnitTest assembleDebug lintDebug`, затем `gradlew.bat connectedDebugAndroidTest -Pstage51Isolated=true` и `git diff --check`. Результаты отдельных задач, failure/error/skipped counts и среду записать; APK указать с commit/hash и вариантом package.
- Существующий lint error `UnusedBoxWithConstraintsScope` в `DicomToolNavigation.kt:244` не скрывать baseline/suppression. Исправление toolchain/lint планируется в 5.6; новые ошибки этапа устранить или явно сообщить.
- Проверить PR #1 остаётся OPEN, auto-merge выключен; main не менялся. Коммит/публикация не означают разрешение на merge.

В этой подготовке тесты и сборка заново не запускались: production-код не менялся. Последний подтверждённый результат 5.1 — 97 JVM и 10 Android tests PASS, assembleDebug PASS, lintDebug FAIL: 1 error / 49 warnings. Реальные PACS, SD/cloud providers и ручная Android-приёмка пока не подтверждены. После 5.6 потребуется отдельный debug APK и ожидание результатов ручного тестирования пользователя; merge только по последующему отдельному разрешению.
