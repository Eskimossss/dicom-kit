# Этап 6 — рабочий протокол

База ef22644, ветка fix/stage-5-1-storage-safety. Реальные testdicom и пользовательские исследования не используются. Все изменения локальные; main/GitHub/Antigravity не затрагиваются. Версия и signing сохраняются.

## Задача 1

Добавлены структурированные приватные сведения о пациентах: PN и ключ различения по PatientID/Issuer (hash не считается анонимизацией). PN разбирается по компонентам и альтернативным группам. Добавлена 11-я обратно совместимая колонка приватного TSV; старые записи не перечитывают архив. В кратких карточках вместо дублирующего результата — пациенты, раскрытие списка и поиск. Закрытая проекция безопасного экспорта не включает новые сведения.

Проверены Stage6JournalPatientsTest и JournalSecurityReliabilityTest: {'tests': 29, 'failures': 0, 'errors': 0, 'skipped': 0}; компиляция PASS. Лог app/build/stage6/task1-tests.log.

## Задача 2

Сохранение, DIMSE Status и запись RSP теперь отдельные структурированные результаты. Поддержаны partial success и предупреждения Bxxx. Отсутствие отдельного ACK не считается ошибкой; реальный сбой записи RSP виден отдельно, подтверждённое хранение не отрицается. Для старых данных неизвестное хранение не выдумывается. Протокольная передача RQ/RSP не подменялась. Проверены Stage6ScpOutcomesTest, ScpLifecycleStage55Test (реальные loopback-сокеты, ECHO/STORE/release/abort), DicomJournalStage432Test: {'tests': 32, 'failures': 0, 'errors': 0, 'skipped': 0}. Лог app/build/stage6/task2-tests.log.

## Задача 3

Диагностика архива хранит source URI/path, имя, стадию и категоризированную причину без stack trace. Полное обновление пересчитывает текущие ошибки, incremental read удаляет успешно обработанную проблему. Идентичные basename не смешиваются, кодировки не считаются ошибками чтения. Плашка и раскрытие только в Принятых; доступны повторное чтение и безопасное закрытие. Исходники не исправляются. Проверки Stage6ArchiveDiagnosticsTest и ArchiveStage56Test: {'tests': 7, 'failures': 0, 'errors': 0, 'skipped': 0}. Лог app/build/stage6/task3-tests.log.

## Задача 4

Общий DicomViewerWorkspace: две колонки на широком экране, адаптивная компоновка на телефоне; метаданные, WW/WL и теги. UI presets убраны, исходная LUT/window логика сохранена. Навигация кадр/экземпляр разделена, включая оба уровня одновременно. Сброс WW/WL сохраняет инверсию/zoom/pan/кадр; общий сброс вида обозначен отдельно. Перерисовка использует декодированный кадр и одну последнюю ожидающую настройку, без очереди устаревших работ. Декодер не переписан.

JVM ViewerStage54Test + Stage21ViewerAndCharsetTest PASS (task4-jvm.log). API35: ViewerSafetyInstrumentedTest 12/12 PASS, включая живой drag 4000×3000, точный конечный pixel, отсутствие повторного чтения, SHA-256 источника и сброс окна; отдельно навигация двух уровней 1/1 PASS (task4-navigation-recheck.log). Первый вариант теста менял wm size, что пересоздавало Activity и теряло установленный тестом content; заменён проверкой ширины Compose-контейнера. При удалении неиспользуемых imports восстановлены неявные getValue/setValue; окончательная компиляция PASS. Полная регрессия после задач 5–6 ещё предстоит.

## Задача 5

Добавлено presentation-only закрытие плашек: C-ECHO/C-STORE, кодировки, чтение архива/внешнего файла, SCP/хранилище, статус миграции и состояние журнала. Новые результаты SCU имеют отдельный event ID, даже при совпадении текста; закрытие сохраняется в Compose SaveableState при переходах и пересоздании Activity. Success автоматически скрывается через 6 секунд, warning/error — только вручную. Диагностика архива, теги, результаты файлов, журнал и восстановление доступны после закрытия. Устаревший итог пакета больше не определяет цвет последующего C-ECHO.

API35: Stage6NoticesInstrumentedTest 2/2 PASS (таймер, manual dismiss, новый одноимённый event, реальный C-ECHO, переходы и recreation); ViewerSafety closingCharsetNotice... 1/1 PASS (метаданные, теги и исходные байты сохранены). JVM Stage6*, JournalSecurityReliabilityTest, ArchiveStage56Test: 40/40 PASS, 0 skipped. Первый JVM-прогон выявил влияние presentation UUID на структурное равенство архива; UUID перенесён из data constructor, исходный тест равенства incremental/full index сохранён без ослабления. Логи task5-android.log, task5-charset-notice.log, task5-jvm-final.log.

## Задача 6

Добавлен сохраняемый выбор UTF-8 (default ISO_IR 192), ISO-8859-5 (ISO_IR 144), строгого ASCII (без тега), экспериментального UTF-8 без тега и CP1251 без фиктивного ISO_IR 144. Строгий CharsetEncoder REPORT проверяет PN/PatientID/StudyDescription/SeriesDescription; несовместимый объект не подготавливается и не отправляется, старый preview/UID не выдаётся за новый. UI позволяет исправить поля и продолжить. Кодировка не применяется к пересылке оригиналов и не меняет Pixel Data.

53 целевых JVM-теста: 52 PASS, 1 SKIP (Orthanc ещё не запущен; финальная интеграция предстоит). Stage6SyntheticEncodingTest проверяет actual Part10 bytes независимым explicit-VR LE reader, 0008,0005, PN, кириллицу, ASCII rejection, invalid Unicode, CP1251, Pixel Data, UID/retry. Реальный loopback SCU: два C-STORE каждого из пяти режимов, захват фактического encoded Data Set и сохранность подготовленного объекта. Первоначальный тест выявил мутацию dcm4che Attributes getter-ами при отображении PN/PatientID; сведения для журнала/сообщений теперь читаются из snapshot, wire/retry regression PASS. API35 selector/ASCII/отсутствие stale object/сохранение настройки/recreation 1/1 PASS. Независимый pydicom 3.0.2 (только ignored build tools) прочитал 3 стандартных варианта с точным PN и charset; SHA256 Pixel Data одинаковы. Логи task6-wire-initial.log (найденная ошибка), task6-wire-final.log, task6-android.log, encoding-independent.json. Полная финальная проверка ещё предстоит.

## Итоговая регрессия — найденные и исправляемые отклонения

Первый полный JVM-прогон: 228/228 PASS, 0 skipped, отдельный Orthanc (порт и PID в ignored stage6/orthanc-process.json). Три реально сохранённых объекта independently прочитаны pydicom: Implicit/Explicit/Deflated, точный synthetic PN и Pixel Data. Первый полный Android-прогон: 49 тестов, 48 PASS / 1 FAIL — compactLoggerWarningOpensTechnicalDetails: после замены Surface потерялся clickable handler. Обработчик восстановлен; существующий тест дополнен доступом к подробностям после X. Добавлены проверка управляющих символов метаданных и bounded presentation-state (256 hashed IDs) против роста saved instance state. Подготовлены synthetic wide/narrow screenshots в тесте; их локальное получение/осмотр ещё предстоит. Финальный повтор JVM/Android/build/lint должен подтвердить окончательный код; прежние PASS не считаются доказательством новых изменений.

Повтор 49/49 API35 PASS (final-android-recheck.log) подтвердил исправление журнала. Затем усилена проверка 4000×3000: исходный ненасыщенный pixel, живые WW и WL, точный final pixel и read-open count; исправлено уменьшение slider range при drag. По осмотру synthetic screenshots toolbar заменён FlowRow, чтобы не обрезать горизонтально кнопки на телефоне. Две целевые instrumentation-проверки latest UI/large drag PASS (прямой am instrument без очистки app data); screenshots сохранены и просмотрены в app/build/stage6/screenshots. После этих последних изменений запущен новый полный прогон (final-android-verified.log); завершение ещё ожидается.
