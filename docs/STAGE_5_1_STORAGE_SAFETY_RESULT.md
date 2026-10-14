**DICOM Kit — этап 5.1: безопасность хранения**

Дата: 14 октября 2026 года. Ветка: `fix/stage-5-1-storage-safety`.
База: `faf2e81a934763000db202e658aa213c66556e3e` (актуальный origin/main при начале работ).
Аудированный коммит: `37043fa5673da2066f0ce98ffc1680aba7c0cc1b`.

Изначально локальная рабочая папка не имела HEAD: в ней находились только результаты аудита и незаполненный Git-репозиторий. Получен origin/main; GitHub compare подтвердил четыре новых коммита, меняющих только README. Рабочая ветка создана от актуального main. Изменения подготовлены для локального коммита по отдельному разрешению пользователя; main не изменён, push/merge/PR не выполнялись.

**Исправленные дефекты**

| Аудит | Исправление | Проверка |
|---|---|---|
| P0-01 | Идентичность — полный потоковый SHA-256 + фактическое число прочитанных байтов. Различающееся содержимое сохраняется отдельно. Проверка stage и final чтением после закрытия потока; размер provider не используется как доказательство. Источник удаляется после проверки и сохранения реестра | JVM; реальный SAF provider: одинаковые размеры/разные pixels, настоящий duplicate, short write, write exception, SIZE=0 |
| P0-02 | Один алгоритм для приёма и миграции в обоих направлениях. Временный файл, публикация без замены, UUID для отличающихся объектов, проверка результата перед удалением источника, поиск уже проверенной одинаковой копии при повторе | JVM collision/copy failure/interruption; Android миграция internal→SAF→internal с collision |
| P0-03 | SAF удаляется только по приватному реестру созданных приложением документов и совпадающему текущему fingerprint. Нет удаления SAF-директорий. Unknown/legacy/changed/unreadable documents оставляются нетронутыми | JVM и Android: keep.txt, unknown DICOM, unknown directories; provider специально умеет рекурсивно удалять директории |
| P1-01 | Проверка синтаксиса UID, длины и leading zeros; безопасные компоненты, canonical containment, обход internal без symlinks | JVM набор traversal/invalid UID; loopback C-STORE возвращает Failure и сохраняет полностью полученный payload |
| P1-03 | Общая координация по internal root: короткие directory locks, per-destination SOP lock, shared receive gate; cleanup эксклюзивен. Миграции сериализованы между собой, независимый приём остаётся параллельным | JVM 8 разных объектов одного UID и параллельная работа двух независимых UID; Android 4 объекта одного UID и одновременные противоположные миграции |

Полностью полученный DICOM в `.incoming_tmp` больше не уничтожается при ошибке безопасного сохранения. Незавершённый сетевой payload по-прежнему можно удалить: он не является успешно полученным объектом. Нестандартные исходники не преобразуются; невалидные UID вызывают Failure, полностью полученные байты остаются для разбора/восстановления.

**Изменённые файлы**

- [VerifiedDicomStore.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/data/storage/VerifiedDicomStore.kt>) — общий алгоритм, потоковые fingerprints, координация, проверка путей, private ownership ledger, internal backend.
- [SafStorageAccess.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/data/storage/SafStorageAccess.kt>) — ContentResolver/DocumentsContract backend, корректные tree document URI, отсутствие зависимости от SIZE/rename.
- [DicomStorageManager.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/data/storage/DicomStorageManager.kt>) — безопасное сохранение, обе миграции, удаление только принадлежащих приложению SAF documents.
- [DicomStudyRepository.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/data/storage/DicomStudyRepository.kt>) — единая политика сохранения вместо резервной небезопасной копии, координированное удаление, безопасный internal обход.
- [DicomStorageScpServer.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt>) — сохранение complete failed incoming payload; уже классифицированный DicomServiceException не заменяется другим исключением.
- [StorageSafetyTest.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/test/java/ru/medeng/dicomtool/StorageSafetyTest.kt>) — 19 новых JVM-регрессий.
- [Dcm4cheCompatibilityTest.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/test/java/ru/medeng/dicomtool/Dcm4cheCompatibilityTest.kt>) — 2 новых настоящих TCP C-STORE regression tests.
- [StorageSafetyInstrumentedTest.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/androidTest/java/ru/medeng/dicomtool/StorageSafetyInstrumentedTest.kt>) — 10 Android tests production manager/backend/repository.
- [Stage51DocumentsProvider.java](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/androidTest/java/ru/medeng/dicomtool/storagefixtures/Stage51DocumentsProvider.java>), [UriGrantActivity.java](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/androidTest/java/ru/medeng/dicomtool/storagefixtures/UriGrantActivity.java>), [AndroidManifest.xml](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/androidTest/AndroidManifest.xml>) — провайдер и настоящий persistable tree grant из отдельного test APK UID.
- [StorageGrantReceiverActivity.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/debug/java/ru/medeng/dicomtool/storagefixtures/StorageGrantReceiverActivity.kt>), [AndroidManifest.xml](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/debug/AndroidManifest.xml>) — неэкспортированная debug-only Activity для получения test URI.
- [build.gradle.kts](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/build.gradle.kts>), [libs.versions.toml](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/gradle/libs.versions.toml>) — Android test runner/dependencies, opt-in изолированный emulator application ID.
- Этот документ — результаты и ограничения этапа.

Тестовый provider и GrantActivity существуют только в androidTest APK; receiver отсутствует в release. Нормальные debug/release application IDs не меняются. Для тестов на устройстве нужен `-Pstage51Isolated=true`: тогда debug package получает суффикс `.storagesafetytest`. Test setup проверяет этот суффикс до работы с файлами. Использованы исключительно созданные тестами синтетические DICOM, реальные исследования не открывались.

**Новые JVM-тесты**

`StorageSafetyTest`:

1. sameLengthDifferentPixelDataPreservedInSafAlgorithm.
2. trueDuplicateComparedByBytesAndNotWrittenAgain.
3. truncatedDestinationNeverReturnsSuccessOrDeletesSource.
4. copyExceptionPreservesSourceAndExistingOriginal.
5. delayedReadVisibilityIsVerifiedByReadingAgain.
6. internalMigrationCollisionDoesNotOverwriteOriginal.
7. interruptedAfterCommitCanResumeWithoutLosingOrDuplicatingData.
8. interruptedThreadCannotDeleteSource.
9. unsafeUidsAndPathsRejectedWithoutChangingSource.
10. parallelSameUidDifferentObjectsAllSurvive.
11. independentSopsCanCopyAtTheSameTime.
12. cleanupKeepsUnknownKeepTxtAndLegacyDicomAndNeverDeletesDirectories.
13. changedOwnedDocumentIsNotDeleted.
14. identicalUnknownDocumentIsNotClaimedForCleanup.
15. ledgerPersistenceFailureNeverDeletesIncomingSource.
16. ownershipReloadFromDiskAllowsOnlyRecordedDocumentsToBeCleaned.
17. corruptOwnershipLedgerFailsClosed.
18. privateRepositoryUsesSafeSaveAndCleanupKeepsNonDicom (также сохранность .part и его метрики).
19. retainedSourceCanBeRetriedWithoutExtraCopiesOrDataLoss.

`DicomScpStage2IntegrationTest`:

- unsafeStudyUidReturnsFailedCStoreAndKeepsCompleteSyntheticPayload.
- publicationFailureDoesNotReturnSuccessfulCStoreOrDestroyPayload.

Оба интеграционных теста создают настоящий TCP association, отправляют синтетический C-STORE и проверяют status 0x0110, сохранность complete incoming .part и отсутствие перезаписи защищённого destination.

**Android Emulator**

Pixel Tablet, emulator-5554, Android 15 / API 35. Выполнены 10 tests:

- sameSizeDifferentPixelsAndTrueDuplicates.
- zeroProviderSizeDoesNotPreventReadbackVerification.
- shortFinalWriteRetainsSourceAndReturnsFailure.
- providerWriteExceptionRetainsSource.
- migrationBothWaysPreservesCollidingOriginalAndCanResume.
- cleanupKeepsKeepTxtUnknownDicomAndUnknownDirectories.
- concurrentSameUidDoesNotLoseDifferentObjects.
- oppositeMigrationsCannotDeleteBothIdenticalCopies.
- repeatMigrationAfterSourceDeleteDeniedUsesVerifiedCopy.
- retainedIncomingSurvivesStudyCleanupAndIsReported.

Тесты получают настоящий read/write/persistable tree URI grant между двумя package/UID и вызывают production DicomStorageManager через ContentResolver. Использованы обычные файлы, reliable pipe с молчаливой неполной записью, ошибочный SIZE=0 и исключение ENOSPC провайдера.

Первые прогоны обнаружили ошибки нового тестового стенда и SAF-адаптера. Они устранены: standalone test provider переведён на Java; дочерние директории обрабатываются через DocumentsContract, а не SingleDocumentFile. Финальный прогон: **10/10 PASS, 0 failures/errors/skipped**.

**Финальные проверки**

Среда: Windows, JDK 21.0.8, SDK 35 / Build Tools 36.0.0.

| Команда | Результат |
|---|---|
| `gradlew.bat testDebugUnitTest` | PASS: 97 tests, 0 failures, 0 errors, 0 skipped |
| `gradlew.bat assembleDebug` | PASS: обычный debug APK собран |
| `gradlew.bat lintDebug` | FAIL: 1 error, 49 warnings |
| `gradlew.bat connectedDebugAndroidTest -Pstage51Isolated=true` | PASS: 10 Android tests на API 35 |
| `git diff --check` | PASS |

Последний запуск первых трёх задач выполнен одной командой `testDebugUnitTest assembleDebug lintDebug`. Первые две задачи завершились успешно; общий Gradle exit code 1 вызван lintDebug. Lint результат не скрыт: baseline, suppression и изменение severity не добавлялись.

**Поправка к исходному аудиту:** в нём ошибочно указано 86 тестов. Сумма исходных 10 JUnit XML suites — **76**, а не 86. Добавлен 21 JVM test; финальная сумма 97. Эта поправка не меняет выводы аудита о дефектах.

Отчёты: [index.html](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/build/reports/tests/testDebugUnitTest/index.html>), [index.html](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/build/reports/androidTests/connected/debug/index.html>), [lint-results-debug.html](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/build/reports/lint-results-debug.html>). Build outputs могут быть пересозданы следующим запуском Gradle.

**Ранее существовавшая ошибка lint**

[DicomToolNavigation.kt](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/app/src/main/java/ru/medeng/dicomtool/ui/navigation/DicomToolNavigation.kt:244>): `UnusedBoxWithConstraintsScope`, «BoxWithConstraints scope is not used». Ошибка подтверждена и исходным lint XML аудированного snapshot. Она находится вне этапа хранения и не исправлялась.

Прочие прежние warning categories: AndroidGradlePluginVersion, OldTargetApi, GradleDependency, DiscouragedApi (Timer), ModifierParameter, ObsoleteSdkInt, StaticFieldLeak, IconLauncherShape. Новые warnings — наличие новых версий **тестовых** runner 1.6.2 и ext:junit 1.2.1 (по три сообщения для каждого из-за lint models). Нет новых lint errors в storage коде. Выбранные версии test dependencies проверены реальной компиляцией и Android-прогоном; версия production Kotlin/AGP не менялась. Официальный список: [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test).

Build также предупреждает о старом AGP для compileSdk 35 и версии SDK XML; это ранее существовавшие особенности среды/конфигурации, не новый storage failure.

**Сохранение и восстановление после прерывания**

- Данные копируются в уникальный stage; его содержимое проверяется.
- Internal publish не использует REPLACE_EXISTING для DICOM. SAF не предполагает атомарный rename: final document создаётся отдельно и проверяется повторным чтением.
- Ownership ledger хранится приватно, записывается через временный файл с fsync и заменой только собственного metadata-файла. Данные DICOM в нём не меняются.
- Source удаляется после проверки destination и успешного commit ledger. Если процесс прерван раньше, source остаётся; возможно, дополнительно остаются stage/final documents.
- Повторная миграция распознаёт уже полную идентичную копию по fingerprint, включая duplicate names, и завершает перенос без повторной записи.
- Если прерывание случилось после commit, но до удаления source, verified destination сохраняется и не откатывается при ошибке удаления.
- Изменившийся source/destination не удаляется автоматически. Повреждённый/утраченный ownership ledger не даёт права удалять неизвестные SAF documents.
- Полностью полученные failed C-STORE .part остаются в private .incoming_tmp для последующего разбора/восстановления; автоматическая повторная отправка или изменение таких файлов не добавлялись.

Проверены искусственное прерывание перед удалением source и загрузка ledger новым экземпляром из диска. Настоящий kill процесса во время записи и потеря питания не моделировались.

**Известные ограничения**

1. Старые SAF-файлы без реестра не считаются принадлежащими приложению. Они могут отображаться существующим reader, но cleanup и destructive SAF→internal migration их не удаляют/не переносят. Автоматическое «усыновление» по имени/UID запрещено.
2. SAF-директории намеренно остаются, даже если кажутся пустыми. Provider может иметь скрытые документы или независимого внешнего писателя.
3. Проверка подтверждает bytes, доступные через DocumentsProvider после close. API не гарантирует физическую/облачную durability после потери питания; remote upload commit не проверяется. Для некоторых providers с долгой задержкой видимости операция вернёт Failure и сохранит source, вместо ложного Success.
4. При crash могут остаться .part или ещё не зарегистрированные документы. Они не удаляются эвристически; завершённый source позволяет безопасно повторить миграцию. Автоматического фонового сборщика таких объектов в этом этапе нет.
5. Registry рассчитан на текущий один процесс приложения и приватный internal root. Координация не является распределённым замком против произвольного другого приложения/изменяющего содержимое provider.
6. SHA-256/readback добавляют I/O. Независимые data copies идут параллельно; directory creation и durable registry commits требуют короткой общей синхронизации. Очистка исключает receive publication, противоположные миграции сериализованы.
7. Проверен один custom provider на одном API 35 emulator. Не проверены реальные cloud/SD providers, извлечение SD-карты, все Android API и аппаратные power-loss сценарии.
8. Остальные дефекты аудита — SCU Deflated, Viewer, журнал/PHI export, lifecycle и прочие — не объявляются устранёнными. Работа ограничена этапом 5.1.


**Дополнительная проверка перед коммитом**

`finish()` возвращает Success после проверки целевого файла и источника, даже если remove() вернул false. Теперь результат содержит явный sourceRetained и понятное русское предупреждение. Миграция учитывает такие операции как незавершённый перенос, отдельно показывает число сохранённых источников; проверенные копии остаются на месте. JVM-тест повторяет отказ удаления трижды, затем успешное удаление: единственный destination и исходные байты сохраняются. Android-тест запрещает удаление через chmod каталога исходника, выполняет две миграции, проверяет единственный SAF document и идентичность обеих копий, затем восстанавливает права и успешно завершает перенос.

Новый RetainedIncoming.kt считает количество и объём непосредственных .incoming_tmp/*.part без чтения DICOM и обхода ссылок. ViewModel обновляет показатели в Dispatchers.IO каждые пять секунд, пока живёт ViewModel, и при обновлении информации хранилища. SettingsAndCleanupScreen.kt и ReceiverScpScreen.kt показывают предупреждение; диалог обычной очистки прямо указывает, что эти файлы не удаляются. JVM и Android тесты проверяют сохранность синтетического .part после очистки и его метрики. Во время активного приёма счётчик может включать ещё записываемые payloads. Автоматическое удаление или восстановление не добавлено; уведомления вне интерфейса приложения отсутствуют. Исключение при удалении, в отличие от false, сохраняет прежний Failure с сохранением проверенной копии; тест прерывания подтверждает безопасный повтор.

**Diff для ревью**

[STAGE_5_1_STORAGE_SAFETY.patch](<C:/Users/Eskimos/Documents/ChatGPT/Dicom Tool/STAGE_5_1_STORAGE_SAFETY.patch>) содержит изменённые и новые исходники, tests, build setup и этот отчёт относительно origin/main. Изменения также доступны в локальном review panel. Исходный независимый аудит и evidence ZIP не включены в diff.

**Push/merge и переход к следующему этапу не выполнялись.**
