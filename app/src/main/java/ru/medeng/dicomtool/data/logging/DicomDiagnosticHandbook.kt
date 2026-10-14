package ru.medeng.dicomtool.data.logging

import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import java.util.Locale

/**
 * Диагностическая рекомендация для сервисных инженеров медицинского оборудования.
 * Работает полностью офлайн без обращения к внешним серверам или ИИ.
 */
data class DiagnosticRecommendation(
    val technicalName: String,
    val explanationRu: String,
    val possibleCausesRu: List<String>,
    val engineerChecklistRu: List<String>,
    val dicomStatusCodeHex: String? = null
)

/**
 * Локальный справочник типовых сетевых и DICOM-ошибок сервисного инженера.
 */
object DicomDiagnosticHandbook {

    /**
     * Анализ отдельного события журнала на наличие известных диагностических паттернов.
     * Возвращает рекомендацию только если событие является ошибкой или предупреждением.
     */
    fun analyzeEntry(entry: DicomLogEntry): DiagnosticRecommendation? {
        if (!entry.isError &&
            entry.category != DicomLogCategory.ASSOCIATE_RJ &&
            entry.category != DicomLogCategory.ABORT &&
            entry.category != DicomLogCategory.NETWORK_ERROR
        ) {
            // Проверяем, нет ли здесь статуса предупреждения C-STORE/C-ECHO
            val statusVal = extractStatusCode(entry)
            if (statusVal != null && statusVal != 0x0000) {
                return lookupDicomStatus(statusVal)
            }
            return null
        }

        val allText = buildString {
            append(entry.title).append(" ")
            entry.summaryLines.forEach { append(it).append(" ") }
            entry.technicalDetails.forEach { (k, v) -> append(k).append(": ").append(v).append(" ") }
        }.lowercase(Locale.US)

        // 1. Извлечение и анализ конкретного числового DICOM-статуса
        val explicitStatus = extractStatusCode(entry)
        if (explicitStatus != null && explicitStatus != 0x0000) {
            val statusRec = lookupDicomStatus(explicitStatus)
            if (statusRec != null) return statusRec
        }

        // 2. Отсутствие ответа C-STORE-RSP / Таймаут ответа
        if (allText.contains("c-store-rsp не получен") ||
            allText.contains("таймаут или отсутствие ответа c-store-rsp") ||
            (entry.category == DicomLogCategory.C_STORE_RQ && allText.contains("таймаут"))
        ) {
            return DiagnosticRecommendation(
                technicalName = "C-STORE-RSP Timeout / Missing Response",
                explanationRu = "Удалённый PACS получил команду C-STORE-RQ, но не прислал подтверждение сохранения (C-STORE-RSP) до закрытия соединения или истечения сетевого таймаута.",
                possibleCausesRu = listOf(
                    "PACS занят сохранением или перестроением базы данных и не успел отправить ответ.",
                    "Ассоциация была разорвана до получения ответа (A-ABORT или обрыв сокета).",
                    "На стороне PACS включена проверка дискового пространства или валидация метаданных, зависшая на обработке.",
                    "Межсетевой экран или NAT прервал входящий TCP-канал ответа."
                ),
                engineerChecklistRu = listOf(
                    "Проверить логи удалённого PACS на предмет ошибок импорта или перегрузки базы данных.",
                    "Увеличить таймаут отклика SCU при работе с медленными PACS.",
                    "Проверить стабильность Wi-Fi/Ethernet соединения между планшетом и PACS.",
                    "Убедиться, что PACS не сбросил TCP-соединение из-за внутренней ошибки сохранения."
                )
            )
        }

        // 3. Отказ в согласовании Presentation Context
        if (allText.contains("отклонен контекст представления") ||
            allText.contains("no presentation context") ||
            allText.contains("presentation context rejected") ||
            allText.contains("нет согласованного контекста") ||
            entry.category == DicomLogCategory.PRESENTATION_CONTEXT && entry.isError
        ) {
            return DiagnosticRecommendation(
                technicalName = "Presentation Context Rejected",
                explanationRu = "PACS отклонил контекст представления для данного SOP Class или предложенных синтаксисов передачи (Transfer Syntax).",
                possibleCausesRu = listOf(
                    "Удалённый PACS не поддерживает передаваемый класс объекта (например, Secondary Capture или специфическую модальность).",
                    "PACS не поддерживает предложенный Transfer Syntax (например, JPEG Lossless или Deflated little endian).",
                    "На PACS строго сконфигурирован список принимаемых SOP Class для данного Calling AE Title."
                ),
                engineerChecklistRu = listOf(
                    "Сверить DICOM Conformance Statement удалённого PACS с параметрами файла.",
                    "Проверить разрешённые синтаксисы передачи в конфигурации PACS (предпочтителен Explicit VR Little Endian или Implicit VR Little Endian).",
                    "Проверить, включен ли приём Secondary Capture Image Storage (1.2.840.10008.5.1.4.1.1.7) на стороне PACS."
                )
            )
        }

        // 4. Несовместимый Transfer Syntax
        if (allText.contains("transfer syntax") && (allText.contains("не поддерживается") || allText.contains("unsupported") || allText.contains("incompatible"))) {
            return DiagnosticRecommendation(
                technicalName = "Unsupported Transfer Syntax",
                explanationRu = "Синтаксис передачи (Transfer Syntax) файла не поддерживается удалённым узлом.",
                possibleCausesRu = listOf(
                    "Файл закодирован сжатием (JPEG Baseline, JPEG 2000, RLE), которое не активировано на принимающем сервере.",
                    "PACS требует распакованный синтаксис (Explicit VR Little Endian)."
                ),
                engineerChecklistRu = listOf(
                    "Сверить поддерживаемые Transfer Syntaxes в Conformance Statement приёмника.",
                    "При необходимости выполнить транскодирование в Implicit/Explicit VR Little Endian."
                )
            )
        }

        // 5. Отклонение ассоциации A-ASSOCIATE-RJ
        if (entry.category == DicomLogCategory.ASSOCIATE_RJ || allText.contains("a-associate-rj") || allText.contains("association rejected")) {
            val reason = entry.technicalDetails["Причина отказа"] ?: entry.technicalDetails["Причина"] ?: ""
            return DiagnosticRecommendation(
                technicalName = "A-ASSOCIATE-RJ (Association Rejected)",
                explanationRu = "Удалённый DICOM-узел отклонил запрос на установление ассоциации.${if (reason.isNotBlank()) " Причина: $reason." else ""}",
                possibleCausesRu = listOf(
                    "Неверный Called AE Title (PACS ожидает другое имя своей службы).",
                    "Неверный или неразрешённый Calling AE Title (PACS не знает данный мобильный клиент и фильтрует по белому списку).",
                    "Превышен лимит одновременных ассоциаций на стороне PACS.",
                    "Несовпадение версии прикладного протокола DICOM."
                ),
                engineerChecklistRu = listOf(
                    "Сверить Called AE Title с точным регистром букв в настройках PACS.",
                    "Добавить Calling AE Title приложения (по умолчанию ANDROID_SCU) в белый список разрешённых узлов на PACS.",
                    "Проверить порт службы PACS (обычно 104, 11112 или 4242)."
                )
            )
        }

        // 6. Аварийный разрыв ассоциации A-ABORT
        if (entry.category == DicomLogCategory.ABORT || allText.contains("a-abort") || allText.contains("association aborted")) {
            return DiagnosticRecommendation(
                technicalName = "A-ABORT (Association Aborted)",
                explanationRu = "Ассоциация была немедленно разорвана одной из сторон без штатной процедуры A-RELEASE.",
                possibleCausesRu = listOf(
                    "Удалённая служба упала или была принудительно перезапущена во время обмена.",
                    "Получен некорректный или повреждённый PDU-пакет.",
                    "Таймаут неактивности (Association Inactivity Timeout) на стороне PACS.",
                    "Сетевой сокет был разорван маршрутизатором или брандмауэром."
                ),
                engineerChecklistRu = listOf(
                    "Проверить журнал службы PACS на момент сброса.",
                    "Убедиться в отсутствии потери сетевых пакетов (ping / MTU).",
                    "Проверить размер максимальной длины PDU (Max PDU Length)."
                )
            )
        }

        // 7. Connection refused (В соединении отказано)
        if (allText.contains("connection refused") || allText.contains("отказано") || allText.contains("connectexception")) {
            return DiagnosticRecommendation(
                technicalName = "Connection Refused (ECONNREFUSED)",
                explanationRu = "Целевой хост доступен в сети, но на указанном TCP-порту не запущена служба DICOM или входящие подключения сброшены файрволом.",
                possibleCausesRu = listOf(
                    "Служба PACS/SCP остановлена или не запущена.",
                    "Указан неверный TCP-порт в настройках отправки.",
                    "Брандмауэр операционной системы сервера блокирует входящий TCP-порт.",
                    "Служба слушает только локальный адрес (127.0.0.1) вместо внешнего интерфейса (0.0.0.0)."
                ),
                engineerChecklistRu = listOf(
                    "Проверить статус службы PACS на целевом сервере.",
                    "Сверить номер порта в приложении и на сервере (например, 104, 11112, 4242).",
                    "Проверить правила Windows Firewall / iptables на сервере PACS.",
                    "Проверить доступность порта утилитой telnet / nc / nmap с того же сегмента сети."
                )
            )
        }

        // 8. Connection timeout / Socket timeout
        if (allText.contains("connect timed out") ||
            allText.contains("connection timeout") ||
            allText.contains("таймаут подключения") ||
            allText.contains("etimedout")
        ) {
            return DiagnosticRecommendation(
                technicalName = "Connection Timeout (ETIMEDOUT)",
                explanationRu = "Не удалось установить TCP-соединение с удалённым узлом в течение заданного времени ожидания.",
                possibleCausesRu = listOf(
                    "Указан неверный IP-адрес сервера.",
                    "Планшет и PACS находятся в разных изолированных подсетях или VLAN.",
                    "Межсетевой экран или корпоративный шлюз молча отбрасывает (DROP) пакеты SYN.",
                    "Целевой компьютер выключен или отключен от кабельной сети/Wi-Fi."
                ),
                engineerChecklistRu = listOf(
                    "Проверить правильность IP-адреса PACS в настройках SCU.",
                    "Убедиться, что мобильное устройство подключено к той же локальной сети (Wi-Fi), что и PACS.",
                    "Проверить доступность сервера командой ping из локальной сети.",
                    "Проверить маршрутизацию между Wi-Fi сегментом и сегментом серверов."
                )
            )
        }

        // 9. Socket read timeout
        if (allText.contains("read timed out") || allText.contains("таймаут ожидания данных") || allText.contains("sockettimeoutexception")) {
            return DiagnosticRecommendation(
                technicalName = "Socket Read Timeout",
                explanationRu = "TCP-соединение было установлено, но сервер перестал передавать ожидаемые данные в течение таймаута чтения сокета.",
                possibleCausesRu = listOf(
                    "PACS завис при выполнении запроса или дисковой операции.",
                    "Временный разрыв Wi-Fi соединения планшета.",
                    "Удалённая сторона ожидает подтверждения, которое не дошло из-за фрагментации пакетов."
                ),
                engineerChecklistRu = listOf(
                    "Проверить нагрузку на процессор и диск сервера PACS.",
                    "Проверить стабильность сигнала Wi-Fi на Android-устройстве."
                )
            )
        }

        // 10. Connection reset / Broken pipe
        if (allText.contains("connection reset") ||
            allText.contains("соединен") && allText.contains("сброшен") ||
            allText.contains("broken pipe") ||
            allText.contains("econnreset")
        ) {
            return DiagnosticRecommendation(
                technicalName = "Connection Reset (ECONNRESET)",
                explanationRu = "Удалённый узел или промежуточный шлюз принудительно разорвал уже установленное TCP-соединение с флагом TCP RST.",
                possibleCausesRu = listOf(
                    "Процесс PACS аварийно завершился во время передачи данных.",
                    "Сетевой экран (Stateful Firewall) сбросил сессию по внутреннему таймауту неактивности.",
                    "Попытка передачи неподдерживаемого формата или повреждённого пакета вызвала крах обработчика на PACS."
                ),
                engineerChecklistRu = listOf(
                    "Изучить журнал событий операционной системы и службы PACS в момент сброса.",
                    "Проверить настройки сетевого экрана на предмет агрессивных таймаутов TCP RST."
                )
            )
        }

        // 11. Unknown host / DNS error
        if (allText.contains("unknownhostexception") || allText.contains("unknown host") || allText.contains("неизвестный хост") || allText.contains("dns")) {
            return DiagnosticRecommendation(
                technicalName = "Unknown Host / DNS Error",
                explanationRu = "Не удалось разрешить сетевое имя хоста PACS в IP-адрес через DNS.",
                possibleCausesRu = listOf(
                    "Опечатка в доменном имени или hostname сервера.",
                    "DNS-сервер недоступен в текущей Wi-Fi сети.",
                    "Имя хоста не зарегистрировано в локальной зоне DNS."
                ),
                engineerChecklistRu = listOf(
                    "Указать прямой числовой IP-адрес вместо доменного имени (например, 192.168.1.50).",
                    "Проверить настройки DNS в параметрах сетевого подключения Wi-Fi на планшете."
                )
            )
        }

        // 12. Ошибка чтения файла
        if (allText.contains("ошибка чтения файла") ||
            allText.contains("filenotfoundexception") ||
            allText.contains("securityexception") ||
            allText.contains("permission denial")
        ) {
            return DiagnosticRecommendation(
                technicalName = "File Access / Read Error",
                explanationRu = "Приложению не удалось прочитать DICOM-файл с диска или из внешнего хранилища.",
                possibleCausesRu = listOf(
                    "Файл был удалён или перемещён другим приложением до отправки.",
                    "Истёк срок временного URI-разрешения Android Storage Access Framework (SAF).",
                    "Файл повреждён или заблокирован антивирусом/системой."
                ),
                engineerChecklistRu = listOf(
                    "Выбрать файл заново через системный проводник для обновления разрешений SAF.",
                    "Убедиться, что файл не имеет нулевой размер и доступен в памяти устройства."
                )
            )
        }

        // 13. Ошибка сохранения файла (SCP)
        if (allText.contains("ошибка сохранения принятого файла") ||
            allText.contains("не удалось сохранить") ||
            allText.contains("диск переполнен") ||
            allText.contains("no space left on device")
        ) {
            return DiagnosticRecommendation(
                technicalName = "File Storage / Write Error",
                explanationRu = "Приёмнику DICOM Kit (SCP) не удалось сохранить принятый файл на локальный диск устройства.",
                possibleCausesRu = listOf(
                    "Закончилось свободное место во внутреннем хранилище или на SD-карте планшета.",
                    "Ошибка доступа к каталогу хранения исследований."
                ),
                engineerChecklistRu = listOf(
                    "Проверить доступное дисковое пространство в разделе «Настройки и хранилище» DICOM Kit.",
                    "Выполнить очистку старых исследований или сменить каталог хранения на внешний накопитель."
                )
            )
        }

        return null
    }

    /**
     * Расшифровка стандартных статусов DICOM (PS3.4 / PS3.7).
     */
    fun lookupDicomStatus(statusCode: Int): DiagnosticRecommendation? {
        val hex = String.format(Locale.US, "0x%04X", statusCode and 0xFFFF)
        return when (statusCode and 0xFFFF) {
            0x0000 -> DiagnosticRecommendation(
                technicalName = "Success (0x0000)",
                explanationRu = "Операция успешно выполнена удалённым узлом без ошибок.",
                possibleCausesRu = listOf("Штатное подтверждение DICOM C-STORE или C-ECHO."),
                engineerChecklistRu = listOf("Действий инженера не требуется."),
                dicomStatusCodeHex = hex
            )

            0x0001 -> DiagnosticRecommendation(
                technicalName = "Warning: Coercion of Data Elements (0x0001)",
                explanationRu = "PACS сохранил исследование, но изменил значения некоторых атрибутов для соответствия своей локальной модели данных.",
                possibleCausesRu = listOf("Автоматическая коррекция Patient ID, Study UID или Specific Character Set на стороне PACS."),
                engineerChecklistRu = listOf("Сверить атрибуты принятого на PACS файла с исходным объектом."),
                dicomStatusCodeHex = hex
            )

            0xB000 -> DiagnosticRecommendation(
                technicalName = "Warning: Elements Discarded (0xB000)",
                explanationRu = "PACS принял объект, но отбросил часть неизвестных или неподдерживаемых атрибутов.",
                possibleCausesRu = listOf("Объект содержит нестандартные или приватные теги (Private Tags), не включённые в словарь PACS."),
                engineerChecklistRu = listOf("Проверить, поддерживаются ли приватные теги оборудования в конфигурации архива."),
                dicomStatusCodeHex = hex
            )

            0xB006 -> DiagnosticRecommendation(
                technicalName = "Warning: Elements Coerced (0xB006)",
                explanationRu = "При сохранении значения некоторых элементов были принудительно заменены.",
                possibleCausesRu = listOf("Согласование данных с Worklist или внутренним регламентом архива."),
                engineerChecklistRu = listOf("Проверить правила коэрциции тегов на сервере."),
                dicomStatusCodeHex = hex
            )

            0xB007 -> DiagnosticRecommendation(
                technicalName = "Warning: Data Set does not match SOP Class (0xB007)",
                explanationRu = "Набор атрибутов не полностью соответствует требованиям спецификации SOP Class, но объект был принят.",
                possibleCausesRu = listOf("Отсутствуют некоторые атрибуты типа 2 или не обязательные теги."),
                engineerChecklistRu = listOf("Проверить соответствие метаданных стандарту DICOM PS3.3."),
                dicomStatusCodeHex = hex
            )

            0x0111 -> DiagnosticRecommendation(
                technicalName = "Failure: Duplicate SOP Instance (0x0111)",
                explanationRu = "PACS отклонил сохранение: объект с таким же SOP Instance UID уже существует в архиве.",
                possibleCausesRu = listOf("Повторная отправка того же файла или конфликт генератора UID."),
                engineerChecklistRu = listOf("Проверить, сохранён ли файл на PACS ранее, либо сгенерировать новые уникальные UID."),
                dicomStatusCodeHex = hex
            )

            0x0122 -> DiagnosticRecommendation(
                technicalName = "Failure: SOP Class Not Supported (0x0122)",
                explanationRu = "PACS отклонил операцию: класс SOP Class не поддерживается сервером.",
                possibleCausesRu = listOf("Архив не сконфигурирован для хранения данного типа исследований."),
                engineerChecklistRu = listOf("Сверить DICOM Conformance Statement PACS."),
                dicomStatusCodeHex = hex
            )

            0x0124 -> DiagnosticRecommendation(
                technicalName = "Failure: Not Authorized (0x0124)",
                explanationRu = "Отказ в авторизации: клиенту запрещено выполнять данную операцию на PACS.",
                possibleCausesRu = listOf("Calling AE Title не имеет прав записи в архив."),
                engineerChecklistRu = listOf("Проверить права доступа для Calling AE Title на стороне PACS."),
                dicomStatusCodeHex = hex
            )

            0x0211 -> DiagnosticRecommendation(
                technicalName = "Failure: Unrecognized Operation (0x0211)",
                explanationRu = "Запрошенная операция не распознана удалённым узлом.",
                possibleCausesRu = listOf("Несовместимость реализаций протокола DICOM."),
                engineerChecklistRu = listOf("Проверить согласованные параметры ассоциации."),
                dicomStatusCodeHex = hex
            )

            in 0xA700..0xA7FF -> DiagnosticRecommendation(
                technicalName = "Failure: Out of Resources ($hex)",
                explanationRu = "На удалённом сервере PACS закончились системные ресурсы (дисковое пространство, память или дескрипторы базы данных).",
                possibleCausesRu = listOf(
                    "Диск хранилища PACS переполнен.",
                    "Превышен пул подключений базы данных архива.",
                    "Временная нехватка оперативной памяти сервера."
                ),
                engineerChecklistRu = listOf(
                    "Проверить свободное место на дисковых накопителях сервера PACS.",
                    "Проверить системный монитор и службы PACS."
                ),
                dicomStatusCodeHex = hex
            )

            in 0xA900..0xA9FF -> DiagnosticRecommendation(
                technicalName = "Failure: Data Set does not match SOP Class ($hex)",
                explanationRu = "Данные файла не соответствуют объявленному классу SOP Class. Сервер отклонил сохранение.",
                possibleCausesRu = listOf(
                    "В файле отсутствуют обязательные атрибуты типа 1 (Type 1 Attributes).",
                    "Некорректная структура заголовка DICOM."
                ),
                engineerChecklistRu = listOf(
                    "Проверить обязательные теги (PatientID, StudyInstanceUID, SeriesInstanceUID, SOPInstanceUID, Modality).",
                    "Проверить файл валидатором dcmtk dcmftest / dciodvfy."
                ),
                dicomStatusCodeHex = hex
            )

            in 0xC000..0xCFFF -> DiagnosticRecommendation(
                technicalName = "Failure: Processing Failure / Cannot Understand ($hex)",
                explanationRu = "Внутренняя ошибка обработки на стороне сервера PACS при сохранении объекта.",
                possibleCausesRu = listOf(
                    "Сбой базы данных архива при индексации файла.",
                    "Ошибка плагина или постобработчика PACS.",
                    "Неподдерживаемая кодировка или повреждённые пиксельные данные."
                ),
                engineerChecklistRu = listOf(
                    "Проверить журнал ошибок удалённого сервера PACS за точное время передачи.",
                    "Убедиться в целостности исходного DICOM-файла."
                ),
                dicomStatusCodeHex = hex
            )

            else -> null
        }
    }

    /**
     * Извлечение целочисленного кода статуса DICOM из полей записи (title, details).
     */
    fun extractStatusCode(entry: DicomLogEntry): Int? {
        val statusStr = entry.technicalDetails["Статус"]
            ?: entry.technicalDetails["Status"]
            ?: entry.technicalDetails["Код ответа"]
            ?: ""

        findHexStatus(statusStr)?.let { return it }
        findHexStatus(entry.title)?.let { return it }
        for (line in entry.summaryLines) {
            findHexStatus(line)?.let { return it }
        }
        return null
    }

    private fun findHexStatus(text: String): Int? {
        if (text.isBlank()) return null
        val regex = Regex("""0x([0-9a-fA-F]{1,4})\b""")
        val match = regex.find(text) ?: return null
        return try {
            match.groupValues[1].toInt(16)
        } catch (_: Exception) {
            null
        }
    }
}
