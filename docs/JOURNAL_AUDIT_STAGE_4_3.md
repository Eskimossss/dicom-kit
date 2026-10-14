# DICOM Kit — Аудит технического журнала (Этап 4.3)

**Дата аудита:** 13 октября 2026 г.  
**Проект:** DICOM Kit (`ru.medeng.dicomtool`)  
**Рабочая директория:** `E:\Dicomtool`  
**Цель:** Всесторонний технический аудит существующей системы журналирования DICOM-событий, анализ полноты протокольных записей, идентификации сеансов, архитектуры интерфейса и подготовка плана реализации краткого и подробного режимов журнала с локальной диагностикой ошибок.

---

## 1. Текущая архитектура журнала

### 1.1. Компоненты системы журналирования

В проекте система технического журнала состоит из следующих ключевых слоёв и классов:

1. **Модели данных (`ru.medeng.dicomtool.domain.model` в [DicomModels.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt#L326-L368)):**
   * [`DicomLogCategory`](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt#L326-L342): перечисление категорий протокола (15 значений): `TCP_CONNECT`, `TCP_DISCONNECT`, `ASSOCIATE_RQ`, `ASSOCIATE_AC`, `ASSOCIATE_RJ`, `PRESENTATION_CONTEXT`, `C_ECHO_RQ`, `C_ECHO_RSP`, `C_STORE_RQ`, `C_STORE_RSP`, `RELEASE_RQ`, `RELEASE_RP`, `ABORT`, `NETWORK_ERROR`, `SERVER_LIFECYCLE`.
   * [`DicomTrafficDirection`](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt#L347-L351): направление трафика: `INBOUND ("IN")`, `OUTBOUND ("OUT")`, `LOCAL ("SYS")`.
   * [`DicomLogEntry`](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/domain/model/DicomModels.kt#L357-L367): структура отдельной записи:
     ```kotlin
     data class DicomLogEntry(
         val id: String,                         // UUIDv4
         val timestampFormatted: String,         // "HH:mm:ss.SSS"
         val category: DicomLogCategory,
         val direction: DicomTrafficDirection = DicomTrafficDirection.INBOUND,
         val associationId: String = "-",        // e.g. "SCU-3", "ASSOC-1", "TCP-54321"
         val title: String,
         val summaryLines: List<String>,
         val technicalDetails: Map<String, String> = emptyMap(),
         val isError: Boolean = false
     )
     ```

2. **Хранилище и логгер ([PersistentDicomTechnicalLogger.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/data/logging/PersistentDicomTechnicalLogger.kt)):**
   * Реализует контракт [`DicomTechnicalLogger`](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/DicomContracts.kt#L49-L53).
   * Синглтон, управляемый реестром [`DicomRuntimeRegistry.getTechnicalLogger(context)`](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/DicomRuntimeRegistry.kt#L43-L49).
   * Файл постоянного хранения: `context.filesDir/logs/dicom_technical_log.tsv`.
   * Хранит оперативный список в `MutableStateFlow<List<DicomLogEntry>>`.
   * Сериализует записи в формат TSV (9 колонок) с разделителями `\u001F` (Unit Separator для элементов списков) и `\u001E` (Record Separator для пар ключ-значение).

3. **Источники событий (Сервисы SCP и SCU):**
   * [DicomStorageScpServer.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt): фиксирует события входящих TCP-подключений, установку/отклонение ассоциаций, согласование Presentation Context, входящие C-ECHO-RQ / C-STORE-RQ, отправку C-ECHO-RSP / C-STORE-RSP, закрытие через A-RELEASE и ошибки.
   * [DicomScuClient.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt): фиксирует события исходящих TCP-подключений, отправку A-ASSOCIATE-RQ, получение A-ASSOCIATE-AC / A-ASSOCIATE-RJ, отправку C-ECHO / C-STORE, получение ответов, тайм-ауты, A-RELEASE и аварийные прерывания.
   * [DicomScpForegroundService.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomScpForegroundService.kt): инициирует запуск и остановку SCP-сервера в фоновом режиме, косвенно генерируя события жизненного цикла.

4. **Уровень представления и UI:**
   * [DicomToolViewModel.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/viewmodel/DicomToolViewModel.kt#L233-L240): предоставляет `StateFlow<List<DicomLogEntry>>`, флаг `autoScrollLogs` и набор развёрнутых записей `expandedLogIds: StateFlow<Set<String>>`.
   * [DicomLogScreen.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt): экран 5-й вкладки «Журнал», отображающий линейный список записей в `LazyColumn`.
   * [SettingsAndCleanupScreen.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/settings/SettingsAndCleanupScreen.kt#L521-L552): диалог очистки журнала из настроек.

---

### 1.2. Ответы на ключевые вопросы архитектуры

| Вопрос | Ответ и текущая реализация | Анализ и риски |
|---|---|---|
| **1. Где создаются события?** | В коде `DicomStorageScpServer.kt` (методы мониторов dcm4che `ConnectionMonitor`, `AssociationMonitor`, `AssociationHandler`, обработчиков `BasicCEchoSCP`, `BasicCStoreSCP`) и `DicomScuClient.kt` (`performCEcho`, `performCStore`, `performCStoreBatch`, `classifyException`). | События разбросаны непосредственно внутри сетевых процедур, нет промежуточного слоя фасадных доменных событий. |
| **2. Как передаются в интерфейс?** | Через вызовы `logger.recordTechnicalEvent(...)` -> обновление `_logsFlow: MutableStateFlow` -> `viewModel.logs` (`SharingStarted.WhileSubscribed(5000)`) -> Compose `collectAsState()`. | Передача реактивная, без задержек опроса. Однако каждое добавление обновляет весь список целиком (`_logsFlow.value = (_logsFlow.value + entry).takeLast(2000)`), создавая копирование списка на каждое событие. |
| **3. Где и как сохраняются?** | Файл во внутренней памяти: `filesDir/logs/dicom_technical_log.tsv`. Запись в файл производится методом `appendEntryToDisk()` при каждом вызове `recordEvent()`. | Персистентность работает, но реализация ввода-вывода блокирующая (см. пункт 6). |
| **4. Сохраняются ли после перезапуска?** | **Да.** При старте `PersistentDicomTechnicalLogger` вызывает `loadFromDisk()`, который парсит TSV-файл и восстанавливает последние 2000 записей. | Записи сохраняются, но сессионные ID при перезапуске обнуляются и начинают конфликтовать со старыми записями (см. Раздел 3). |
| **5. Есть ли ограничения на количество записей?** | В оперативной памяти установлен лимит: `maxEntries = 2000` (`takeLast(2000)`). **На диске лимита нет!** Метод `appendEntryToDisk()` только дописывает строки (`appendText`). Файл на диске растёт бесконечно до вызова «Очистить журнал». | **Уязвимость:** при активном приёме тысяч снимков TSV-файл может вырасти до десятков/сотен мегабайт. При очередном перезапуске метод `loadFromDisk()` попытается вычитать весь файл целиком в память, вызвав OOM или зависание приложения при старте. |
| **6. Не блокирует ли журналирование сетевые операции?** | **Да, блокирует!** В `PersistentDicomTechnicalLogger.recordEvent()` синхронно захватывается `ReentrantLock` и внутри критической секции вызывается `logStorageFile.appendText(...)`, открывающий и синхронизирующий файл на диске. | Это происходит прямо в потоках сетевого пула `dcm4che` при обработке пакетов `store()` и `onDimseRQ()`. При медленном флеш-накопителе или нагрузке на диск ввод-вывод журнала задерживает отправку подтверждений `C-STORE-RSP` и обработку сетевых PDU. |
| **7. Какие данные доступны, но не показываются?** | В `technicalDetails` передаются: `StudyInstanceUID`, `SeriesInstanceUID`, `SOPInstanceUID`, `TransferSyntaxUID`, `AffectedSOPClassUID`, `Calling/Called AE`, сетевые IP и порты, PDU hex-коды, `MessageID`, время выполнения в миллисекундах, размеры переданных байтов. | В свёрнутом виде пользователю виден только короткий заголовок `title` и 1-2 строки `summaryLines`. Полные технические данные доступны только при ручном раскрытии каждой отдельной записи. Нет агрегации по исследованию, нет поиска по UID или имени файла. |

---

## 2. Перечень и полнота DICOM-событий

Ниже представлена детальная матрица протокольных событий, реально фиксируемых приложением DICOM Kit:

### 2.1. Ассоциации (DICOM Upper Layer, PS3.8)

| Событие | Реализовано в SCP | Реализовано в SCU | Сохраняемые поля | Теряемые сведения | Файл и строки |
|---|:---:|:---:|---|---|---|
| **A-ASSOCIATE-RQ** | Да | Да | Calling/Called AE Title, Remote Socket (`IP:port`), число Presentation Contexts, Max PDU Length, Implementation Class UID / Version. | В SCP: перечень конкретных предложенных Presentation Contexts и Transfer Syntaxes в момент RQ не логируется (логируется только общее их число; конкретные контексты логируются только постфактум при формировании AC). | [DicomStorageScpServer.kt#L601-L623](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L601-L623)<br>[DicomScuClient.kt#L652-L670](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L652-L670) |
| **A-ASSOCIATE-AC** | Да | Да | Calling/Called AE, Max PDU Length, Implementation Class UID, Implementation Version. | — | [DicomStorageScpServer.kt#L625-L641](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L625-L641)<br>[DicomScuClient.kt#L676-L693](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L676-L693) |
| **A-ASSOCIATE-RJ** | Да | Да | Result (permanent/transient), Source (user/provider/presentation), Reason (код и русская расшифровка), Calling/Called AE, Remote Socket. | — (сохраняется полная триада Result/Source/Reason по стандарту PS3.8). | [DicomStorageScpServer.kt#L563-L583](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L563-L583)<br>[DicomScuClient.kt#L241-L273](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L241-L273) |
| **A-RELEASE-RQ** | Да | Да | Calling AE, Remote Socket, PDU 05H. | В SCU фиксируется время запроса, но не вычисляется суммарное время удержания ассоциации. | [DicomStorageScpServer.kt#L741-L752](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L741-L752)<br>[DicomScuClient.kt#L1816-L1827](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1816-L1827) |
| **A-RELEASE-RP** | Да | Да | Remote Socket, PDU 06H, факт освобождения TCP-сокета. | — | [DicomStorageScpServer.kt#L753-L764](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L753-L764)<br>[DicomScuClient.kt#L1828-L1840](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1828-L1840) |
| **A-ABORT** | Да | Да | В SCU: Source (user/provider), Reason (код и текстовое описание), сообщение исключения. В SCP: только сообщение исключения. | В SCP: коды Source и Reason из объекта `AAbort` не парсятся (сохраняется только строковое представление исключения). | [DicomStorageScpServer.kt#L691-L707](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L691-L707)<br>[DicomScuClient.kt#L296-L320](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L296-L320) |

---

### 2.2. DIMSE операции (PS3.7)

| Событие | Реализовано в SCP | Реализовано в SCU | Сохраняемые поля | Теряемые сведения | Файл и строки |
|---|:---:|:---:|---|---|---|
| **C-ECHO-RQ** | Да | Да | MessageID, Presentation Context ID, Affected SOP Class UID, Transfer Syntax UID, Calling/Called AE. | — | [DicomStorageScpServer.kt#L796-L813](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L796-L813)<br>[DicomScuClient.kt#L743-L758](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L743-L758) |
| **C-ECHO-RSP** | Да | Да | MessageIDBeingRespondedTo, DICOM Status (`0x0000 Success` / Error), длительность выполнения с точностью до миллисекунд/микросекунд. | — | [DicomStorageScpServer.kt#L818-L834](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L818-L834)<br>[DicomScuClient.kt#L799-L819](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L799-L819) |
| **C-STORE-RQ** | Да | Да | MessageID, Presentation Context ID (в SCP), Affected SOP Class UID, Affected SOP Instance UID, Study UID / Series UID (в SCU), Transfer Syntax UID, Calling/Called AE, имя файла и размер (в SCU). | В SCU: Presentation Context ID опущен в `technicalDetails` для C-STORE-RQ (сохранены только UID). | [DicomStorageScpServer.kt#L867-L887](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L867-L887)<br>[DicomScuClient.kt#L1587-L1610](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1587-L1610) |
| **C-STORE-RSP** | Да | Да | MessageIDBeingRespondedTo, DICOM Status Hex (`0x0000`, `0xB000`, `0x0110`, `0xA700`), расшифровка статуса, длительность передачи, SOP Class UID, SOP Instance UID, Study/Series UID (в SCP), сохранённый путь на диске и действие при дубликатах (в SCP), имя файла (в SCU). | Error Comment тег (0000,0902) считывается, но если он пуст, расшифровка строится только по стандарту PS3.7. | [DicomStorageScpServer.kt#L952-L1002](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L952-L1002)<br>[DicomScuClient.kt#L1678-L1700](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1678-L1700) |
| **Коды ответов DIMSE** | Да | Да | `0x0000` (Success), `0xB000..0xBFFF` (Warnings), `0x0110` (Processing Failure), `0xA700` (Out of Resources), `0xA900` (Data Set does not match SOP Class), `0xC000` (Cannot Understand). | Все стандартные диапазоны классифицируются функцией `evaluateDimseStatus()`. | [DicomScuClient.kt#L172-L225](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L172-L225) |
| **Presentation Context** | Да | Да | PC ID, Abstract Syntax UID, Proposed Transfer Syntaxes, Accepted Transfer Syntax, Negotiation Result (`0 - Acceptance`, `1 - User Rejection`, `3 - Abstract Syntax Not Supported`, `4 - Transfer Syntaxes Not Supported`). | — | [DicomStorageScpServer.kt#L644-L677](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L644-L677)<br>[DicomScuClient.kt#L701-L723](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L701-L723)<br>[DicomScuClient.kt#L1523-L1551](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1523-L1551) |

---

### 2.3. Передача файлов и сетевые ошибки

| Событие / Ситуация | Фиксируется | Поля в журнале | Что теряется или искажается | Файл и строки |
|---|:---:|---|---|---|
| **Начало отправки файла** | Да | Заголовок `[SCU] Отправка C-STORE-RQ (X/N: file.dcm)`, размер в КБ/байтах, SOP Class UID, SOP Instance UID, модальность. | — | [DicomScuClient.kt#L1587-L1610](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1587-L1610) |
| **Размер файла** | Да | Байт и форматированный размер (КБ / МБ). | — | [DicomScuClient.kt#L1602](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1602)<br>[DicomStorageScpServer.kt#L972](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L972) |
| **Результат передачи файла** | Да | Статус DIMSE, длительность C-STORE в мс, имя файла, подтверждённый SOP Instance UID. | — | [DicomScuClient.kt#L1678-L1716](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1678-L1716) |
| **Ошибки чтения/декодирования файла** | Да | Исключение ввода-вывода, классификация ошибки, имя проблемного файла. | — | [DicomScuClient.kt#L1717-L1739](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1717-L1739) |
| **Отсутствие ответа C-STORE-RSP** | Да | Чётко фиксируется `[SCU] Не получен C-STORE-RSP для file.dcm` с флагом `isError = true`. | Приложение **не** симулирует ошибку PACS, а фиксирует факт неполучения ответа. | [DicomScuClient.kt#L1660-L1671](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1660-L1671) |
| **Сетевые тайм-ауты (Connect/Socket Timeout)** | Да | Категория `TCP_TIMEOUT`, `SocketTimeoutException` или `ConnectException (timed out)`, удалённый узел `host:port`. | — | [DicomScuClient.kt#L342-L379](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L342-L379) |
| **Connection Refused (порт закрыт/сервер выключен)** | Да | Категория `TCP_CONNECTION_REFUSED`, порт, хост, явное объяснение: «узел отклонил подключение». | — | [DicomScuClient.kt#L380-L396](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L380-L396) |
| **Неизвестный хост (DNS)** | Да | Категория `DNS_RESOLUTION_ERROR`, имя хоста, `UnknownHostException`. | — | [DicomScuClient.kt#L323-L339](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L323-L339) |
| **Несовпадение AE Title** | Да | В SCP: `AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED`. В SCU: `DicomValidationUtils` перед отправкой + фиксация `A-ASSOCIATE-RJ` от PACS. | — | [DicomStorageScpServer.kt#L716-L726](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L716-L726)<br>[DicomScuClient.kt#L541-L569](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L541-L569) |
| **Несогласованный Presentation Context** | Да | Категория `PRESENTATION_CONTEXT_REJECTED`, причина отказа, несовместимый SOP Class / Transfer Syntax. | **Баг в батче SCU:** если в батче файлов контекст был отклонён, для отдельных файлов прогресс ставится `FAILED`, но в лог отдельная запись на каждый файл не пишется (только общий отказ PC). | [DicomScuClient.kt#L1572-L1578](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1572-L1578) |
| **Итог отправки очереди файлов** | Да | Фиксируется общее число файлов, успешно/предупреждений/ошибок, отменено. | **Ошибочная классификация:** событие логируется с категорией `C_STORE_RSP` вместо сводной/системной категории (строка 1778). | [DicomScuClient.kt#L1777-L1796](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1777-L1796) |

---

## 3. Анализ идентификации сеансов

### 3.1. Как формируются идентификаторы сеансов

1. **В режиме SCU ([DicomScuClient.kt#L161](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L161)):**
   * Используется статический счётчик `private val assocCounter = AtomicInteger(0)`.
   * При каждой новой операции или батче вызывается `val assocId = "SCU-${assocCounter.incrementAndGet()}"`.
   * Результат: `SCU-1`, `SCU-2`, `SCU-3`...
   * Все события одного сеанса (TCP connect, A-ASSOCIATE-RQ, AC, PC, C-STORE, RELEASE, TCP disconnect) помечаются этим общим `assocId`.

2. **В режиме SCP ([DicomStorageScpServer.kt#L1079-L1081](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L1079-L1081)):**
   * Для ассоциаций используется внутренний серийный номер объекта `Association` библиотеки dcm4che: `"ASSOC-${asAssoc.serialNo}"` (например, `ASSOC-1`, `ASSOC-2`).
   * Для событий до установления ассоциации (приём TCP сокета) используется `"TCP-$remotePort"` (например, `TCP-49152`).
   * Для сетевых сбоев сокета — `"TCP"`.
   * Для событий запуска/остановки сервера — `"SCP"`.

### 3.2. Уникальность и повторяемость сеансов

| Вопрос | Результат анализа | Обоснование в кодовой базе |
|---|---|---|
| **Уникальны ли идентификаторы в пределах работы приложения?** | **Да, в пределах одного запуска.** | И `assocCounter` в SCU, и `asAssoc.serialNo` в SCP монотонно инкрементируются. |
| **Могут ли повторяться после перезапуска приложения?** | **ДА, ОБЯЗАТЕЛЬНО ПОВТОРЯЮТСЯ!** | `assocCounter` в SCU сбрасывается в `0` при старте процесса. Счётчик ассоциаций в dcm4che `Device` также создаётся заново и начинается с `1`. При этом в файле `dicom_technical_log.tsv` уже лежат старые события с `SCU-1`, `SCU-2`, `ASSOC-1`. |
| **Можно ли надёжно связать все события одной ассоциации?** | **В SCU — да. В SCP — частично.** | В SCU все события от TCP до RELEASE имеют один `SCU-N`. В SCP событие `TCP_CONNECT` имеет ID `TCP-$port`, а последующий `A-ASSOCIATE-RQ` — `ASSOC-N`. Разрыв идентификатора не позволяет однозначно привязать факт открытия сокета к ассоциации. |
| **Можно ли определить, какие файлы передавались в сеансе?** | **Да.** | В событиях C-STORE-RQ и C-STORE-RSP присутствуют `SOPInstanceUID` и имя файла (в SCU) или путь сохранения (в SCP) с тем же самым `associationId`. |
| **Что происходит при одновременных соединениях?** | **События перемешиваются во времени в едином списке.** | Сервер SCP обрабатывает входящие соединения параллельно в пуле потоков. События от `ASSOC-1` и `ASSOC-2` пишутся в `_logsFlow` вперемешку по мере поступления. В текущем UI нет группировки, поэтому строки от разных подключений чередуются. |
| **Можно ли группировать события без изменения сетевой логики?** | **Да, абсолютно.** | Поле `entry.associationId` уже присутствует в каждой записи. ViewModel может строить сессионную группировку исключительно как слой представления (View/Presentation layer), не трогая сетевые стеки dcm4che. |

---

## 4. Анализ текущего интерфейса (`DicomLogScreen.kt`)

### 4.1. Существующие элементы управления
1. Верхняя информационная строка:
   * Текст «Всего записей: X».
   * Переключатель «Автопрокрутка» (`Switch`).
   * Кнопка корзины (вызов диалога очистки журнала).
2. Список записей (`LazyColumn`):
   * Каждая строка кликабельна и разворачивается индивидуально по `onClick = { onToggleExpand(entry.id) }`.
   * Левая колонка шириной `122.dp`: время `HH:mm:ss.SSS`, бейджи роли (`SCU` / `SCP`), направления (`IN` / `OUT` / `SYS`), и текст `associationId` (`SCU-1`, `ASSOC-2`).
   * Цветной круглый индикатор категории (20dp).
   * Правая колонка: заголовок `title`, строки `summaryLines`.
   * При раскрытии: карточка `technicalDetails` со списком ключ-значение в `SelectionContainer`.

### 4.2. Конкретные недостатки и эргономические проблемы текущего интерфейса

1. **Полное отсутствие фильтрации и поиска:**
   * Нет фильтра по роли (`SCU` / `SCP`).
   * Нет фильтра по результату (`Только ошибки`, `Предупреждения`, `Успешные`).
   * Нет фильтра по типу операции (`C-STORE`, `C-ECHO`, `Ассоциации`, `Сеть`).
   * Нет текстового поиска (по имени файла, названию PACS, AE Title, IP-адресу, SOP Instance UID).
   * Нет фильтра по конкретному сеансу (`Показать только SCU-3`). Инженер вынужден листать сотни строк вручную.
2. **Проблемы вёрстки на смартфонах (узкие экраны):**
   * Левая колонка имеет жёстко фиксированную ширину `122.dp` ([DicomLogScreen.kt#L253](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt#L253)).
   * На экранах телефонов шириной 360–390dp на основное текстовое содержимое остаётся менее 220dp. Длинные UID, пути и заголовки сильно дробятся переносами, снижая читаемость.
3. **Неудобство копирования технических данных:**
   * Используется стандартный `SelectionContainer`. Выделять длинные строки пальцем на сенсорном экране смартфона крайне неудобно.
   * Отсутствует кнопка «Копировать событие» или «Копировать UID» в один клик.
   * Отсутствует функция экспорта журнала в файл или отправки в техническую поддержку (поделиться логом).
4. **Производительность автопрокрутки при пакетных операциях:**
   * В [DicomLogScreen.kt#L83-L87](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt#L83-L87) при каждом изменении размера списка срабатывает `listState.animateScrollToItem(logs.size - 1)`.
   * При пакетной передаче КТ-серии из 100 снимков генерируется более 300 событий за несколько секунд. Анимация скролла для каждого события перегружает поток отрисовки Compose и вызывает сильные микрофризы интерфейса.

---

## 5. Проблемы и ограничения (Сводный реестр дефектов)

| № | Проблема / Ограничение | Уровень критичности | Влияние на систему |
|---|---|:---:|---|
| **1** | **Синхронный блокирующий ввод-вывод на диск в сетевых потоках** ([PersistentDicomTechnicalLogger.kt#L48-L52](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/data/logging/PersistentDicomTechnicalLogger.kt#L48-L52)) | **P0** | Метод `appendEntryToDisk()` синхронно вызывает `appendText()` под `ReentrantLock`. Сетевой поток `dcm4che` приостанавливается на время записи на флеш-диск, что увеличивает время ответа C-STORE-RSP. |
| **2** | **Коллизия идентификаторов сеансов после перезапуска приложения** ([DicomScuClient.kt#L161](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L161), [DicomStorageScpServer.kt#L1080](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L1080)) | **P0** | После перезапуска новые сеансы получают ID `SCU-1`, `ASSOC-1`, которые уже есть в сохранённом файле. Сеансы разных запусков склеиваются или перемешиваются. |
| **3** | **Неконтролируемый рост размера лог-файла на диске** ([PersistentDicomTechnicalLogger.kt#L108-L114](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/data/logging/PersistentDicomTechnicalLogger.kt#L108-L114)) | **P0** | Ограничение в 2000 записей применяется только к оперативной памяти. На диске файл только растёт. Через месяц работы размер файла может превысить сотни мегабайт, приводя к зависанию парсера при старте. |
| **4** | **Разрыв идентификации сеанса при входящем TCP подключении в SCP** ([DicomStorageScpServer.kt#L521](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scp/DicomStorageScpServer.kt#L521)) | **P1** | Событие подключения TCP логируется с ID `TCP-$remotePort`, а последующие события ассоциации — с `ASSOC-$serialNo`. Нет надёжного связывания TCP-события с ассоциацией. |
| **5** | **Ошибочная категория для сводного события отправки очереди** ([DicomScuClient.kt#L1778](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1778)) | **P1** | Итоговый отчёт отправки очереди записывается с категорией `C_STORE_RSP` вместо сводной категории. Это искажает статистику DIMSE-ответов. |
| **6** | **Пропуск индивидуального логирования файлов при отклонённом Presentation Context** ([DicomScuClient.kt#L1572-L1578](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/dicom/scu/DicomScuClient.kt#L1572-L1578)) | **P1** | Если PACS отклонил PC для определённого класса/синтаксиса, в UI файлы помечаются как `FAILED`, но в журнал не пишется запись об ошибке передачи конкретного файла с его именем. |
| **7** | **Отсутствие фильтрации, поиска и группировки в UI** ([DicomLogScreen.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt)) | **P1** | Журнал представляет собой «простыню» из сотен событий в хронологическом порядке без возможности быстро найти ошибку или конкретный файл. |
| **8** | **Фризы анимации скролла при высокой частоте событий** ([DicomLogScreen.kt#L83-L87](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/ui/screens/log/DicomLogScreen.kt#L83-L87)) | **P2** | `animateScrollToItem()` вызывается на каждый кадр/пакет. |

---

## 6. Предлагаемая архитектура краткого и подробного режимов

### 6.1. Принципы проектирования
1. **Единый источник истины (Single Source of Truth):**
   * Все события по-прежнему фиксируются как подробные технические записи `DicomLogEntry`.
   * Краткий режим **не является отдельным файлом или отдельной базой данных**.
   * Краткий режим формируется динамически (или через маппер слоя представления) путём агрегации событий с общим `associationId`.
2. **Сессионная агрегация для краткого режима:**
   * Каждой сессии (`SCU-N` или `SCP-N`) сопоставляется сводная карточка сеанса `DicomSessionSummary`:
     * Роль и направление: `SCU OUT` или `SCP IN`.
     * Идентификатор сеанса: `SCU-3`.
     * Имя удалённого узла / PACS / AE Title.
     * Основная операция: `C-STORE (пакет)`, `C-STORE (тест)`, `C-ECHO`.
     * Итоговый статус: Успешно / С предупреждением / Ошибка / Прервано.
     * Объём данных и число файлов: например, `18,0 МБ · 12 файлов · 0x0000`.
     * Главная причина сбоя (если была ошибка): краткая русскоязычная расшифровка.
3. **Бесшовный переход между режимами:**
   * В заголовке экрана размещается сегментированный переключатель: **«Краткий» / «Подробный»**.
   * В кратком режиме при нажатии на карточку сеанса:
     * Вариант А: разворачивается список переданных файлов с их индивидуальными статусами.
     * Вариант Б: кнопка «Показать протокол сеанса» переключает экран в подробный режим с предустановленным фильтром по данному `associationId`.
4. **Непрерывность записи:**
   * Переключение режимов в UI меняет только способ отображения в Compose и никак не влияет на работу сервисов SCP/SCU или дискового логгера.

---

## 7. Возможность локальной расшифровки ошибок

В приложении **категорически исключается** использование сторонних облачных API или нейросетей для расшифровки ошибок. Требуется строгий детерминированный локальный классификатор (справочник).

### 7.1. Существующие данные для диагностики
В приложении уже заложена основа для такой классификации:
1. `ScuErrorCategory` (10 категорий) и метод `classifyException()` в `DicomScuClient.kt`.
2. `DimseStatusEvaluation` и метод `evaluateDimseStatus()` в `DicomScuClient.kt`.
3. Коды отказов ассоциаций `formatAssociateRjReason()` и прерываний `formatAbortReason()`.

### 7.2. Архитектура локального справочника (`DicomDiagnosticHandbook`)

Предлагается выделить независимый утилитный класс/объект `DicomDiagnosticHandbook`, который по существующим полям `DicomLogEntry` (`category`, `technicalDetails["DICOM Status"]`, `technicalDetails["Exception"]`, `technicalDetails["Reason"]`) возвращает структурированную подсказку для сервисного инженера:

```kotlin
data class DiagnosticExplanation(
    val titleRu: String,                 // Краткая суть проблемы на русском языке
    val probableCauses: List<String>,    // 2-4 наиболее вероятные причины
    val recommendedActions: List<String> // Конкретные шаги по устранению
)
```

#### Примеры типовых правил локального справочника:

1. **Connection refused (`ConnectException`, код `ECONNREFUSED`):**
   * *Объяснение:* «Удалённый узел отклонил TCP-подключение на уровне сетевого сокета.»
   * *Вероятные причины:*
     1. DICOM-сервер (PACS/модальность) не запущен или остановлен на указанном порту.
     2. Указан неверный TCP-порт в настройках отправки.
     3. Локальный брандмауэр (Windows Firewall / iptables) на стороне PACS блокирует входящие подключения на этот порт.
   * *Рекомендации:* «Проверьте, запущена ли служба PACS; убедитесь, что порт открыт с помощью telnet/ping; сверьте номер порта в настройках SCU.»

2. **C-STORE-RSP не получен (Time-out / No Response):**
   * *Объяснение:* «Приложение полностью передало DICOM-объект, но подтверждение от PACS не поступило.»
   * *Вероятные причины:*
     1. PACS выполняет долгую фоновую индексацию или запись в медленную БД и превысил таймаут ответа.
     2. Произошёл разрыв сетевого соединения в момент передачи подтверждения.
     3. Сетевой шлюз или NAT принудительно закрыл неактивную сессию.
   * *Рекомендации:* «Не отправляйте снимок повторно сразу. Проверьте веб-интерфейс или журнал PACS: возможно, файл уже был принят и обрабатывается.» *(Важно: не утверждать, что PACS отклонил файл!)*

3. **Called AE Title not recognized (`A-ASSOCIATE-RJ`, Reason 7):**
   * *Объяснение:* «Удалённый PACS отклонил ассоциацию из-за несовпадения Called AE Title.»
   * *Вероятные причины:*
     1. В поле Called AE Title указано имя, не зарегистрированное в белом списке PACS.
     2. Регистр букв AE Title не совпадает (AE Title чувствителен к регистру).
   * *Рекомендации:* «Сверьте имя Called AE Title в конфигурации PACS и убедитесь, что введено точное совпадение с учётом регистра.»

4. **Status 0xA700 (Refused: Out of Resources):**
   * *Объяснение:* «Принимающая система отказала в сохранении из-за нехватки ресурсов.»
   * *Вероятные причины:*
     1. На дисковом хранилище PACS закончилось свободное место.
     2. Превышен лимит выделенной оперативной памяти процесса PACS.
   * *Рекомендации:* «Проверьте дисковое пространство на сервере PACS.»

---

## 8. Риски изменений

| Риск | Описание | Способ минимизации |
|---|---|---|
| **Регрессия стабильности SCP/SCU** | Вмешательство в сетевые классы может нарушить поток передачи кадров или сломать C-STORE. | Не изменять сетевые методы dcm4che. Логирование вызывать через существующий фасад; группировку сессий делать только на уровне ViewModel/Presentation. |
| **Потеря обратной совместимости логов** | Изменение структуры TSV-файла может привести к сбою чтения старых сохранённых логов. | Сохранить структуру 9 колонок TSV-файла неизменной, либо предусмотреть версионирование формата с безопасным фоллбэком. |
| **Увеличение потребления памяти** | Агрегация сессий в памяти может увеличить footprint приложения. | Агрегировать только идентификаторы и метаданные сессий в пределах существующих 2000 записей (LRU/takeLast). |
| **Утечка персональных данных (PHI)** | При расширении логирования файлов можно случайно залогировать имя пациента. | Строго соблюдать существующий фильтр `sanitizeEntry()`: запретить передачу `PatientName`, `PatientID` и `PixelData` в журнал. |

---

## 9. Рекомендуемый план реализации этапа 4.3

### Приоритет P0 (Критические исправления и стабильность данных)
1. **Устранение блокирующего I/O в логгере ([PersistentDicomTechnicalLogger.kt](file:///E:/Dicomtool/app/src/main/java/ru/medeng/dicomtool/data/logging/PersistentDicomTechnicalLogger.kt)):**
   * Перенести запись на диск в фоновый однопоточный экзекутор / корутину (Channel / non-blocking queue), чтобы файловый ввод-вывод никогда не блокировал потоки ассоциаций dcm4che.
2. **Гарантия глобальной уникальности сессий:**
   * Добавить к префиксу сессии отметку времени или дату запуска (например, `SCU-261013-001`, `SCP-261013-001`), исключив коллизии сессий после перезапуска приложения.
3. **Ротация и ограничение размера файла журнала на диске:**
   * Реализовать ограничение размера TSV-файла на диске (например, не более 5 МБ или не более 2000 строк). При превышении выполнять атомарную ротацию или обрезку старых записей.

### Приоритет P1 (Функционал краткого/подробного режимов и локальной диагностики)
4. **Доменный слой сессий (`DicomSessionSummary`):**
   * Создать модель агрегированной сессии и чистый маппер `List<DicomLogEntry> -> List<DicomSessionSummary>`, группирующий события по уникальному `associationId`.
5. **Интерфейс краткого режима:**
   * Разработать карточки сессий в стиле Material 3 (роль, статус, число переданных файлов, суммарный размер, длительность, понятный статус).
6. **Локальный справочник диагностики (`DicomDiagnosticHandbook`):**
   * Создать локальный статический репозиторий типовых ситуаций и ошибок на русском языке с вероятными причинами и рекомендациями.
7. **Фильтрация и поиск в подробном режиме:**
   * Добавить панель фильтров: по роли (`Все`, `SCP`, `SCU`), по статусу (`Только ошибки`, `Все`), строку поиска по тексту (UID, имя файла, хост, статус).

### Приоритет P2 (Эргономика и удобство использования)
8. **Удобное копирование и экспорт:**
   * Добавить кнопку «Копировать событие» в буфер обмена в один клик.
   * Добавить действие «Экспорт журнала» (сохранение в `.txt` / `.tsv` через системный диалог `Share` / `ACTION_SEND`).
9. **Оптимизация производительности скролла:**
   * Заменить непрерывный `animateScrollToItem` на скролл только при появлении экрана или с троттлингом (batch update) при пакетном приёме файлов.
10. **Адаптивная компоновка для смартфонов:**
    * Сделать гибкую разметку левой колонки (перенос бейджей, компактный таймстамп) для комфортного чтения на узких экранах шириной 360dp.
