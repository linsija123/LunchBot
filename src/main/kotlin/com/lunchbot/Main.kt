package com.lunchbot

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

private val json = Json {
    ignoreUnknownKeys = true
}

private val timeZone = ZoneId.of("Europe/Tirane")

// Тестовий час; після перезапуску бота використовується реальний час.
private var testDateTime: LocalDateTime? = null

private fun botNow(): LocalDateTime =
    testDateTime ?: LocalDateTime.now(timeZone)

private const val ADD_PROMPT =
    "Щоб додати дитину, натисніть «Відповісти» на це повідомлення та надішліть ім'я. Кілька імен — кожне з нового рядка. Очікування — 2 хвилини."

private const val ADD_TIMEOUT_MILLIS = 2 * 60 * 1000L

private data class PendingAdd(
    val chatId: Long,
    val promptMessageId: Long,
    val expiresAt: Long
)

// Очікування вводу прив'язане до конкретного користувача, а не до всієї групи.
private val pendingAdd = mutableMapOf<Long, PendingAdd>()

private const val DEFAULT_PROMPT =
    "Надішліть початковий список дітей. Кожне ім'я з нового рядка:"

private val pendingDefault = mutableMapOf<Long, Long>()

private fun today(): LocalDate =
    botNow().toLocalDate()

private fun isWeekend(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY

private fun isGroupChat(message: JsonObject): Boolean {
    val type = message["chat"]
        ?.jsonObject
        ?.get("type")
        ?.jsonPrimitive
        ?.content

    return type == "group" || type == "supergroup"
}


private suspend fun telegramRequest(
    client: HttpClient,
    token: String,
    method: String,
    parameters: Map<String, String> = emptyMap()
): JsonObject {

    val response = client.get(
        "https://api.telegram.org/bot$token/$method"
    ) {
        parameters.forEach { (key, value) ->
            parameter(key, value)
        }
    }.bodyAsText()

    val result = json.parseToJsonElement(response).jsonObject

    if (result["ok"]?.jsonPrimitive?.boolean != true) {
        val errorCode = result["error_code"]?.jsonPrimitive?.intOrNull
        val description = result["description"]?.jsonPrimitive?.content
        val retryAfter = result["parameters"]
            ?.jsonObject
            ?.get("retry_after")
            ?.jsonPrimitive
            ?.intOrNull

        error(
            "Telegram API error: method=$method, " +
                    "code=$errorCode, description=$description, " +
                    "retry_after=$retryAfter"
        )
    }

    return result
}


// Надсилання повідомлення
private suspend fun sendMessage(
    client: HttpClient,
    token: String,
    chatId: Long,
    text: String,
    replyMarkup: String? = null
): JsonObject {

    val parameters = mutableMapOf(
        "chat_id" to chatId.toString(),
        "text" to text
    )

    if (replyMarkup != null) {
        parameters["reply_markup"] = replyMarkup
    }

    return telegramRequest(
        client,
        token,
        "sendMessage",
        parameters
    )
}

// Редагування повідомлення
private suspend fun editMessage(
    client: HttpClient,
    token: String,
    chatId: Long,
    messageId: Long,
    text: String,
    replyMarkup: String
) {
    telegramRequest(
        client,
        token,
        "editMessageText",
        mapOf(
            "chat_id" to chatId.toString(),
            "message_id" to messageId.toString(),
            "text" to text,
            "reply_markup" to replyMarkup
        )
    )
}

// Відповідь на натискання кнопки
private suspend fun answerCallback(
    client: HttpClient,
    token: String,
    callbackId: String,
    text: String? = null
) {
    val parameters = mutableMapOf(
        "callback_query_id" to callbackId
    )

    if (text != null) {
        parameters["text"] = text
    }

    telegramRequest(
        client,
        token,
        "answerCallbackQuery",
        parameters
    )
}

// Клавіатура основного списку — без окремих кнопок біля кожної дитини
private fun createListKeyboard(
    children: List<ChildEntry>,
    closed: Boolean
): String {
    val rows = buildJsonArray {
        if (!closed) {
            add(buildJsonArray {
                add(buildJsonObject {
                    put("text", "➕ Додати дитину")
                    put("callback_data", "add")
                })
            })
            if (children.isNotEmpty()) {
                add(buildJsonArray {
                    add(buildJsonObject {
                        put("text", "🗑 Видалити дитину")
                        put("callback_data", "del_open")
                    })
                })
            }
        }
    }

    return buildJsonObject {
        put("inline_keyboard", rows)
    }.toString()
}

private const val DELETE_PAGE_SIZE = 8

// Окреме меню вибору дитини для видалення
private fun createDeleteKeyboard(
    children: List<ChildEntry>,
    page: Int
): String {
    val pageCount = maxOf(1, (children.size + DELETE_PAGE_SIZE - 1) / DELETE_PAGE_SIZE)
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageChildren = children.drop(safePage * DELETE_PAGE_SIZE).take(DELETE_PAGE_SIZE)

    val rows = buildJsonArray {
        pageChildren.chunked(2).forEach { pair ->
            add(buildJsonArray {
                pair.forEach { child ->
                    add(buildJsonObject {
                        put("text", child.name)
                        put("callback_data", "del_pick:${child.id}")
                    })
                }
            })
        }

        if (pageCount > 1) {
            add(buildJsonArray {
                if (safePage > 0) {
                    add(buildJsonObject {
                        put("text", "⬅️")
                        put("callback_data", "del_page:${safePage - 1}")
                    })
                }
                add(buildJsonObject {
                    put("text", "${safePage + 1}/$pageCount")
                    put("callback_data", "del_page:$safePage")
                })
                if (safePage < pageCount - 1) {
                    add(buildJsonObject {
                        put("text", "➡️")
                        put("callback_data", "del_page:${safePage + 1}")
                    })
                }
            })
        }

        add(buildJsonArray {
            add(buildJsonObject {
                put("text", "↩️ Назад")
                put("callback_data", "del_back")
            })
        })
    }

    return buildJsonObject { put("inline_keyboard", rows) }.toString()
}

private suspend fun showDeleteMenu(
    client: HttpClient,
    token: String,
    database: Database,
    chatId: Long,
    messageId: Long,
    page: Int = 0
) {
    val children = database.getChildren(today())
    val pageCount = maxOf(1, (children.size + DELETE_PAGE_SIZE - 1) / DELETE_PAGE_SIZE)
    val safePage = page.coerceIn(0, pageCount - 1)

    val text = if (children.isEmpty()) {
        "У списку поки немає дітей."
    } else {
        "Оберіть дитину для видалення (${children.size}):\nСторінка ${safePage + 1} з $pageCount"
    }

    editMessage(
        client, token, chatId, messageId, text,
        createDeleteKeyboard(children, safePage)
    )
}

// Клавіатура підтвердження видалення
private fun createConfirmationKeyboard(childId: Long): String {
    val rows = buildJsonArray {
        add(buildJsonArray {
            add(buildJsonObject {
                put("text", "Так, видалити")
                put("callback_data", "del_confirm:$childId")
            })
            add(buildJsonObject {
                put("text", "Скасувати")
                put("callback_data", "del_cancel")
            })
        })
    }
    return buildJsonObject { put("inline_keyboard", rows) }.toString()
}

// Формування тексту списку
private fun renderList(
    date: LocalDate,
    database: Database
): String {
    val children = database.getChildren(date)
    val closed = database.getListStatus(date) == "CLOSED"

    val childrenText = if (children.isEmpty()) {
        "Поки що дітей у списку немає."
    } else {
        children.mapIndexed { index, child ->
            "${index + 1}. ${child.name}"
        }.joinToString("\n")
    }

    return buildString {
        appendLine("🍽 Список дітей на обід")
        appendLine("📅 ${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
        appendLine()

        if (!closed) {
            appendLine(
                "✏️ Зміни до списку можна вносити до ${
                    database.getCloseTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                }. Після цього список буде закрито, а обід замовлено."
            )
            appendLine()
        }

        appendLine(childrenText)
        appendLine()
        appendLine("👥 Усього дітей: ${children.size}")

        if (closed) {
            appendLine()
            append("🔒 Голосування завершено")
        }
    }
}

// Оновлення основного повідомлення
private suspend fun updateListMessage(
    client: HttpClient,
    token: String,
    database: Database,
    date: LocalDate
) {
    val savedMessage = database.getTelegramMessage(date) ?: return

    val children = database.getChildren(date)
    val closed = database.getListStatus(date) == "CLOSED"

    editMessage(
        client = client,
        token = token,
        chatId = savedMessage.first,
        messageId = savedMessage.second,
        text = renderList(date, database),
        replyMarkup = createListKeyboard(children, closed)
    )
}

// Створення або відкриття списку вручну
private suspend fun showList(
    client: HttpClient,
    token: String,
    database: Database,
    chatId: Long,
    groupChat: Boolean
) {
    val date = today()

    if (isWeekend(date)) {
        sendMessage(
            client,
            token,
            chatId,
            "Сьогодні вихідний. Список на обід не створюється."
        )
        return
    }

    if (groupChat) {
        database.setTargetChatId(chatId)
    }

    database.createDailyList(date, cleanupHistory = testDateTime == null)

    // Після встановленого часу закриття список одразу закривається
    if (!botNow().toLocalTime().isBefore(database.getCloseTime())) {
        database.closeDailyList(date)
    }

    val savedMessage = database.getTelegramMessage(date)

    if (savedMessage != null && savedMessage.first == chatId) {
        updateListMessage(client, token, database, date)
        return
    }

    val children = database.getChildren(date)
    val closed = database.getListStatus(date) == "CLOSED"

    val response = sendMessage(
        client,
        token,
        chatId,
        renderList(date, database),
        createListKeyboard(children, closed)
    )

    val messageId = response["result"]
        ?.jsonObject
        ?.get("message_id")
        ?.jsonPrimitive
        ?.long ?: error("Could not get message ID")

    database.saveTelegramMessage(
        date,
        chatId,
        messageId
    )
}

// Щоденне автоматичне створення та закриття списку
private suspend fun processSchedule(
    client: HttpClient,
    token: String,
    database: Database,
    adminId: Long
) {
    if (database.isPaused()) return

    val date = today()

    if (isWeekend(date)) return

    val currentTime = botNow().toLocalTime()

    val startTime = database.getStartTime()
    val closeTime = database.getCloseTime()

    // До встановленого часу публікації нічого не робимо
    if (currentTime.isBefore(startTime)) return

    val targetChatId = database.getTargetChatId() ?: return

    // Створення списку з перенесенням попередніх записів
    database.createDailyList(date, cleanupHistory = testDateTime == null)

    // Перевіряємо, чи вже є повідомлення в групі
    var savedMessage = database.getTelegramMessage(date)

    if (savedMessage == null) {
        val children = database.getChildren(date)

        val response = sendMessage(
            client,
            token,
            targetChatId,
            renderList(date, database),
            createListKeyboard(
                children,
                database.getListStatus(date) == "CLOSED"
            )
        )

        val messageId = response["result"]
            ?.jsonObject
            ?.get("message_id")
            ?.jsonPrimitive
            ?.long ?: error("Could not get message ID")

        database.saveTelegramMessage(
            date,
            targetChatId,
            messageId
        )

        savedMessage = Pair(targetChatId, messageId)
    }

    // До встановленого часу закриття список залишається відкритим
    if (currentTime.isBefore(closeTime)) return

    // Закриваємо список, якщо він ще відкритий
    val wasOpen = database.getListStatus(date) == "OPEN"

    if (wasOpen) {
        database.closeDailyList(date)

        try {
            updateListMessage(
                client,
                token,
                database,
                date
            )
        } catch (e: Exception) {
            println("Could not update closed list: ${e.message}")
        }
    }

    val children = database.getChildren(date)
    val finalText = buildString {
        appendLine("✅ Список на сьогодні закрито")
        appendLine("📅 $date")
        appendLine()
        appendLine(
            if (children.isEmpty()) {
                "Дітей у списку немає."
            } else {
                children.mapIndexed { index, child ->
                    "${index + 1}. ${child.name}"
                }.joinToString("\n")
            }
        )
        appendLine()
        append("👥 Усього дітей: ${children.size}")
    }

    // Надсилаємо підсумковий звіт адміністратору
    if (!database.isAdminNotified(date)) {
        try {
            sendMessage(client, token, adminId, finalText)
            database.markAdminNotified(date)
            println("Daily list closed and admin notified.")
        } catch (e: Exception) {
            println("Could not notify admin: ${e.message}")
        }
    }

    // Надсилаємо підсумковий звіт додатковим отримувачам
    for (recipientId in database.getAdditionalRecipients()) {
        if (database.isRecipientNotified(date, recipientId)) continue

        try {
            sendMessage(client, token, recipientId, finalText)
            database.markRecipientNotified(date, recipientId)
            println("Daily list sent to additional recipient: $recipientId")
        } catch (e: Exception) {
            println("Could not notify additional recipient $recipientId: ${e.message}")
        }
    }
}

// Обробка звичайних повідомлень
private suspend fun handleMessage(
    client: HttpClient,
    token: String,
    database: Database,
    message: JsonObject,
    adminId: Long
) {
    val chatId = message["chat"]
        ?.jsonObject
        ?.get("id")
        ?.jsonPrimitive
        ?.long ?: return

    val fromId = message["from"]
        ?.jsonObject
        ?.get("id")
        ?.jsonPrimitive
        ?.long

    val text = message["text"]
        ?.jsonPrimitive
        ?.content ?: return

    val date = today()

    val repliedMessageId = message["reply_to_message"]
        ?.jsonObject
        ?.get("message_id")
        ?.jsonPrimitive
        ?.long

    // Обробляємо ім'я лише як явну відповідь на конкретний запит цього користувача.
    // ForceReply не використовується, тому Telegram не відкриває Reply автоматично.
    if (fromId != null) {
        val pending = pendingAdd[fromId]

        if (pending != null && pending.expiresAt <= System.currentTimeMillis()) {
            pendingAdd.remove(fromId)
        } else if (
            pending != null &&
            pending.chatId == chatId &&
            repliedMessageId == pending.promptMessageId
        ) {
            val names = text
                .split("\n", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (names.isNotEmpty()) {
                pendingAdd.remove(fromId)
                database.addChildren(
                    date,
                    names,
                    cleanupHistory = testDateTime == null
                )
                updateListMessage(client, token, database, date)
                editMessage(
                    client,
                    token,
                    chatId,
                    pending.promptMessageId,
                    "Дитину додано до списку.",
                    "{\"inline_keyboard\":[]}"
                )
                return
            }
        }
    }

    // Відповідь на запит початкового списку (адміністраторська команда).
    val replyText = message["reply_to_message"]
        ?.jsonObject
        ?.get("text")
        ?.jsonPrimitive
        ?.content

    if (
        replyText?.startsWith(DEFAULT_PROMPT) == true &&
        fromId == adminId &&
        pendingDefault.containsKey(adminId)
    ) {
        val names = text
            .split("\n", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val count = database.setDefaultChildren(names)
        pendingDefault.remove(adminId)

        sendMessage(
            client,
            token,
            chatId,
            "Початковий список збережено. Дітей: $count."
        )
        return
    }

    val command = text
        .substringBefore(" ")
        .substringBefore("@")

    when (command) {

        "/start" -> {
            sendMessage(
                client,
                token,
                chatId,
                """
                Вітаю! Я LunchBot.

                Допомагаю формувати щоденний список дітей на обід.

                Для перегляду списку використовуйте /list.
                """.trimIndent()
            )
        }

        "/list" -> {
            showList(
                client,
                token,
                database,
                chatId,
                isGroupChat(message)
            )
        }

        "/testtime" -> {
            if (fromId != adminId) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Ця команда доступна лише адміністратору."
                )
                return
            }

            if (isGroupChat(message)) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Надішліть команду /testtime в особистому чаті з ботом."
                )
                return
            }

            val testTimeText = text.substringAfter(" ", "").trim()

            if (testTimeText.isBlank()) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Вкажіть дату й час у форматі: /testtime 2026-11-24 07:00"
                )
                return
            }

            try {
                val parsed = LocalDateTime.parse(
                    testTimeText,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                )
                testDateTime = parsed

                sendMessage(
                    client,
                    token,
                    chatId,
                    "⏱ Тестовий час встановлено: ${parsed.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} (Europe/Tirane). Розклад перевіриться в наступному циклі."
                )
            } catch (e: DateTimeParseException) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Не вдалося розпізнати дату. Формат: /testtime 2026-11-24 07:00"
                )
            }
        }

        "/realtime" -> {
            if (fromId != adminId) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Ця команда доступна лише адміністратору."
                )
                return
            }

            if (isGroupChat(message)) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Надішліть команду /realtime в особистому чаті з ботом."
                )
                return
            }

            testDateTime = null

            sendMessage(
                client,
                token,
                chatId,
                "▶ Повернув реальний час: ${botNow().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} (Europe/Tirane)."
            )
        }

        "/setdefault" -> {
            if (fromId != adminId) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Ця команда доступна лише адміністратору."
                )
                return
            }

            if (!isGroupChat(message)) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Виконайте цю команду в груповому чаті."
                )
                return
            }

            database.setTargetChatId(chatId)
            pendingDefault[adminId] = chatId

            sendMessage(
                client,
                token,
                chatId,
                DEFAULT_PROMPT + " Відповідайте на це повідомлення."
            )
        }

        "/settime" -> {
            if (fromId != adminId) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Ця команда доступна лише адміністратору."
                )
                return
            }

            if (isGroupChat(message)) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Надішліть команду /settime в особистому чаті з ботом."
                )
                return
            }

            val parts = text.trim().split(Regex("\\s+"))
            if (parts.size != 3) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Формат: /settime 07:30 09:15\nПерше значення — час публікації, друге — час закриття."
                )
                return
            }

            try {
                val startTime = LocalTime.parse(parts[1])
                val closeTime = LocalTime.parse(parts[2])

                if (!closeTime.isAfter(startTime)) {
                    sendMessage(
                        client,
                        token,
                        chatId,
                        "Час закриття має бути пізніше за час публікації."
                    )
                    return
                }

                database.setScheduleTimes(startTime, closeTime)
                sendMessage(
                    client,
                    token,
                    chatId,
                    "✅ Розклад оновлено. Публікація: ${
                        startTime.format(
                            DateTimeFormatter.ofPattern(
                                "HH:mm"
                            )
                        )
                    }, закриття: ${closeTime.format(DateTimeFormatter.ofPattern("HH:mm"))} (Europe/Tirane)."
                )
            } catch (e: DateTimeParseException) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Не вдалося розпізнати час. Використовуйте формат HH:mm, наприклад: /settime 07:30 09:15."
                )
            }
        }

        "/setrecipient" -> {
            if (fromId != adminId) {
                sendMessage(client, token, chatId, "Ця команда доступна лише адміністратору.")
                return
            }

            if (isGroupChat(message)) {
                sendMessage(client, token, chatId, "Надішліть команду в особистому чаті з ботом.")
                return
            }

            val rawIds = text.substringAfter(" ", "").trim()
            val idParts = rawIds.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
            val recipientIds = idParts.mapNotNull { it.toLongOrNull() }

            if (idParts.isEmpty() || recipientIds.size != idParts.size || recipientIds.any { it <= 0L }) {
                sendMessage(
                    client,
                    token,
                    chatId,
                    "Вкажіть один або кілька додатних Telegram ID. Приклад: /setrecipient 123456789 987654321"
                )
                return
            }

            val addedCount = database.addAdditionalRecipients(recipientIds)
            val recipients = database.getAdditionalRecipients()

            sendMessage(
                client,
                token,
                chatId,
                "Додано отримувачів: $addedCount. Загалом отримувачів: ${recipients.size}.\nID: ${
                    recipients.joinToString(
                        ", "
                    )
                }"
            )
        }

        "/clearrecipients", "/clearrecipient" -> {
            if (fromId != adminId) {
                sendMessage(client, token, chatId, "Ця команда доступна лише адміністратору.")
                return
            }

            if (isGroupChat(message)) {
                sendMessage(client, token, chatId, "Надішліть команду в особистому чаті з ботом.")
                return
            }

            val removedCount = database.clearAdditionalRecipients()
            sendMessage(
                client,
                token,
                chatId,
                "Список додаткових отримувачів очищено. Видалено: $removedCount."
            )
        }

        "/pause" -> {
            if (fromId != adminId) return

            database.setPaused(true)

            sendMessage(
                client,
                token,
                chatId,
                "⏸ Роботу бота призупинено. Розклад і списки збережено."
            )
        }

        "/resume" -> {
            if (fromId != adminId) return

            database.setPaused(false)

            sendMessage(
                client,
                token,
                chatId,
                "▶ Роботу бота відновлено."
            )

            processSchedule(
                client,
                token,
                database,
                adminId
            )
        }

        "/status" -> {
            if (fromId != adminId) return

            val paused = database.isPaused()
            val count = database.getChildrenCount(date)
            val status = database.getListStatus(date) ?: "NOT_CREATED"
            val startTime = database.getStartTime()
            val closeTime = database.getCloseTime()

            sendMessage(
                client,
                token,
                chatId,
                """
                📊 Статус LunchBot

                Стан: ${if (paused) "Призупинено" else "Працює"}
                Список: $status
                Дітей сьогодні: $count
                Час: ${botNow().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}
                Режим часу: ${if (testDateTime != null) "Тестовий" else "Реальний"}
                Публікація: ${startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}
                Закриття: ${closeTime.format(DateTimeFormatter.ofPattern("HH:mm"))}
                Часовий пояс: Europe/Tirane
                """.trimIndent()
            )
        }
    }
}

// Обробка натискання inline-кнопок
private suspend fun handleCallback(
    client: HttpClient,
    token: String,
    database: Database,
    callback: JsonObject
) {
    val callbackId = callback["id"]?.jsonPrimitive?.content ?: return
    val data = callback["data"]?.jsonPrimitive?.content ?: return
    val message = callback["message"]?.jsonObject ?: return
    val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return
    val messageId = message["message_id"]?.jsonPrimitive?.long ?: return
    val date = today()

    answerCallback(client, token, callbackId)

    if (database.getListStatus(date) != "OPEN") {
        sendMessage(client, token, chatId, "Список уже закрито. Зміни більше не приймаються.")
        return
    }

    when {
        data == "add" -> {
            val userId = callback["from"]
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.long

            if (userId != null) {
                val response = sendMessage(
                    client,
                    token,
                    chatId,
                    ADD_PROMPT,
                    buildJsonObject {
                        put("inline_keyboard", buildJsonArray {
                            add(buildJsonArray {
                                add(buildJsonObject {
                                    put("text", "✖️ Скасувати")
                                    put("callback_data", "add_cancel")
                                })
                            })
                        })
                    }.toString()
                )

                val promptMessageId = response["result"]
                    ?.jsonObject
                    ?.get("message_id")
                    ?.jsonPrimitive
                    ?.long

                if (promptMessageId != null) {
                    pendingAdd[userId] = PendingAdd(
                        chatId = chatId,
                        promptMessageId = promptMessageId,
                        expiresAt = System.currentTimeMillis() + ADD_TIMEOUT_MILLIS
                    )
                }
            }
        }

        data == "add_cancel" -> {
            val userId = callback["from"]
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.long

            val pending = userId?.let { pendingAdd[it] }
            if (userId != null && pending != null && pending.chatId == chatId) {
                pendingAdd.remove(userId)
                editMessage(
                    client,
                    token,
                    chatId,
                    pending.promptMessageId,
                    "Додавання скасовано.",
                    "{\"inline_keyboard\":[]}"
                )
            }
        }

        data == "del_open" -> {
            showDeleteMenu(client, token, database, chatId, messageId)
        }

        data.startsWith("del_page:") -> {
            val page = data.substringAfter(":").toIntOrNull() ?: 0
            showDeleteMenu(client, token, database, chatId, messageId, page)
        }

        data.startsWith("del_pick:") -> {
            val childId = data.substringAfter(":").toLongOrNull() ?: return
            val child = database.getChildren(date).find { it.id == childId } ?: return
            editMessage(
                client, token, chatId, messageId,
                "Ви впевнені, що хочете видалити ${child.name}?",
                createConfirmationKeyboard(childId)
            )
        }

        data == "del_confirm" || data.startsWith("del_confirm:") -> {
            val childId = data.substringAfter(":").toLongOrNull() ?: return
            database.removeChild(date, childId)
            updateListMessage(client, token, database, date)
        }

        data == "del_cancel" -> {
            showDeleteMenu(client, token, database, chatId, messageId)
        }

        data == "del_back" -> {
            updateListMessage(client, token, database, date)
        }
    }
}

fun main() = runBlocking {

    val token = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: error("TELEGRAM_BOT_TOKEN is not configured")

    println("Token configured: ${token.isNotBlank()}")
    println("Token length: ${token.length}")

    val adminId = System.getenv("TELEGRAM_ADMIN_ID")
        ?.toLongOrNull()
        ?: error("TELEGRAM_ADMIN_ID is not configured or invalid")

    val client = HttpClient(CIO) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 40_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 40_000
        }
    }
    val database = Database()

    try {
        database.initialize()
        database.cleanupHistory(today())

        val botInfo = telegramRequest(
            client,
            token,
            "getMe"
        )

        val botName = botInfo["result"]
            ?.jsonObject
            ?.get("username")
            ?.jsonPrimitive
            ?.content

        println("Connected to Telegram: @$botName")
        println("Timezone: Europe/Tirane")
        println("Waiting for messages...")

        var offset: Long? = null

        while (true) {
            try {
                // Перевіряємо розклад перед отриманням повідомлень
                processSchedule(
                    client,
                    token,
                    database,
                    adminId
                )

                val parameters = mutableMapOf(
                    "timeout" to "25"
                )

                offset?.let {
                    parameters["offset"] = it.toString()
                }

                val response = telegramRequest(
                    client,
                    token,
                    "getUpdates",
                    parameters
                )

                val updates = response["result"]?.jsonArray
                    ?: continue

                for (update in updates) {
                    val updateObject = update.jsonObject

                    val updateId = updateObject["update_id"]
                        ?.jsonPrimitive
                        ?.long ?: continue

                    offset = updateId + 1

                    val message = updateObject["message"]?.jsonObject

                    if (message != null) {
                        handleMessage(
                            client,
                            token,
                            database,
                            message,
                            adminId
                        )
                    }

                    val callback = updateObject["callback_query"]
                        ?.jsonObject

                    if (callback != null) {
                        handleCallback(
                            client,
                            token,
                            database,
                            callback
                        )
                    }
                }

            } catch (e: Exception) {
                println("Error: ${e::class.simpleName}")
                delay(3000.milliseconds)
            }
        }

    } finally {
        database.close()
        client.close()
    }
}
