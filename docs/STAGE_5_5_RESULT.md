# Этап 5.5 — SCP, Foreground Service и сетевой lifecycle

Дата: 15 октября 2026 года. Репозиторий `Eskimossss/dicom-kit`; рабочая ветка `fix/stage-5-1-storage-safety`. База: `c33c4bc3903c7f7533a6844fd15f9b257a96f9d1` (этап 5.4). Исходный аудит `DICOM_KIT_AUDIT_2026-10-14.md` доступен локально; проверены P1-04, P1-05, P1-14, SCP-часть P1-15 и lifecycle-часть P2-02. Документы и исходные изменения 5.1–5.4 сохранены.

## Изменения

| Находка | Реализованное поведение |
|---|---|
| P1-04 | Каждый принятый TCP socket учитывается до реального закрытия, включая клиента без A-ASSOCIATE. Stop отключает accept/listening, даёт до 1200 мс на drain и release простаивающих Association, затем закрывает оставшиеся sockets, прерывает executor и ждёт ещё до 300 мс. STOPPED появляется после фактического завершения executors. При непрерываемом I/O остаётся STOPPING; новый сервер не запускается. Отдельный сетевой stop не ждёт SAF/startup indexing. |
| P1-05 | Запуск/остановка сервера перенесены на отдельный последовательный worker службы. Android 15 onTimeout закрывает сеть, освобождает блокировки и promptly вызывает stopSelf. Собственный сеанс ограничен 4 ч, WakeLock имеет конечный timeout с 30-секундным запасом, WifiLock освобождается при stop/error/destroy. START_NOT_STICKY, без автоматического восстановления службы. В UI описаны ограничения фонового приёма. |
| P1-14 | На accept выделяется уникальная identity с короткой меткой SCP-N. TCP, negotiation, DIMSE и закрытие используют одну identity, закреплённую также за Association. Source port, адрес и библиотечный serialNo не считаются уникальностью. Удалено объединение старых событий по endpoint-строкам; неоднозначные старые записи остаются раздельными. Run ID использует полный UUID. SCU-N сохраняется. |
| P1-15 | Проверенное сохранение, попытка ответа, завершённая локальная write/flush и ошибка записи — отдельные факты. RSP записывается как локально завершённый только после writeDimseRSP. RJ selection отличается от callback после успешной локальной записи. onClose не синтезирует A-RELEASE-RQ/RP. A700 не подменяется 0110. Сбой ответа после успешного сохранения не удаляет DICOM и не означает отказ/успех SCU. |
| P2-02, часть | Activity и Service используют один process registry; конфигурация AE/порта сохраняется, флаг running не сохраняется. UI подписывается на SCP через collectAsStateWithLifecycle. STARTING, STOPPING и serviceStopping не дают начать новую операцию до завершения очистки/уничтожения прежней службы. |

Основные файлы: `DicomStorageScpServer.kt`, `DicomScpForegroundService.kt`, `DicomRuntimeRegistry.kt`, `DicomSessionManager.kt`, `DicomSessionSummary.kt`, `SafeTechnicalSummary.kt`, `DicomModels.kt`, `DicomToolViewModel.kt`, `ReceiverScpScreen.kt`, `DicomToolNavigation.kt`. AndroidManifest уже содержит dataSync FGS/неэкспортируемую службу и необходимые разрешения; новые разрешения и обходы Android не добавлялись.

## Сохранность данных и точность журнала

Оригинальные DICOM не переписываются и не транскодируются. SCP продолжает согласовывать Implicit/Explicit VR Little Endian; поддержка исходных Deflated-байтов в SCU 5.2 сохранена. Полностью принятые .incoming_tmp/*.part при неуспешном сохранении остаются для восстановления по правилам 5.1. Неполный сетевой payload удаляется прежним механизмом. В частности, Stop при непрерываемой записи не превращает отмену в C-STORE Success.

Запись RSP означает только подтверждённое завершение локальной записи dcm4che encoder, который выполняет flush до возврата. Она не подтверждает получение/обработку ответа SCU. Ошибка такой записи может оставить удалённый результат неизвестным даже при локально проверенном DICOM. В краткой сводке SCP это UNKNOWN; результаты известных отдельных ответов остаются доступными. Safe export допускает только конечные значения Response Write; свободный текст и персональные данные в безопасный экспорт не добавляются. Health-модель и writer журнала 5.3 не менялись.

Библиотечный callback onClose не является наблюдателем PDU. Поэтому для штатного remote release сохраняется достоверное закрытие TCP и завершение обработчика, без предполагаемых RELEASE PDU. Собственные попытки release при stop выполняются ограниченно по времени; нет обещания, что узел их получил. Это сознательное ограничение детализации, а не скрытая генерация протокольных подтверждений.

## Регрессионные тесты

`ScpLifecycleStage55Test.kt`: 14 новых JVM-тестов с реальными loopback sockets/dcm4che и синтетическими DICOM: Start/Stop/Start на том же порту; остановка старой Association; TCP без negotiation; параллельные SCU; reuse source port; A700 и сохранённый .part; stop при непрерываемом provider; peer abort без фиктивного release; bind failure/retry; независимость identity от serial/port; несколько C-STORE в одной Association; stop во время цикла C-ECHO; RST после verified save без удаления оригинала; OS stop при блокированном startup indexing; churn 60 соединений, 120 C-ECHO и 120 C-STORE за 10 запусков/остановок. Проверяются counts, статусы, исходный Pixel Data, освобождение соединений и возможность повторного запуска.

`ScpLifecycleInstrumentedTest.kt`: 6 новых Android-тестов: остановка через реальный PendingIntent уведомления и повторный запуск; текущие WakeLock/WifiLock; Activity recreate/реальный поворот; фон/выключение экрана/возврат; настоящий системный callback timeout API 35; сохранение настроек без восстановления running; реальный Android Service SCP принимает C-ECHO и C-STORE, проверяется сохранённый Pixel Data. Часть сценариев объединена в одном тесте.

Три прежних JVM-теста скорректированы под SCP-N и отсутствие вымышленных RELEASE PDU; проверки wire Status, UID, Pixel Data, charset, SQ, storage и SCU сохранены. Все автоматические объекты синтетические; обычная установка приложения и AVD emulator-5554 не очищались.

## Результаты окончательной проверки

| Проверка | Результат |
|---|---|
| `testDebugUnitTest` | **PASS: 192 теста, 0 failures/errors/skipped**, включая 14 новых, регрессии 5.1–5.4 и Orthanc |
| `assembleDebug` | **PASS**, обычный applicationId `ru.medeng.dicomtool`, версия 1.3.1/code 1 |
| `lintDebug` | **FAIL: 1 существующая ошибка `UnusedBoxWithConstraintsScope`, `DicomToolNavigation.kt:249`; 49 warnings** |
| `connectedDebugAndroidTest -Pstage51Isolated=true` | **PASS: 33 теста, 0 failures/errors/skipped**, включая 6 новых; API 35, отдельный пакет `.storagesafetytest` |
| Orthanc | **PASS**: три синтетических объекта, включая Deflated, Explicit/Implicit VR LE; отдельный Orthanc TEST_PACS на порту 49437, без HTTP/реальных исследований |
| Android process death | **PASS**: SIGKILL, холодный запуск без ложного running, сохранение конфигурации, ручной restart и реальные AC/RP |
| `git diff --check` | PASS |

Итоговый `testDebugUnitTest assembleDebug lintDebug` завершился с ненулевым кодом из-за lint. Ошибка не подавлялась, baseline не добавлялся. Существуют также предупреждения toolchain об SDK XML v3/v4 и marshalling package.xml; инструменты/версии в этом этапе не обновлялись.

Окончательные логи: `app/build/stage55-final-build.log` и `app/build/stage55-android-complete.log`. XML/JUnit/Android HTML reports остаются в стандартных каталогах `app/build/test-results`, `app/build/outputs/androidTest-results` и `app/build/reports`. Нормальный APK сохранён до сборки изолированного тестового варианта: `app/build/reports/stage55/dicom-kit-stage-5-5-debug.apk`. SHA-256: `60DB481D35EEEE55AA5F84928685019ACAA89731F1A4A76B9BE381436EDADD75`.

Промежуточные неуспешные прогоны не скрыты: при первичной компиляции исправлены ошибочные API/import references; старый JVM assert ожидал вымышленные RELEASE PDU и был заменён проверкой реального TCP close. Первый Android прогон выявил гонку возврата уведомления после timeout (исправлена) и неверное чтение истории WakeLock вместо текущих locks (исправлен тест). После этого полные 32/33 Android прогоны проходили; финальный повторяет все 33 на окончательном коде. Промежуточные логи `stage55-compile.log`, `stage55-unit.log`, `stage55-android-focus.log`, `stage55-android-final.log` сохранены отдельно от итоговых.

## Перезапуск процесса Android

На изолированной установке APK через настоящий UI заданы AE `SYNTHETIC55` и порт `55155`. Проверены фактические A-ASSOCIATE-AC и A-RELEASE-RP через adb TCP forwarding. Процесс приложения PID 8304 принудительно завершён SIGKILL под UID тестового пакета (без onDestroy, без force-stop/очистки данных). После этого endpoint закрылся, NotificationRecord и ServiceRecord отсутствовали, автоматического перезапуска не произошло. Холодный запуск Activity создал PID 8676, восстановил AE/порт, но UI показал «Сервер остановлен», уведомления и слушателя не было. Явный ручной запуск снова принял Association и вернул фактический RELEASE-RP на том же порту; затем сервер штатно остановлен.

Артефакты: `process-result.txt`, `process-wire-before.txt`, XML UI до/после, dumpsys services/notifications в `app/build/reports/stage55`. Это реальное принудительное завершение процесса на API 35; low-memory killer/OEM аварийное восстановление отдельно не моделировались.

## Ограничения и ручная приёмка

- Проверен API 35 на изолированном headless AVD DicomKit_Stage54, emulator-5556; API 26–34 и 36+, реальные аппараты и прошивки OEM не проверены. Конфигурация и гарантии Android power management не выводятся из JVM-тестов.
- Системный dataSync timeout ускорен документированной настройкой `data_sync_fgs_timeout_duration=2000`, затем исходное значение восстановлено. Полный шестичасовой бюджет ОС и реальный четырёхчасовой сеанс не выдерживались в тесте. Android может остановить приложение/службу раньше, в том числе по пользовательскому действию, фоновой политике, Doze или OEM ограничениям. UI не обещает круглосуточную работу.
- Непрерываемый сторонний SAF provider может задержать завершение дисковой операции и cleanup. Сеть закрывается, блокировки освобождаются, UI показывает ожидание; новый SCP до окончания освобождения ресурсов не стартует. Продолжительность чужого блокирующего I/O приложение гарантировать не может.
- Короткий churn проверяет базовую очистку, но не заменяет многочасовую нагрузку/измерение ресурсов на целевом устройстве. Разные реальные IP с одинаковым source port дополнительно проверить вручную; identity уже не выводится ни из IP, ни из порта.
- Общая декомпозиция большой ViewModel и lifecycle/state остальных экранов остаются P2-02/5.6. Существующий UI/index I/O остаётся частично открытым P1-06. Ошибка lint оставлена видимой для 5.6.
- Перед приёмкой: несколько PACS/SCU, медленная SD/SAF, нехватка места, прерывание активной передачи, сетевые тайм-ауты, блокировка экрана/энергосбережение, длительная работа на реальном Android. Сохранение файла и подтверждение передачи должны проверяться раздельно. Сообщённый dcm4che A-ABORT/ошибка сокета выделяются в ABORT/NETWORK_ERROR; при уже завершённой локальной записи ответа ошибка закрытия даёт WARNING, а не замалчивается.

## Git и артефакты

Создаётся один отдельный локальный коммит этапа 5.5 в текущей ветке. `main` не меняется. Push, merge, rebase, squash и force push не выполняются. PR #1 остаётся открыт, auto-merge выключен; репозиторий PRIVATE. Этап 5.6 не начат.

Diff: `STAGE_5_5.patch` (из базового c33c4bc в коммит 5.5). APK и диагностические логи — в `app/build/reports/stage55` и `app/build/stage55-*.log`, в коммит не включаются. Неотслеживаемые исходные материалы аудита и патчи прежних этапов оставлены нетронутыми.

Ссылки на проверенные первичные источники: [Android FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout), [dcm4che 5.33.1 Association](https://github.com/dcm4che/dcm4che/blob/5.33.1/dcm4che-net/src/main/java/org/dcm4che3/net/Association.java), [PDUEncoder](https://github.com/dcm4che/dcm4che/blob/5.33.1/dcm4che-net/src/main/java/org/dcm4che3/net/PDUEncoder.java).
