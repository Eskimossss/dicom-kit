# Проверка интеграции и диагностика SCP — 20.10.2026

Рабочая ветка: `fix/stage-5-1-storage-safety`. Сохранён обычный merge
`ba687128f6b0da5aa28a9455679b9d768d9168af` с родителями `9c99aff` и `df05172`.
Резервная ветка: `codex/backup-before-main-integration-9c99aff`.
Push, rebase, изменение тега/Release и окончательный merge PR #1 не выполняются.
Клон `E:\Dicomtool` не изменяется. Автоматические проверки используют только синтетику.

## Первоначальный DIMSE-RSP timeout

Сохранён исходный отчёт: `app/build/scp-diagnosis-20261020/initial-results/`.
В `ScpLifecycleStage55Test.connect()` клиент задаёт `responseTimeout=2000`.
Stack trace первоначального сбоя ведёт через `Timeout.run()` к `Association.abort()`;
журнал содержит `1:DIMSE-RSP timeout expired`, затем A-ABORT. Это истечение
клиентского бюджета ожидания, а не ответ PACS с ошибкой хранения.

Merge не изменил `DicomStorageScpServer`, `VerifiedDicomStore`, репозиторий
или исходный тест конкурентного сохранения относительно `9c99aff`.
В save-пути проверены read-lock приёма, короткие блокировки каталогов и отдельная
блокировка SOP. Изменившиеся объекты одного UID публикуются последовательно;
перед удалением источника проверяются байты назначения и фиксируется durable ledger.
Запись временного payload использует `File.createTempFile()` через IncomingPayloadTracker.

Участки кода: `ScpLifecycleStage55Test.kt:33` — клиентский deadline;
`DicomStorageScpServer.kt:983,1006,1122` — sync payload, verified save и последующая
запись ответа; `VerifiedDicomStore.kt:161,188,211` — блокировка SOP, durable ownership,
проверка источника перед удалением. Бюджет клиента включает необходимые проверки
диска/сохранения и очередь одного SOP. Отвечать Success до их завершения нельзя.

Пять фактических отдельных запусков исходного теста: **5/5 PASS** (`isolated-1..5`).
Исходный класс: **17/17 PASS** (`class-results`). После добавления диагностики
три фактических повтора пары конкурентный приём + клиентский timeout:
**6/6 PASS** (`actual-1..3-results`); класс с новой проверкой: **18/18 PASS**.
Два дополнительных вызова `confirmed-2/3` были UP-TO-DATE и не считаются повторами.
Максимальные round-trip времена в `actual-1..3`: 1059, 904 и 857 мс.
Во всех конкурентных проверках сохранены прежние требования: 24 разных Pixel Data,
24 файла, Success для каждого STORE, завершение соединений и отсутствие `.part`.

Подтверждён непосредственный механизм сбоя: дедлайн 2 секунды истёк раньше ответа.
Воспроизводимая регрессия merge, deadlock или потеря объектов не обнаружены.
Задержка диска/планирования при нагрузке — вероятное объяснение, **не доказанная
первопричина**: исходный отчёт не содержит профиля серверного потока или длительностей
отдельных стадий. Утверждать, что конкретный fsync/индексация/эмулятор вызвал тот сбой,
нельзя. Сетевые тайм-ауты не увеличены; рабочий алгоритм приёма/хранения не изменён.

## Изменения

- `ScpLifecycleStage55Test`: монотонные замеры save+index и C-STORE round-trip,
  выводимые также при падении, без изменения существующих safety assertions.
- Новый `clientResponseTimeoutDoesNotUndoVerifiedObjectOrClaimPeerReceipt`:
  latch удерживает ответ после реального verified save до фактического timeout клиента.
  Проверяются исходные pixels, один сохранённый объект, отсутствие `.part` и WARNING.
  Локальная TCP-запись может завершиться или упасть после timeout удалённого клиента;
  тест различает оба факта и не выдаёт LOCAL_COMPLETED за подтверждение получения.
- Полный прогон обнаружил отдельную гонку старого `testRealCEchoAndCStoreScpEndToEnd`:
  клиент уже получил RJ, а серверный callback ещё не добавил ASSOCIATE_RJ в журнал.
  Snapshot заменён ограниченным ожиданием **фактического события** через StateFlow.first.
  `Dcm4cheCompatibilityTest.kt:250–255`. Код отказа и все проверки журнала сохранены.
  Три отдельных прогона класса: PASS.
- Новый lint на интегрированном коде обнаружил `ByteOrderMark` в
  `ThirdPartyLicensesDialog.kt:496`, пришедшем из main. Удалён только один U+FEFF
  внутри MPL-константы; остальной текст лицензий сохранён. Без baseline/suppression.

Первый новый timeout-тест ошибочно требовал обязательного FAILED локальной записи.
На реальном TCP получен LOCAL_COMPLETED после timeout клиента; проверка уточнена
по реальной семантике транспорта. Исходный конкурентный тест не ослаблялся.
Промежуточные неудачные логи сохранены, включая `diagnostic-tests.log`,
`final/build.log` (гонка RJ) и `final/build-verified.log` (BOM lint).

## Итоговые проверки

| Проверка | Итог / доказательство |
|---|---|
| testDebugUnitTest после BOM | **229/229 PASS**, без failures/errors/skips; `final/jvm-after-bom.log`, `final/jvm-after-bom-results` |
| assembleDebug / assembleRelease | **PASS**; `final/build-after-bom.log` |
| lintDebug | **PASS: 0 errors, 42 warnings, 1 information**; `final/build-after-bom.log` и lint XML |
| Orthanc + независимый parser | **PASS**, 3 synthetic instances: Implicit LE, Explicit LE, Deflated; PN и Pixel Data точные |
| Генератор + pydicom 3.0.2 | **PASS**, три стандартные кодировки, точные PN/charset и одинаковые Pixel Data |
| Сертификат нового release APK | **PASS**, apksigner verify, RSA 4096, SHA-256 совпал с опубликованным APK |
| Обновление v1.3.1 на эмуляторе | **PASS**, install -r без удаления пакета; appId и firstInstallTime сохранены; release cold launch PASS |
| Android instrumentation | **49/49 PASS**, без failures/errors/skips, новый API35 AVD; `final/android-verified.log` и `final/android-verified-results` |

Пути логов в таблице относительны `app/build/scp-diagnosis-20261020`.
Полный JVM-прогон повторён после удаления BOM; прежние результаты не выданы за
проверку последнего кода. 229 вместо прежних 228 — одна новая regression check.
Lint warnings: GradleDependency 33, AndroidGradlePluginVersion 3,
IconLauncherShape 5, OldTargetApi 1; Information AutoboxingStateCreation 1.

Первый Android-прогон: **44 PASS, 5 FAIL**, ошибки Back и чтения clipboard. Системный
ClipboardService отказал `ru.medeng.dicomtool.storagesafetytest`: application is not
in focus. `dumpsys window` показал `mCurrentFocus=Application Not Responding:
com.android.systemui`, при этом mFocusedApp указывал на тестовую Activity.
Это подтверждённая помеха окружения, а не доказательство ошибки безопасной проекции
экспорта. Снимок состояния сохранён в `final/emulator-window-anr.txt`, исходный
прогон — `final/android.log`, XML — `final/android-results`.
После reboot прежнего AVD ANR SystemUI повторился на Launcher до запуска DICOM Kit.
Создан отдельный AVD `DicomKit_Integration_20261020` на установленном API35
google_apis_playstore_tablet x86_64: 1280x800 / 160dpi, RAM 3072MB, 4 cores.
Ранее использовались 2560x1600 / 320dpi, RAM 2048MB, 2 cores; логическая область
Compose осталась 1280x800dp. Прежний AVD сохранён, сторонний emulator-5554 не тронут.
Новый AVD загрузился с Launcher в фокусе без ANR. Проверки Android-тестов не менялись.
Целевой повтор Back + весь класс журнала + clipboard тегов: **7/7 PASS**
(`final/android-focus-recheck.log`); затем полный неизменённый набор **49/49 PASS**
за 5м56с. Трёхминутная проверка SCP приняла 297 синтетических объектов, файл
наблюдений памяти/потоков — `final/soak-metrics.csv`; это не доказательство
отсутствия всех возможных утечек на произвольной нагрузке.

Обычные APK после BOM сохранены отдельно от instrumentation:

- `app/build/reports/integration-20261020/dicom-kit-integration-debug.apk`.
- `app/build/reports/integration-20261020/dicom-kit-integration-release.apk`.

После локального коммита выполняется обычная упаковка debug/release из его исходников
(`final/package-committed.log`). Точные SHA-256 находятся в sidecar `.apk.sha256`,
а исходный commit, сертификат, badging и хеши tracked build inputs — в provenance
рядом с APK. Сравнение этих inputs исключает использование старой instrumentation
сборки. APK не добавляются в Git и не публикуются как замена существующему Release.

## Подпись, безопасность и ограничения

Сохранены `ru.medeng.dicomtool`, `versionName=1.3.1`, `versionCode=10301`.
Release собирается постоянным ключом из локальной конфигурации, переданной процессу
через environment; пароли/keystore/config не добавляются в Git и не печатаются.
Фактический SHA-256 сертификата должен совпадать с опубликованной v1.3.1:
`46:90:B6:71:B0:0B:DD:A6:5C:97:40:A2:7D:BD:22:50:DC:8C:CB:DD:95:79:7E:11:2C:3C:47:63:AA:6C:1A:8D`.

Остаются прежние ограничения: OEM/Doze, физические/cloud SAF, vendor PACS и GPU
нуждаются в проверке на реальном устройстве; блокирующий SAF может задержать отмену;
экспериментальные кодировки не гарантируют совместимость; P2-02 частично исправлен.
Успешные повторы не доказывают невозможность повторного короткого клиентского timeout
при произвольной нагрузке. Для следующего подобного сбоя доступны замеры теста;
для точной первопричины потребуется профиль/трассировка конкретного зависшего этапа.

Push и обновление PR — только после отдельного разрешения пользователя.
По имеющимся проверкам ветка подходит для push и ревью PR после этого разрешения;
первопричина физической задержки первоначального timeout остаётся указанным выше
ограничением диагностики. Эти результаты не разрешают окончательное слияние PR.
