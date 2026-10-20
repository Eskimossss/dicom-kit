# DICOM Kit — Результат этапа 7: Modality Worklist (MWL SCP + SCU)

> Обновление alpha.2: исходные заявления и результаты этого документа относятся к первоначальному этапу. Фактические исправления, регрессии и ограничения описаны в [STAGE_7_AUDIT_FIXES.md](STAGE_7_AUDIT_FIXES.md); актуальное поведение — в [MWL_DICOM_CONFORMANCE.md](MWL_DICOM_CONFORMANCE.md).
**Дата:** 20.10.2026  
**Ветка:** `feature/stage-7-worklist-mwl`  
**Базовый коммит:** `61dc8ee5a1f8b1bf2c1d28f88ddadb8030ce2648` (merge PR #1 в `origin/main`)  
**Протокол:** DICOM DIMSE C-FIND, SOP Class UID `1.2.840.10008.5.1.4.31` (Modality Worklist Information Model - FIND)  
**Статус сборки и тестов:**
- Unit-тесты: `255/255 PASS` (`BUILD SUCCESSFUL in 28s`)
- DCMTK Interoperability: `PASS` (проверено эталонной утилитой `C:\DCMTK\bin\findscu.exe v3.7.0`)
- Debug APK: `app-debug.apk` (18 880 141 байт, собран без ошибок)

---

## 1. Архитектурный обзор

В соответствии с техническим заданием в DICOM Kit разработан полноценный раздел **Worklist**, предназначенный для сервисных инженеров медицинского оборудования (рентген, маммография, флюорография, КТ, МРТ, УЗИ).

Реализованы два полностью автономных режима:
1. **MWL SCP (сервер назначений):**
   - Запускается на Android-устройстве сервисного инженера в качестве foreground-сервиса (`MwlScpForegroundService`).
   - Принимает входящие запросы DIMSE C-FIND от диагностических аппаратов.
   - Выполняет поиск по базе локальных пациентов и назначенных процедур согласно DICOM PS3.4 Annex K (Single Value, Wildcard `*`/`?`, Universal, DA/TM Range Matching, Sequence Matching внутри `(0040,0100)`).
   - Поддерживает C-CANCEL-FIND и корректный возврат статусов (Pending `0xFF00`, Success `0x0000`, Cancelled `0xFE00`).
   - Настраиваемый Local AE Title, TCP-порт (по умолчанию 11112), белый список Calling AE Title, выбор кодировки (`ISO_IR 100`, `ISO_IR 144`, `ISO_IR 192`, `GB18030` и др.).
   - Независим от Storage SCP (нет конфликтов портов, сессий или состояний).

2. **MWL SCU (клиент Worklist):**
   - Позволяет сервис-инженеру подключаться к удалённым MWL SCP (RIS, PACS, медицинские информационные системы).
   - Поддерживает профили удалённых серверов (Host, Port, Called AET, Calling AET, таймауты).
   - Гибкие фильтры C-FIND: по модальности, диапазону дат (любая дата, сегодня, завтра, вчера, произвольный диапазон), Patient ID, Patient Name (с подстановочными знаками `*`), Accession Number, Scheduled Station AE Title.
   - Возможность отмены активного запроса на лету (C-CANCEL).
   - Клиническое и детальное техническое представление результатов: дерево DICOM-тегов, вложенные последовательности SPS `(0040,0100)`, декодирование кодировок, копирование значений.

3. **Локальная база пациентов и назначений:**
   - Чёткое разделение сущностей: Пациент (`MwlPatient`) и Назначенное исследование (`MwlScheduledStudy`).
   - Связь один-ко-многим: один пациент может содержать несколько назначений.
   - Строгая валидация DICOM VR (PN, DA, TM, UI, SH, LO, CS, AE).
   - Стабильные UID: автоматическая генерация уникальных Study Instance UID и SPS ID без их изменения при повторных C-FIND ответах.
   - Быстрый генератор синтетических назначений по модальностям (CR/DX, MG, DX/CR флюорография, CT, US) с явной маркировкой `isSynthetic = true` для предотвращения утечки персональных данных (PHI).
   - Атомарное дисковое хранилище JSON (`.tmp` + `sync` + `renameTo`).
   - Экспорт и импорт синтетических назначений для переноса тестовых сценариев между устройствами.

4. **Диагностический технический журнал:**
   - Фиксация всех сетевых и DIMSE событий (A-ASSOCIATE-RQ/AC/RJ, C-FIND-RQ, C-FIND-RSP Pending/Success/Cancel, A-RELEASE, A-ABORT, тайм-ауты).
   - Автоматическая маркировка сессий `MWL SCP-*` и `MWL SCU-*`.
   - Полная инспекция каждого события: Remote Socket, AE Titles, PDU, Transfer Syntaxes, Requested Return Keys.
   - Экспорт диагностического отчёта с безопасным маскированием PHI по умолчанию.

---

## 2. Структура модулей и файлов

Все новые компоненты размещены в изолированном пакете `ru.medeng.dicomtool.mwl`:

```
app/src/main/java/ru/medeng/dicomtool/mwl/
├── MwlRuntimeRegistry.kt              // Синглтон-реестр зависимостей и настроек
├── model/
│   ├── MwlEntities.kt                 // Доменные модели: MwlPatient, MwlScheduledStudy
│   ├── MwlValidation.kt               // Строгая валидация VR и генераторы UID
│   ├── SyntheticMwlGenerator.kt       // Шаблоны синтетических назначений (CR, MG, CT, US, DX)
│   └── MwlScuModels.kt                // Модели профилей SCU, фильтров и результатов запроса
├── repo/
│   ├── MwlJsonUtils.kt                // Чистый Kotlin JSON сериализатор/парсер (без Android stubs)
│   ├── MwlRepository.kt               // Интерфейс репозитория пациентов и исследований
│   ├── LocalMwlRepository.kt          // Thread-safe дисковый репозиторий с Flow
│   └── MwlScuProfileRepository.kt     // Хранилище профилей внешних MWL SCP
├── engine/
│   ├── MwlMatchingEngine.kt           // DICOM PS3.4 Annex K движок сопоставления C-FIND
│   └── MwlDatasetBuilder.kt           // Построитель C-FIND-RQ Identifier и C-FIND-RSP Dataset с SPS Seq
├── scp/
│   ├── MwlScpServer.kt                // Сервер MWL на dcm4che (FIND-SCP + Verification-SCP)
│   └── MwlScpForegroundService.kt     // Android Foreground Service с Notification и WakeLock
├── scu/
│   ├── MwlScuClient.kt                // Клиент MWL SCU с поддержкой C-FIND и C-CANCEL
│   └── MwlTechnicalLogger.kt          // Структурированный технический журнал MWL в TSV
└── ui/
    ├── MwlViewModel.kt                // Единая ViewModel для 4 вкладок
    ├── WorklistMainScreen.kt          // Главный контейнер раздела Worklist с 4 вкладками
    ├── tabs/
    │   ├── MwlScpTab.kt               // Вкладка "Сервер (SCP)": статус, AET, порт, кодировка, белый список
    │   ├── MwlAppointmentsTab.kt      // Вкладка "Назначения": карточки, фильтры, CRUD, генератор, экспорт
    │   ├── MwlScuTab.kt               // Вкладка "Клиент (SCU)": профили, фильтр, C-FIND, отмена, результаты
    │   └── MwlJournalTab.kt           // Вкладка "Журнал": фильтрация, детали PDU, экспорт отчёта
    └── dialogs/
        ├── MwlPatientDialog.kt        // Диалог создания/редактирования пациента
        ├── MwlStudyDialog.kt          // Диалог создания/редактирования назначения
        ├── SyntheticGeneratorDialog.kt // Мастер создания синтетических исследований
        └── MwlScuProfileDialog.kt     // Диалог создания профиля SCU
```

---

## 3. Соответствие стандартам DICOM

1. **SOP Class:** `1.2.840.10008.5.1.4.31` (*Modality Worklist Information Model - FIND*).
2. **Transfer Syntaxes:**
   - `1.2.840.10008.1.2` (*Implicit VR Little Endian*)
   - `1.2.840.10008.1.2.1` (*Explicit VR Little Endian*)
3. **Согласование Presentation Context:**
   - SCU запрашивает Presentation Context #1 для MWL Find и #3 для Verification.
   - Корректная обработка `A-ASSOCIATE-AC` по `pcid` (учитывая, что стандартный PDU ответа не дублирует Abstract Syntax).
4. **Структура Dataset:**
   - Полная поддержка вложенной последовательности `Scheduled Procedure Step Sequence (0040,0100)`.
   - Поддержка допуска для оборудования: если модальность передана в корне набора `(0008,0060)`, сервер сопоставляет её так же, как внутри SPS Sequence.
   - Сохранение запрошенных return keys: если аппарат запросил атрибуты (например, `PatientWeight`, `MedicalAlerts`, `AdmissionID`), сервер возвращает их даже при пустом значении.
5. **Поддержка кодировок (Specific Character Set):**
   - Настраиваемый тег `(0008,0005)`: `ISO_IR 100` (Latin 1), `ISO_IR 144` (ISO Cyrillic 8859-5), `ISO_IR 192` (UTF-8), `GB18030` и др.
   - Корректное декодирование строк на SCU и корректное кодирование на SCP.

---

## 4. Результаты тестирования

### 4.1. Unit-тесты (JVM)
Запуск: `./gradlew testDebugUnitTest`  
Результат: **255 тестов пройдено, 0 ошибок**.
- `MwlDataAndValidationTest`: проверка строгой валидации VR, DICOM UID, генератора синтетических пациентов и репозитория.
- `MwlMatchingEngineTest`: 12 тестов на соответствие правилам сопоставления DICOM PS3.4 Annex K (Single value, Wildcards `*` и `?`, диапазоны дат `DA` и времени `TM`, толерантность к регистру, последовательности SPS).
- `MwlCharsetEncodingTest`: тестирование Specific Character Set (`ISO_IR 192`, `ISO_IR 144`, `ISO_IR 100`) и вложенных структур данных.
- `MwlScpAndScuDimseTest`: сквозные тесты полного цикла DIMSE C-FIND между локальным `MwlScpServer` и `MwlScuClient` через реальный TCP сокет, проверка белого списка Calling AET, освобождения портов и отмены C-CANCEL.

### 4.2. Интеграционное тестирование с DCMTK (findscu v3.7.0)
Файл: `MwlDcmtkInteroperabilityTest.kt`  
Проведено тестирование взаимодействия сервера `MwlScpServer` с официальной эталонной утилитой консорциума OFFIS DCMTK — `findscu.exe`:
```
findscu.exe -v -W -k 0010,0010 -k 0010,0020 -k 0008,0050 -k 0040,0100[0].0008,0060 127.0.0.1 <port> -aec DCMTK_MWL_SCP -aet DCMTK_SCU
```
- Exit code: `0` (Success).
- Статус ассоциации: `Association Accepted`.
- DIMSE статус: `Status: Pending (0xFF00)` для каждой записи, и `Status: Success (0x0000)` в финальном ответе.
- Фильтрация по модальности (`0040,0100[0].0008,0060=CT`): отфильтрованы записи, возвращены строго соответствующие исследования.

---

## 5. Готовность сборки

- **Сборка APK:** Выполнена успешно через `./gradlew assembleDebug`.
- **Файл:** `app/build/outputs/apk/debug/app-debug.apk`
- **Размер:** 18 880 141 байт.
- **Ветка:** `feature/stage-7-worklist-mwl` готова для последующего коммита и приёмочного тестирования.
