# Исправления независимого аудита MWL — 1.5.0-alpha.2

Дата: 2026-10-20. Репозиторий: `E:\Dicomtool`. Ветка: `feature/stage-7-worklist-mwl`.

Исходный аудит: `d205a1d06686c79217750570f7116ae1bc4e66e5`; исправления начаты с чистого `a5cbf2ea948205918b68997f98a2b38e83fcb558` (1.5.0-alpha.1, 10501). Использован полный предыдущий отчёт с 16 замечаниями. Нумерация ниже совпадает с ним: 6 P1, 9 P2, 1 P3; P0 не было.

Первоначально четыре новых теста были запущены на неизменённой реализации и завершились ожидаемыми ошибками: пункты 03, 06, 08, 12. Остальные регрессии проверяют конкретные сетевые, файловые и lifecycle-сценарии из полного аудита. Старые тесты не удалялись. В старом wildcard-тесте Patient ID исправлено стандарт-некорректное ожидание: wildcard больше не считается Single Value Matching. Сетевые фикстуры дополнены обязательным описанием процедуры без ослабления исходных проверок.

## Реестр исправлений

Пути `mwl/...` относятся к `app/src/main/java/ru/medeng/dicomtool/`; тестовые классы — к `app/src/test/java/ru/medeng/dicomtool/`, instrumented — к `app/src/androidTest/java/ru/medeng/dicomtool/`.

### MWL-01 — P1: SCP не обрабатывал конкурентный C-CANCEL

- **Причина:** Обработка выполнялась в потоке чтения; CancelRQHandler отсутствовал.
- **Исправление:** Query task вынесен в executor; обработчик зарегистрирован до dispatch отдельно для каждой Association/Message ID. Проверки отмены выполняются при обходе назначений; общий gate предотвращает новую отправку Pending после обработки отмены. Handler удаляется в finally.
- **Изменённые файлы:** `mwl/scp/MwlScpServer.kt`.
- **Регрессионные тесты:** `MwlAuditNetworkRegressionTest.audit01_cancelIsAssociationScopedEvenWithIdenticalMessageIds; audit01_dcmtkCancelStopsActiveFindWithFe00`.
- **Повторная проверка:** PASS: две ассоциации с одинаковыми первыми Message ID изолированы. DCMTK 3.7.0: один Pending, затем Cancel/FE00.
- **Ограничения:** Уже переданный в TCP Pending может быть получен SCU после отправки C-CANCEL, до подтверждения FE00; это допустимо.

### MWL-02 — P1: SCU представлял Failure/Cancel как Success

- **Причина:** После любого выхода из цикла возвращался Result.success и записывался успешный итог.
- **Исправление:** Введены MwlQueryOutcome и MwlQueryException с частичными результатами. Success, подтверждённый Cancel, локальная отмена без подтверждения и Failure отображаются раздельно. Нет Status или финального ответа — ошибка. Подтверждённый DIMSE-итог сохраняется при ошибке последующего A-RELEASE.
- **Изменённые файлы:** `mwl/scu/MwlScuClient.kt; mwl/model/MwlScuModels.kt; mwl/ui/MwlViewModel.kt`.
- **Регрессионные тесты:** `MwlAuditNetworkRegressionTest.audit02_failureRetainsPartialResultsAndNeverBecomesSuccess; audit02_cancelIsActuallySentAndRequiresRemoteConfirmation; audit02_missingStatusCannotConfirmSuccess`.
- **Повторная проверка:** PASS: A700 остаётся ошибкой и сохраняет предыдущий Pending; реальная отмена подтверждается FE00; отсутствующий Status не подтверждает успех.
- **Ограничения:** Сетевой обрыв не позволяет установить неизвестный удалённый итог; интерфейс явно сохраняет эту неопределённость.

### MWL-03 — P1: Неправильное совместное DA/TM Range Matching

- **Причина:** Независимые фильтры создавали ежедневное временное окно вместо непрерывного интервала.
- **Исправление:** Если оба ключа заданы диапазонами, сравнивается единый интервал DA+TM; поддержаны открытые границы и дробная часть TM.
- **Изменённые файлы:** `mwl/engine/MwlMatchingEngine.kt`.
- **Регрессионные тесты:** `MwlAuditMatchingRegressionTest.audit03_combinedDateTimeRangeIncludesIntermediateDay`.
- **Повторная проверка:** PASS: промежуточный день включён, значения за начальной/конечной границей исключены.
- **Ограничения:** Timezone Query Adjustment не согласовывается; значения трактуются как локальные DICOM DA/TM.

### MWL-04 — P1: Незаметная замена кириллицы

- **Причина:** Объявленный charset не проверялся на представимость реальных строк.
- **Исправление:** До отправки проверяется байтовый round-trip всех текстовых атрибутов, включая вложенные Items. Непредставимые строки приводят к Failure, а не к вопросительным знакам. ASCII передаётся без Specific Character Set.
- **Изменённые файлы:** `mwl/engine/MwlDatasetBuilder.kt; mwl/scp/MwlScpServer.kt`.
- **Регрессионные тесты:** `MwlAuditEngineRegressionTest.audit04_wireRoundTripPreservesCyrillicIncludingNestedPn; audit04_unrepresentableTextFailsInsteadOfReplacingCharacters; MwlAuditNetworkRegressionTest.audit04_unrepresentableResponseReturnsFailureNotCorruptedPending`.
- **Повторная проверка:** PASS: ISO_IR 144/192, ISO 2022 IR 144, GB18030 сохраняют проверенное кириллическое PN; ASCII/Latin-1 отвергают непредставимый ответ; Latin-1 поддерживает MÜLLER^ANDRÉ.
- **Ограничения:** Проверенный набор символов не является полной сертификацией всех языков и комбинаций ISO 2022. Автотранслитерации нет.

### MWL-05 — P1: Неатомарное сохранение

- **Причина:** Старый файл удалялся до renameTo; результат замены игнорировался; пациент и назначения сохранялись раздельно.
- **Исправление:** Единый snapshot пациентов и назначений записывается через fsync и ATOMIC_MOVE с заменой. Память обновляется после успешной записи. Профили используют тот же атомарный writer. Старые файлы читаются и заменяются snapshot при следующем сохранении. SCP получает согласованный snapshot под блокировкой.
- **Изменённые файлы:** `mwl/repo/AtomicMwlFile.kt; LocalMwlRepository.kt; MwlRepository.kt; MwlScuProfileRepository.kt`.
- **Регрессионные тесты:** `MwlAuditDataRegressionTest.audit05_failedReplacementPreservesBothCommittedFileAndMemory; audit05_legacyDataMigratesAndClearDoesNotReseedOnRestart; audit05_profileWriteFailureKeepsPreviousProfile`.
- **Повторная проверка:** PASS: искусственный отказ замены сохраняет байты, память и данные после открытия; legacy-пара сохраняется согласованно; очистка не вызывает повторного seed.
- **Ограничения:** На файловой системе без атомарной замены операция завершается ошибкой. Физическое отключение питания и аппаратные ошибки носителя не моделировались.

### MWL-06 — P1: PHI в обезличенном отчёте

- **Причина:** Регулярные выражения не скрывали Accession и голые значения имени.
- **Исправление:** Обезличенный экспорт исключает свободный текст заголовков, сводок и технических полей; сохраняет категории и разрешённые числовые технические поля. Опция includePhi остаётся явной.
- **Изменённые файлы:** `mwl/log/MwlTechnicalLogger.kt`.
- **Регрессионные тесты:** `MwlAuditDataRegressionTest.audit06_anonymizedReportCannotContainAccessionOrBarePatientName`.
- **Повторная проверка:** PASS: контрольные Accession/PatientName отсутствуют в обезличенном отчёте и присутствуют при явном includePhi.
- **Ограничения:** Полный локальный журнал может содержать чувствительные значения; он хранится в приватном каталоге приложения. Отметка synthetic сама по себе не является обезличиванием.

### MWL-07 — P2: Whitelist обрывал TCP

- **Причина:** В negotiate выбрасывался DicomServiceException вместо созданного AAssociateRJ.
- **Исправление:** Запрещённый Calling AE отклоняется настоящим RJ service-user/reason 3. IP-фильтр использует RJ с no-reason-given. Сравнение AE сохраняет регистр.
- **Изменённые файлы:** `mwl/scp/MwlScpServer.kt`.
- **Регрессионные тесты:** `MwlAuditNetworkRegressionTest.audit07_whitelistSendsRjWithCallingAeReason; MwlScpAndScuDimseTest.testWhitelistCallingAeRejection`.
- **Повторная проверка:** PASS: получен AAssociateRJ с фактическими source/reason.
- **Ограничения:** Whitelist — фильтр узлов, не криптографическая аутентификация; TLS не добавлялся.

### MWL-08 — P2: Неверные правила matching и позиции PN

- **Причина:** Patient ID сравнивался без регистра и с wildcard; пустые компоненты PN удалялись.
- **Исправление:** Patient ID/Modality/Station AE используют Single Value Matching; остальные неперсональные строки сравниваются с учётом регистра. PN обрабатывается отдельно, с сохранением позиций ^ и групп =; wildcard не интерпретируется как PN для LO/SH.
- **Изменённые файлы:** `mwl/engine/MwlMatchingEngine.kt`.
- **Регрессионные тесты:** `MwlAuditMatchingRegressionTest.audit08_patientIdIsCaseSensitiveAndPnComponentsKeepPositions; MwlMatchingEngineTest`.
- **Повторная проверка:** PASS: другой регистр Patient ID не совпадает; ^IVAN не совпадает с IVAN^; старые PN/wildcard/sequence проверки сохранены.
- **Ограничения:** Для PN выбран документированный компонентный поиск без регистра, с незаданными компонентами как ограничениями нулевой длины; фонетический/fuzzy matching не реализован.

### MWL-09 — P2: Неограниченное ожидание Association и отсутствие отмены

- **Причина:** acceptTimeout задавался удалённой connection; сокет не был доступен отмене до connect(). После Pending нужен отдельный retrieveTimeout.
- **Исправление:** Тайм-ауты connect/accept/response/retrieve/release заданы локально. ConnectionMonitor публикует TCP-сокет для отмены во время negotiation. C-CANCEL пишется вне UI-потока; отсутствие подтверждения ограничено дедлайном с закрытием транспорта. Ресурсы закрываются в finally.
- **Изменённые файлы:** `mwl/scu/MwlScuClient.kt`.
- **Регрессионные тесты:** `MwlAuditNetworkRegressionTest.audit09_associationBlackholeTimesOutAndCanBeCancelled; audit09_pendingWithoutFinalTimesOutAndKeepsPartialRecords`.
- **Повторная проверка:** PASS: молчащий AC истекает по тайм-ауту и отменяется; Pending без Final не висит и сохраняется как частичный результат.
- **Ограничения:** Разрешение DNS выполняется системным resolver; отдельный принудительный дедлайн resolver не добавлялся.

### MWL-10 — P2: Недопустимая SPS Sequence принималась с Success

- **Причина:** Из нескольких Items молча использовался первый.
- **Исправление:** Проверяются VR SQ, число Items, календарные даты и формы диапазонов. Ошибочный Identifier получает A900 без Pending. Пустая SQ остаётся допустимым универсальным return key.
- **Изменённые файлы:** `mwl/engine/MwlMatchingEngine.kt; mwl/scp/MwlScpServer.kt`.
- **Регрессионные тесты:** `MwlAuditEngineRegressionTest.audit10_invalidSpsAndRangesAreRejected; MwlAuditNetworkRegressionTest.audit10_malformedSpsReturnsFailureAndNoPending`.
- **Повторная проверка:** PASS: два Items, неверный VR, несуществующая дата и повреждённые диапазоны отвергаются.
- **Ограничения:** Неподдерживаемые optional matching keys игнорируются по Annex K; это не объявляется ошибкой обязательного matching.

### MWL-11 — P2: Недостаточная валидация обязательных атрибутов и VR

- **Причина:** Пустое SPS Time допускалось; описания и длины SH/LO/PN не проверялись полностью; генератор создавал слишком длинное SPS Location.
- **Исправление:** Добавлена общая валидация пациентов/назначений в repository, импорт, UI и builder. Проверяются Type 1/1C доступной модели, календарная дата, TM, ограничения VR. Генератор создаёт допустимое Location. В редактор добавлены IDs, Location и Station Name для исправления старых записей.
- **Изменённые файлы:** `mwl/model/MwlValidation.kt; SyntheticMwlGenerator.kt; mwl/repo/LocalMwlRepository.kt; mwl/engine/MwlDatasetBuilder.kt; mwl/ui/MwlAppointmentsTab.kt; MwlViewModel.kt`.
- **Регрессионные тесты:** `MwlAuditEngineRegressionTest.audit11_requiredFieldsVrAndSyntheticTemplatesAreValidated; MwlDataAndValidationTest`.
- **Повторная проверка:** PASS: пустые обязательные поля и слишком длинный SH отвергаются; все шаблоны проходят валидацию.
- **Ограничения:** Старые невалидные значения не обрезаются и не заменяются вымышленными клиническими данными. Их нужно исправить в редакторе перед передачей; иначе запрос получает Failure.

### MWL-12 — P2: Импорт собственного экспорта не работал

- **Причина:** Парсер плоского объекта обрезал вложенные массивы; экспорт не включал все поля.
- **Исправление:** Парсер валидирует вложенные структуры, escape-последовательности и конец документа; экспорт включает все поля и timestamps. Импорт выполняется единой транзакцией, сохраняет пациента без назначений, отвергает некорректные связи/дубликаты и замену реальных записей.
- **Изменённые файлы:** `mwl/repo/MwlJsonUtils.kt; LocalMwlRepository.kt; mwl/ui/MwlAppointmentsTab.kt`.
- **Регрессионные тесты:** `MwlAuditDataRegressionTest.audit12_exportImportPreservesEveryFieldAndUnattachedPatient; audit12_malformedOrPartialImportCannotChangeCommittedData`.
- **Повторная проверка:** PASS: полное равенство пациентов/назначений после round-trip; повреждённый импорт не меняет сохранённые данные.
- **Ограничения:** Формат version=1; импорт ограничен 8 MiB и глубиной JSON 64. Экспорт предназначен для явно помеченных synthetic-записей.

### MWL-13 — P2: Инспектор скрывал произвольные Sequence

- **Причина:** Раскрывалась только SPS первого уровня; остальные SQ читались через getString.
- **Исправление:** Единый рекурсивный formatter показывает любые SQ/Items, VR, многозначные и бинарные атрибуты. SCU сохраняет копию полного исходного Attributes.
- **Изменённые файлы:** `mwl/engine/MwlDatasetInspector.kt; mwl/ui/MwlScuTab.kt; mwl/scu/MwlScuClient.kt`.
- **Регрессионные тесты:** `MwlAuditInspectorRegressionTest.audit13_arbitrarySequencesItemsUnknownVrAndMultipleValuesAreVisible; MwlAuditNetworkRegressionTest.audit13_scuPreservesUnknownAttributesAndNestedSequences`.
- **Повторная проверка:** PASS: два Items, вложенная code sequence, неизвестный атрибут, UN и многозначный CS сохранены/отображены.
- **Ограничения:** Глубина отображения ограничена 32. При Implicit VR неизвестный private VR невозможно восстановить без словаря; сырые данные сохраняются.

### MWL-14 — P2: Истекал Wake Lock и неверно обещалась фоновая Wi-Fi гарантия

- **Причина:** CPU lock истекал через четыре часа без обновления; LOW_LATENCY не обеспечивает screen-off на Android 10+.
- **Исправление:** CPU lease ограничен двумя минутами и обновляется каждую минуту только во время работы; stop/destroy/failure освобождают ресурсы. API 26–33 использует HIGH_PERF. MWL переведён на connectedDevice для внешних сетевых устройств; добавлены требуемые разрешения. Callback обновляет IP без перепривязки listener 0.0.0.0 и снимается при destroy.
- **Изменённые файлы:** `mwl/scp/MwlLockLease.kt; MwlScpForegroundService.kt; app/src/main/AndroidManifest.xml`.
- **Регрессионные тесты:** `MwlAuditPlatformRegressionTest.audit14_cpuLeaseRenewsPastFourHoursAndStopReleasesOnce; audit14_partialAcquisitionFailureReleasesCpuLease; MwlAuditInstrumentedTest.audit14_serviceTypeScreenOffNotificationStopAndStorageIndependence`.
- **Повторная проверка:** PASS: виртуальные пять часов renewal; ошибки частичного захвата; Android 15 эмулятор: реальный старт FGS, screen-off loopback, stop из уведомления, снятие Wake Lock, освобождение порта и работающий Storage SCP.
- **Ограничения:** Android 14+ сам заменяет HIGH_PERF на LOW_LATENCY. Гарантию фонового физического Wi-Fi приложение дать не может. Doze/OEM/Wi-Fi roaming и длительный физический прогон остаются ручными проверками.

### MWL-15 — P2: Синхронный и неограниченный журнал

- **Причина:** fsync выполнялся под общим lock сетевого вызова; файл рос без ротации и целиком накапливался при загрузке.
- **Исправление:** Введён один фоновый writer, очередь 256, ротация 2 MiB + один backup, ограниченный хвост при чтении. Переполнение учитывается; статистика и ошибки записи доступны в отчёте. Таймеры используют fixed delay.
- **Изменённые файлы:** `mwl/log/MwlTechnicalLogger.kt; mwl/scu/MwlScuClient.kt; mwl/scp/MwlScpServer.kt`.
- **Регрессионные тесты:** `MwlAuditDataRegressionTest.audit15_diskStallCannotBlockRecordAndQueueIsBounded; audit15_logRotationAndRestartAreBounded`.
- **Повторная проверка:** PASS: заблокированный writer не блокирует 1000 recordEvent; память ограничена; переполнение видно; размер активного и backup файлов ограничен, порядок после открытия корректен.
- **Ограничения:** При переполнении очереди дисковые записи пропускаются с учётом счётчика. Полный журнал не является гарантированным медицинским архивом.

### MWL-16 — P3: Некорректный UID-корень в фикстурах

- **Причина:** Тестовые Study UID использовали зарезервированное пространство 1.2.840.10008.1.999.*.
- **Исправление:** Фикстуры заменены на 2.25.*. Рабочий генератор UIDUtils.createUID() не изменён.
- **Изменённые файлы:** `MwlCharsetEncodingTest.kt; MwlMatchingEngineTest.kt`.
- **Регрессионные тесты:** `MwlAuditEngineRegressionTest.audit16_generatedUidsUseUuidRootAndRemainUnique`.
- **Повторная проверка:** PASS: 1000 созданных UID имеют корень 2.25, допустимую длину и не повторяются в выборке; запрещённых фикстур больше нет.
- **Ограничения:** Уникальность основана на UUID; конечная тестовая выборка не является математическим доказательством глобальной уникальности.

## Проверки и воспроизводимость

- `gradlew.bat testDebugUnitTest`: **288 тестов, 0 failures/errors, 1 skipped** (287 выполнены успешно). Единственный пропуск: `ScuTransmissionSafetyTest.isolatedOrthancAcceptsSyntheticDeflatedAndUncompressedObjects`, поскольку не задан `STAGE52_ORTHANC_PORT`. Storage SCP/SCU, C-ECHO, журнал и сессии входят в полный набор.
- Добавлены **30 JVM-регрессий** в шести классах `MwlAudit*RegressionTest`. Внешние проверки: существующие два DCMTK-теста и новый конкурентный `findscu --cancel 1` на DCMTK **3.7.0**. Отмена завершается настоящим FE00 после одного Pending.
- `gradlew.bat lintDebug`: успешно, ошибок нет; предупреждения проекта не скрывались baseline-файлом.
- Эмулятор: **Pixel Tablet, Android 15 / API 35**, изолированное приложение `.storagesafetytest`. `MwlAuditInstrumentedTest`: **2/2 PASS**. Проверены тип FGS, loopback при выключенном экране, остановка из уведомления, Wake Lock, порт и независимость Storage; второй тест открывает экран «О приложении» и проверяет фактические version/branch/SHA.
- Команда эмулятора: `gradlew.bat connectedDebugAndroidTest -Pstage51Isolated=true -Pandroid.testInstrumentationRunnerArguments.class=ru.medeng.dicomtool.MwlAuditInstrumentedTest`.
- Локальные XML/HTML доказательства: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`, `app/build/outputs/androidTest-results/connected/debug/`, `app/build/reports/lint-results-debug.html`.

## Git и подготовка сборки

Логические коммиты исправлений:

1. `87710a7` — атомарные данные, валидация, JSON и безопасный ограниченный журнал.
2. `9881846` — конкурентный SCP, matching, charset и Identifier.
3. `074bd92` — итог SCU, тайм-ауты, отмена, инспектор и сетевые регрессии.
4. `a6977ab` — Android FGS, обновляемые блокировки и instrumented-проверки.
5. Финальный коммит подготовки — документация и `1.5.0-alpha.2`, `versionCode=10502` по принятой схеме `10501 -> 10502`; его SHA определяется `git rev-parse HEAD` и приводится в итоговом сообщении.

`applicationId=ru.medeng.dicomtool`, release signing, About screen, автоматические Git/build-time поля и `build-test-apk.ps1` сохранены. Итоговая сборка выполняется **после коммитов**, из чистого дерева:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-test-apk.ps1
```

Итоговый APK: `builds/DICOM-Kit-1.5.0-alpha.2-debug.apk`. SHA-256 выводится штатным скриптом, повторно проверяется после копирования и сохраняется рядом в `.apk.sha256`; точный SHA и финальный Git HEAD приводятся в итоговом сообщении. Они не включаются в коммит самого APK, чтобы не создавать циклическую зависимость метаданных. APK alpha.1 не удаляются и не перезаписываются. `builds/` остаётся исключённым из Git. Alpha не объявляется стабильным релизом.

Push, PR, merge, rebase, теги и GitHub Release не выполняются; main не изменяется.

## Оставшиеся проверки на реальных устройствах

Автоматические результаты не заменяют следующие проверки:

1. Телефон и планшет: редакторы в портретной/альбомной ориентации, клавиатура, длинные имена, дерево вложенных SQ, видимость отдельных итогов Success/Failure/Cancel/частичных результатов.
2. Обновление установленной alpha.1 без очистки данных: пациенты, назначения, профили и настройки; корректировка старых невалидных SH/обязательных значений через редактор. Первое сохранение переносит данные в единый snapshot.
3. Физическая модальность/DCMTK в локальной сети: кириллица ISO_IR 144/192, ASCII/Latin-1, несколько назначений пациента, combined DA/TM range, whitelist, C-CANCEL и повторное подключение.
4. Реальный телефон/планшет: длительная блокировка экрана более четырёх часов, Doze, ограничения производителя, Ethernet/Wi-Fi, смена точки доступа/IP и повторные запросы после восстановления сети. Незавершённые ассоциации не воспроизводятся автоматически.
5. Совместные Storage SCP/MWL SCP на разных портах: независимая остановка, уведомления, C-ECHO и C-STORE. Storage остаётся со своим существующим dataSync-поведением.
6. Аварийное отключение питания/перезапуск во время сохранения, нехватка места и аппаратные сбои накопителя. Автоматический fault injection покрывает программный отказ атомарной замены, но не физическую надёжность носителя.
7. Внешний Orthanc при настроенном `STAGE52_ORTHANC_PORT`; полная ISO 2022/языковая матрица и устройства конкретных производителей.

## Нормативные ориентиры

- [DICOM PS3.4 Annex K, C-FIND и статусы](https://dicom.nema.org/medical/dicom/current/output/chtml/part04/sect_K.4.html).
- [MWL keys, обязательные атрибуты и combined DA/TM range](https://dicom.nema.org/medical/dicom/current/output/chtml/part04/sect_K.6.html).
- [PS3.4 matching](https://dicom.nema.org/medical/dicom/current/output/chtml/part04/sect_C.2.2.2.html).
- [PS3.8 A-ASSOCIATE-RJ](https://dicom.nema.org/medical/dicom/current/output/chtml/part08/sect_9.3.4.html).
- [PS3.5 UID и UUID-derived UID](https://dicom.nema.org/medical/dicom/current/output/chtml/part05/chapter_9.html), [B.2](https://dicom.nema.org/Medical/Dicom/current/output/chtml/part05/sect_B.2.html).
- [Android connectedDevice FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device), [Wi-Fi lock ограничения](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF).

Готовность: тестовая alpha.2 предназначена для ручной проверки на синтетических данных. Полная совместимость со всем медицинским оборудованием и надёжность фоновой физической сети не объявляются подтверждёнными.
