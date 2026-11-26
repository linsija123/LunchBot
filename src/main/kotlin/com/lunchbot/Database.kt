package com.lunchbot

import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

data class ChildEntry(
    val id: Long,
    val name: String
)

class Database(
    private val filePath: String = "lunchbot.db"
) : AutoCloseable {

    private lateinit var connection: Connection

    fun initialize() {
        connection = DriverManager.getConnection("jdbc:sqlite:$filePath")

        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS daily_lists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    list_date TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    telegram_chat_id INTEGER,
                    telegram_message_id INTEGER,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    closed_at TEXT,
                    admin_notified INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS children_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    daily_list_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (daily_list_id)
                        REFERENCES daily_lists(id)
                        ON DELETE CASCADE,
                    UNIQUE (daily_list_id, normalized_name)
                )
                """.trimIndent()
            )

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS bot_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    admin_id INTEGER,
                    is_paused INTEGER NOT NULL DEFAULT 0,
                    timezone TEXT NOT NULL DEFAULT 'Europe/Tirane',
                    target_chat_id INTEGER,
                    start_time TEXT NOT NULL DEFAULT '07:00',
                    close_time TEXT NOT NULL DEFAULT '09:20'
                )
                """.trimIndent()
            )

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS default_children (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS additional_recipients (
                    chat_id INTEGER PRIMARY KEY,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )

            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS recipient_notifications (
                    list_date TEXT NOT NULL,
                    recipient_chat_id INTEGER NOT NULL,
                    notified_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (list_date, recipient_chat_id),
                    FOREIGN KEY (list_date)
                        REFERENCES daily_lists(list_date)
                        ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        // Оновлення бази даних, створеної попередньою версією
        ensureColumn(
            "daily_lists",
            "admin_notified",
            "INTEGER NOT NULL DEFAULT 0"
        )

        ensureColumn(
            "bot_settings",
            "target_chat_id",
            "INTEGER"
        )

        ensureColumn(
            "bot_settings",
            "start_time",
            "TEXT NOT NULL DEFAULT '07:00'"
        )

        ensureColumn(
            "bot_settings",
            "close_time",
            "TEXT NOT NULL DEFAULT '09:20'"
        )

        connection.createStatement().use {
            it.executeUpdate(
                """
                INSERT OR IGNORE INTO bot_settings (id)
                VALUES (1)
                """.trimIndent()
            )
        }

        println("Database initialized successfully!")
    }

    private fun ensureColumn(
        table: String,
        column: String,
        definition: String
    ) {
        val columns = mutableSetOf<String>()

        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    columns.add(result.getString("name"))
                }
            }
        }

        if (column !in columns) {
            connection.createStatement().use {
                it.execute("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }
    }

    private fun normalizeName(name: String): String {
        return name
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
    }

    // Створення списку з перенесенням даних з останнього закритого списку
    fun createDailyList(date: LocalDate, cleanupHistory: Boolean = true): Long {
        connection.autoCommit = false

        try {
            val inserted = connection.prepareStatement(
                """
                INSERT OR IGNORE INTO daily_lists (list_date)
                VALUES (?)
                """.trimIndent()
            ).use {
                it.setString(1, date.toString())
                it.executeUpdate()
            }

            val listId = connection.prepareStatement(
                """
                SELECT id FROM daily_lists
                WHERE list_date = ?
                """.trimIndent()
            ).use {
                it.setString(1, date.toString())

                it.executeQuery().use { result ->
                    check(result.next())
                    result.getLong("id")
                }
            }

            // Якщо список уже існує, не переносимо записи повторно,
            // але за потреби все одно очищуємо застарілу історію.
            if (inserted == 0) {
                if (cleanupHistory) {
                    cleanupDailyHistory(date, listId)
                }
                connection.commit()
                return listId
            }

            // Пошук останнього успішно закритого списку
            val previousListId = connection.prepareStatement(
                """
                SELECT id FROM daily_lists
                WHERE status = 'CLOSED'
                  AND list_date < ?
                ORDER BY list_date DESC
                LIMIT 1
                """.trimIndent()
            ).use {
                it.setString(1, date.toString())

                it.executeQuery().use { result ->
                    if (result.next()) result.getLong("id") else null
                }
            }

            if (previousListId != null) {
                connection.prepareStatement(
                    """
                    INSERT INTO children_entries (
                        daily_list_id,
                        name,
                        normalized_name
                    )
                    SELECT ?, name, normalized_name
                    FROM children_entries
                    WHERE daily_list_id = ?
                    """.trimIndent()
                ).use {
                    it.setLong(1, listId)
                    it.setLong(2, previousListId)
                    it.executeUpdate()
                }
            } else {
                // Якщо закритих списків ще немає, використовуємо початковий список
                connection.prepareStatement(
                    """
                    INSERT INTO children_entries (
                        daily_list_id,
                        name,
                        normalized_name
                    )
                    SELECT ?, name, normalized_name
                    FROM default_children
                    """.trimIndent()
                ).use {
                    it.setLong(1, listId)
                    it.executeUpdate()
                }
            }

            // Зберігаємо лише поточний список і останній закритий список,
            // якщо поточний список ще відкритий.
            if (cleanupHistory) {
                cleanupDailyHistory(date, listId)
            }

            connection.commit()
            return listId

        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun cleanupHistory(date: LocalDate) {
        connection.autoCommit = false
        try {
            val currentListId = connection.prepareStatement(
                "SELECT id FROM daily_lists WHERE list_date = ?"
            ).use { statement ->
                statement.setString(1, date.toString())
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong("id") else null
                }
            }

            cleanupDailyHistory(date, currentListId)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun cleanupDailyHistory(date: LocalDate, currentListId: Long?) {
        val currentStatus = currentListId?.let { listId ->
            connection.prepareStatement(
                "SELECT status FROM daily_lists WHERE id = ?"
            ).use { statement ->
                statement.setLong(1, listId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString("status") else null
                }
            }
        }

        val idsToKeep = mutableListOf<Long>()
        currentListId?.let(idsToKeep::add)

        if (currentStatus != "CLOSED") {
            val lastClosedListId = connection.prepareStatement(
                """
                SELECT id FROM daily_lists
                WHERE status = 'CLOSED'
                  AND list_date < ?
                ORDER BY list_date DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, date.toString())
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong("id") else null
                }
            }
            lastClosedListId?.let(idsToKeep::add)
        }

        if (idsToKeep.isEmpty()) {
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM daily_lists")
            }
            return
        }

        val placeholders = idsToKeep.joinToString(",") { "?" }
        connection.prepareStatement(
            "DELETE FROM daily_lists WHERE id NOT IN ($placeholders)"
        ).use { statement ->
            idsToKeep.forEachIndexed { index, id ->
                statement.setLong(index + 1, id)
            }
            statement.executeUpdate()
        }
    }

    fun addChildren(
        date: LocalDate,
        names: List<String>,
        cleanupHistory: Boolean = true
    ): Int {
        val listId = createDailyList(date, cleanupHistory)

        if (!isListOpen(listId)) return 0

        var addedCount = 0

        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO children_entries (
                daily_list_id,
                name,
                normalized_name
            )
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->

            for (rawName in names) {
                val name = rawName
                    .trim()
                    .replace(Regex("\\s+"), " ")

                if (name.isBlank()) continue

                statement.setLong(1, listId)
                statement.setString(2, name)
                statement.setString(3, normalizeName(name))

                addedCount += statement.executeUpdate()
            }
        }

        return addedCount
    }

    fun getChildren(date: LocalDate): List<ChildEntry> {
        val resultList = mutableListOf<ChildEntry>()

        connection.prepareStatement(
            """
            SELECT ce.id, ce.name
            FROM children_entries ce
            JOIN daily_lists dl ON ce.daily_list_id = dl.id
            WHERE dl.list_date = ?
            ORDER BY ce.id
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())

            it.executeQuery().use { result ->
                while (result.next()) {
                    resultList.add(
                        ChildEntry(
                            id = result.getLong("id"),
                            name = result.getString("name")
                        )
                    )
                }
            }
        }

        return resultList
    }

    fun getChildrenCount(date: LocalDate): Int {
        return getChildren(date).size
    }

    fun removeChild(
        date: LocalDate,
        childId: Long
    ): Boolean {
        val listId = getDailyListId(date) ?: return false

        if (!isListOpen(listId)) return false

        connection.prepareStatement(
            """
            DELETE FROM children_entries
            WHERE id = ? AND daily_list_id = ?
            """.trimIndent()
        ).use {
            it.setLong(1, childId)
            it.setLong(2, listId)
            return it.executeUpdate() > 0
        }
    }

    fun closeDailyList(date: LocalDate): Boolean {
        connection.prepareStatement(
            """
            UPDATE daily_lists
            SET status = 'CLOSED',
                closed_at = CURRENT_TIMESTAMP
            WHERE list_date = ?
              AND status = 'OPEN'
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())
            return it.executeUpdate() > 0
        }
    }

    fun getListStatus(date: LocalDate): String? {
        connection.prepareStatement(
            """
            SELECT status FROM daily_lists
            WHERE list_date = ?
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())

            it.executeQuery().use { result ->
                return if (result.next()) {
                    result.getString("status")
                } else null
            }
        }
    }

    private fun getDailyListId(date: LocalDate): Long? {
        connection.prepareStatement(
            """
            SELECT id FROM daily_lists
            WHERE list_date = ?
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())

            it.executeQuery().use { result ->
                return if (result.next()) result.getLong("id") else null
            }
        }
    }

    private fun isListOpen(listId: Long): Boolean {
        connection.prepareStatement(
            """
            SELECT status FROM daily_lists
            WHERE id = ?
            """.trimIndent()
        ).use {
            it.setLong(1, listId)

            it.executeQuery().use { result ->
                return result.next() && result.getString("status") == "OPEN"
            }
        }
    }

    // Збереження основного повідомлення Telegram
    fun saveTelegramMessage(
        date: LocalDate,
        chatId: Long,
        messageId: Long
    ) {
        connection.prepareStatement(
            """
            UPDATE daily_lists
            SET telegram_chat_id = ?,
                telegram_message_id = ?
            WHERE list_date = ?
            """.trimIndent()
        ).use {
            it.setLong(1, chatId)
            it.setLong(2, messageId)
            it.setString(3, date.toString())
            it.executeUpdate()
        }
    }

    fun getTelegramMessage(date: LocalDate): Pair<Long, Long>? {
        connection.prepareStatement(
            """
            SELECT telegram_chat_id, telegram_message_id
            FROM daily_lists
            WHERE list_date = ?
              AND telegram_chat_id IS NOT NULL
              AND telegram_message_id IS NOT NULL
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())

            it.executeQuery().use { result ->
                return if (result.next()) {
                    Pair(
                        result.getLong("telegram_chat_id"),
                        result.getLong("telegram_message_id")
                    )
                } else null
            }
        }
    }

    // Початковий список дітей
    fun setDefaultChildren(names: List<String>): Int {
        connection.autoCommit = false

        try {
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM default_children")
            }

            var count = 0

            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO default_children (
                    name,
                    normalized_name
                )
                VALUES (?, ?)
                """.trimIndent()
            ).use { statement ->
                for (rawName in names) {
                    val name = rawName
                        .trim()
                        .replace(Regex("\\s+"), " ")

                    if (name.isBlank()) continue

                    statement.setString(1, name)
                    statement.setString(2, normalizeName(name))
                    count += statement.executeUpdate()
                }
            }

            connection.commit()
            return count

        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun getDefaultChildren(): List<String> {
        val names = mutableListOf<String>()

        connection.createStatement().use {
            it.executeQuery(
                "SELECT name FROM default_children ORDER BY id"
            ).use { result ->
                while (result.next()) {
                    names.add(result.getString("name"))
                }
            }
        }

        return names
    }

    // Керування паузою бота
    fun setPaused(paused: Boolean) {
        connection.prepareStatement(
            """
            UPDATE bot_settings
            SET is_paused = ?
            WHERE id = 1
            """.trimIndent()
        ).use {
            it.setInt(1, if (paused) 1 else 0)
            it.executeUpdate()
        }
    }

    fun isPaused(): Boolean {
        connection.createStatement().use {
            it.executeQuery(
                "SELECT is_paused FROM bot_settings WHERE id = 1"
            ).use { result ->
                return result.next() && result.getInt("is_paused") == 1
            }
        }
    }

    // Час публікації та закриття щоденного списку
    fun setScheduleTimes(startTime: LocalTime, closeTime: LocalTime) {
        connection.prepareStatement(
            """
            UPDATE bot_settings
            SET start_time = ?, close_time = ?
            WHERE id = 1
            """.trimIndent()
        ).use {
            it.setString(1, startTime.toString())
            it.setString(2, closeTime.toString())
            it.executeUpdate()
        }
    }

    fun getStartTime(): LocalTime = getScheduleTime("start_time", LocalTime.of(7, 0))

    fun getCloseTime(): LocalTime = getScheduleTime("close_time", LocalTime.of(9, 20))

    private fun getScheduleTime(column: String, default: LocalTime): LocalTime {
        require(column == "start_time" || column == "close_time")
        connection.createStatement().use {
            it.executeQuery("SELECT $column FROM bot_settings WHERE id = 1").use { result ->
                if (!result.next()) return default
                return runCatching { LocalTime.parse(result.getString(column)) }
                    .getOrDefault(default)
            }
        }
    }

    // Чат для публікації списків
    fun setTargetChatId(chatId: Long) {
        connection.prepareStatement(
            """
            UPDATE bot_settings
            SET target_chat_id = ?
            WHERE id = 1
            """.trimIndent()
        ).use {
            it.setLong(1, chatId)
            it.executeUpdate()
        }
    }

    fun getTargetChatId(): Long? {
        connection.createStatement().use {
            it.executeQuery(
                "SELECT target_chat_id FROM bot_settings WHERE id = 1"
            ).use { result ->
                return if (result.next()) {
                    result.getLong("target_chat_id").let {
                        if (result.wasNull()) null else it
                    }
                } else null
            }
        }
    }

    // Додаткові отримувачі підсумкового звіту
    fun addAdditionalRecipients(chatIds: List<Long>): Int {
        var addedCount = 0

        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO additional_recipients (chat_id)
            VALUES (?)
            """.trimIndent()
        ).use { statement ->
            for (chatId in chatIds.distinct()) {
                statement.setLong(1, chatId)
                addedCount += statement.executeUpdate()
            }
        }

        return addedCount
    }

    fun getAdditionalRecipients(): List<Long> {
        val recipients = mutableListOf<Long>()

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT chat_id FROM additional_recipients ORDER BY created_at, chat_id"
            ).use { result ->
                while (result.next()) {
                    recipients.add(result.getLong("chat_id"))
                }
            }
        }

        return recipients
    }

    fun clearAdditionalRecipients(): Int {
        connection.createStatement().use {
            return it.executeUpdate("DELETE FROM additional_recipients")
        }
    }

    fun isRecipientNotified(date: LocalDate, chatId: Long): Boolean {
        connection.prepareStatement(
            """
            SELECT 1 FROM recipient_notifications
            WHERE list_date = ? AND recipient_chat_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, date.toString())
            statement.setLong(2, chatId)
            statement.executeQuery().use { result ->
                return result.next()
            }
        }
    }

    fun markRecipientNotified(date: LocalDate, chatId: Long) {
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO recipient_notifications (list_date, recipient_chat_id)
            VALUES (?, ?)
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())
            it.setLong(2, chatId)
            it.executeUpdate()
        }
    }

    // Контроль надсилання підсумкового звіту
    fun isAdminNotified(date: LocalDate): Boolean {
        connection.prepareStatement(
            """
            SELECT admin_notified FROM daily_lists
            WHERE list_date = ?
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())

            it.executeQuery().use { result ->
                return result.next() && result.getInt("admin_notified") == 1
            }
        }
    }

    fun markAdminNotified(date: LocalDate) {
        connection.prepareStatement(
            """
            UPDATE daily_lists
            SET admin_notified = 1
            WHERE list_date = ?
            """.trimIndent()
        ).use {
            it.setString(1, date.toString())
            it.executeUpdate()
        }
    }

    override fun close() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
    }
}
