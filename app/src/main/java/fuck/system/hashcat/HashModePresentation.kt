package fuck.system.hashcat

import android.content.Context
import java.util.Locale

object HashModePresentation
{
    data class CategoryPresentation(
        val titleRes: Int,
        val hintRes: Int,
        val iconRes: Int
    )

    fun categoryPresentation(category: String): CategoryPresentation
    {
        return when (category)
        {
            "Raw Hash" -> CategoryPresentation(
                R.string.mode_group_raw_hash,
                R.string.mode_group_raw_hash_hint,
                R.drawable.ic_mode_hash
            )

            "Raw Hash salted and/or iterated" -> CategoryPresentation(
                R.string.mode_group_salted_hash,
                R.string.mode_group_salted_hash_hint,
                R.drawable.ic_mode_hash
            )

            "Raw Hash authenticated" -> CategoryPresentation(
                R.string.mode_group_authenticated_hash,
                R.string.mode_group_authenticated_hash_hint,
                R.drawable.ic_mode_hash
            )

            "Raw Cipher, Known-plaintext attack" -> CategoryPresentation(
                R.string.mode_group_cipher,
                R.string.mode_group_cipher_hint,
                R.drawable.ic_mode_lock
            )

            "Generic KDF" -> CategoryPresentation(
                R.string.mode_group_kdf,
                R.string.mode_group_kdf_hint,
                R.drawable.ic_mode_key
            )

            "Network Protocol" -> CategoryPresentation(
                R.string.mode_group_network_protocol,
                R.string.mode_group_network_protocol_hint,
                R.drawable.ic_mode_network
            )

            "Forums, CMS, E-Commerce" -> CategoryPresentation(
                R.string.mode_group_forum,
                R.string.mode_group_forum_hint,
                R.drawable.ic_mode_app
            )

            "Database Server" -> CategoryPresentation(
                R.string.mode_group_database_server,
                R.string.mode_group_database_server_hint,
                R.drawable.ic_mode_database
            )

            "FTP, HTTP, SMTP, LDAP Server" -> CategoryPresentation(
                R.string.mode_group_network_server,
                R.string.mode_group_network_server_hint,
                R.drawable.ic_mode_network
            )

            "Raw Checksum" -> CategoryPresentation(
                R.string.mode_group_checksum,
                R.string.mode_group_checksum_hint,
                R.drawable.ic_mode_hash
            )

            "Operating System" -> CategoryPresentation(
                R.string.mode_group_os,
                R.string.mode_group_os_hint,
                R.drawable.ic_mode_system
            )

            "Enterprise Application Software (EAS)" -> CategoryPresentation(
                R.string.mode_group_enterprise,
                R.string.mode_group_enterprise_hint,
                R.drawable.ic_mode_app
            )

            "Archive" -> CategoryPresentation(
                R.string.mode_group_archive,
                R.string.mode_group_archive_hint,
                R.drawable.ic_mode_document
            )

            "Full-Disk Encryption (FDE)" -> CategoryPresentation(
                R.string.mode_group_fde,
                R.string.mode_group_fde_hint,
                R.drawable.ic_mode_lock
            )

            "File-Based Encryption (FBE)" -> CategoryPresentation(
                R.string.mode_group_fbe,
                R.string.mode_group_fbe_hint,
                R.drawable.ic_mode_lock
            )

            "Document" -> CategoryPresentation(
                R.string.mode_group_document,
                R.string.mode_group_document_hint,
                R.drawable.ic_mode_document
            )

            "Password Manager" -> CategoryPresentation(
                R.string.mode_group_password_manager,
                R.string.mode_group_password_manager_hint,
                R.drawable.ic_mode_key
            )

            "One-Time Password" -> CategoryPresentation(
                R.string.mode_group_otp,
                R.string.mode_group_otp_hint,
                R.drawable.ic_mode_key
            )

            "Plaintext" -> CategoryPresentation(
                R.string.mode_group_plaintext,
                R.string.mode_group_plaintext_hint,
                R.drawable.ic_mode_hash
            )

            "Framework" -> CategoryPresentation(
                R.string.mode_group_framework,
                R.string.mode_group_framework_hint,
                R.drawable.ic_mode_app
            )

            "Private Key" -> CategoryPresentation(
                R.string.mode_group_private_key,
                R.string.mode_group_private_key_hint,
                R.drawable.ic_mode_key
            )

            "Instant Messaging Service" -> CategoryPresentation(
                R.string.mode_group_messaging,
                R.string.mode_group_messaging_hint,
                R.drawable.ic_mode_chat
            )

            "Cryptocurrency Wallet" -> CategoryPresentation(
                R.string.mode_group_wallet,
                R.string.mode_group_wallet_hint,
                R.drawable.ic_mode_wallet
            )

            "Application Database" -> CategoryPresentation(
                R.string.mode_group_app_database,
                R.string.mode_group_app_database_hint,
                R.drawable.ic_mode_database
            )

            else -> CategoryPresentation(
                R.string.mode_group_other,
                R.string.mode_group_other_hint,
                R.drawable.ic_mode_app
            )
        }
    }

    fun categoryOrder(): List<String>
    {
        return CATEGORY_ORDER
    }

    fun categoryTitle(context: Context, category: String): String
    {
        return context.getString(categoryPresentation(category).titleRes)
    }

    fun categoryHint(context: Context, category: String): String
    {
        return context.getString(categoryPresentation(category).hintRes)
    }

    fun modeDescription(
        context: Context,
        mode: HashcatRunner.HashMode
    ): String
    {
        val name = mode.name
        val lowerName = name.lowercase(Locale.ROOT)
        val russian = isRussianLocale(context)

        return when
        {
            lowerName.contains("wpa") -> if (russian)
            {
                "Wi‑Fi/WiFi WPA/WPA2: PMKID, EAPOL handshake или готовый PMK."
            }
            else
            {
                "Wi‑Fi/WiFi WPA/WPA2: PMKID, EAPOL handshake or prepared PMK."
            }

            lowerName.contains("office") -> if (russian)
            {
                "Документы Microsoft Office: Word, Excel, PowerPoint и родственные форматы."
            }
            else
            {
                "Microsoft Office documents: Word, Excel, PowerPoint and related formats."
            }

            lowerName.contains("pdf") -> if (russian)
            {
                "Защищённый PDF-документ."
            }
            else
            {
                "Protected PDF document."
            }

            lowerName.contains("zip") ||
                lowerName.contains("rar") ||
                lowerName.contains("7-zip") ||
                lowerName.contains("pkzip") -> if (russian)
            {
                "Защищённый архив или контейнер: $name."
            }
            else
            {
                "Protected archive or container: $name."
            }

            lowerName.contains("mysql") ||
                lowerName.contains("mssql") ||
                lowerName.contains("postgres") ||
                lowerName.contains("oracle") ||
                lowerName.contains("mongodb") ||
                lowerName.contains("sqlite") -> if (russian)
            {
                "База данных: хэш или учётные данные $name."
            }
            else
            {
                "Database hash or credentials: $name."
            }

            lowerName.contains("bitcoin") ||
                lowerName.contains("ethereum") ||
                lowerName.contains("wallet") ||
                lowerName.contains("metamask") -> if (russian)
            {
                "Криптокошелёк или защищённое wallet-хранилище: $name."
            }
            else
            {
                "Cryptocurrency wallet or protected wallet storage: $name."
            }

            lowerName.contains("ssh") ||
                lowerName.contains("pgp") ||
                lowerName.contains("private key") -> if (russian)
            {
                "Защищённый приватный ключ: $name."
            }
            else
            {
                "Protected private key: $name."
            }

            else -> genericModeDescription(mode, russian)
        }
    }

    fun modeMatchesQuery(
        context: Context,
        mode: HashcatRunner.HashMode,
        query: String
    ): Boolean
    {
        val presentation = categoryPresentation(mode.category)
        val haystack = listOf(
            mode.mode.toString(),
            mode.name,
            mode.category,
            context.getString(presentation.titleRes),
            context.getString(presentation.hintRes),
            modeDescription(context, mode),
            modeSearchAliases(mode)
        ).joinToString(" ")
        val normalizedHaystack = normalizeText(haystack)
        val normalizedQuery = normalizeText(query)
        val compactHaystack = compactSearchText(normalizedHaystack)
        val compactQuery = compactSearchText(normalizedQuery)

        if (normalizedHaystack.contains(normalizedQuery))
        {
            return true
        }

        if (compactQuery.isNotEmpty() && compactHaystack.contains(compactQuery))
        {
            return true
        }

        return normalizedQuery.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .all { token ->
                compactHaystack.contains(compactSearchText(token))
            }
    }

    private fun genericModeDescription(
        mode: HashcatRunner.HashMode,
        russian: Boolean
    ): String
    {
        return if (russian)
        {
            when (mode.category)
            {
                "Raw Hash" -> "Несолёный хэш-алгоритм: ${mode.name}."
                "Raw Hash salted and/or iterated" -> "Хэш с солью, итерациями или преобразованием: ${mode.name}."
                "Raw Hash authenticated" -> "Аутентифицированный хэш или HMAC-схема: ${mode.name}."
                "Raw Checksum" -> "Контрольная сумма или близкий формат проверки данных: ${mode.name}."
                "Raw Cipher, Known-plaintext attack" -> "Шифр для атаки по известному открытому тексту: ${mode.name}."
                "Generic KDF" -> "Функция выработки ключа: ${mode.name}."
                "Network Protocol" -> "Данные сетевого протокола или handshake: ${mode.name}."
                "Operating System" -> "Парольный формат операционной системы: ${mode.name}."
                "Database Server", "Application Database" -> "База данных или локальное хранилище приложения: ${mode.name}."
                "FTP, HTTP, SMTP, LDAP Server" -> "Серверная схема аутентификации: ${mode.name}."
                "Enterprise Application Software (EAS)" -> "Формат корпоративного приложения: ${mode.name}."
                "Full-Disk Encryption (FDE)" -> "Полнодисковое шифрование или зашифрованный том: ${mode.name}."
                "File-Based Encryption (FBE)" -> "Файловое шифрование или контейнер: ${mode.name}."
                "Document" -> "Защищённый документ: ${mode.name}."
                "Password Manager" -> "Хранилище менеджера паролей: ${mode.name}."
                "Archive" -> "Защищённый архив: ${mode.name}."
                "Forums, CMS, E-Commerce" -> "Формат пароля веб-платформы, CMS или магазина: ${mode.name}."
                "One-Time Password" -> "Одноразовый пароль или OTP/TOTP-формат: ${mode.name}."
                "Plaintext" -> "Специальный режим для открытого текста: ${mode.name}."
                "Framework" -> "Формат аутентификации фреймворка: ${mode.name}."
                "Private Key" -> "Защищённый приватный ключ: ${mode.name}."
                "Instant Messaging Service" -> "Формат учётных данных мессенджера: ${mode.name}."
                "Cryptocurrency Wallet" -> "Криптокошелёк или wallet-хранилище: ${mode.name}."
                else -> "Формат Hashcat: ${mode.name}."
            }
        }
        else
        {
            when (mode.category)
            {
                "Raw Hash" -> "Unsalted hash algorithm: ${mode.name}."
                "Raw Hash salted and/or iterated" -> "Salted, iterated or transformed hash: ${mode.name}."
                "Raw Hash authenticated" -> "Authenticated hash or HMAC scheme: ${mode.name}."
                "Raw Checksum" -> "Checksum or related data-verification format: ${mode.name}."
                "Raw Cipher, Known-plaintext attack" -> "Cipher mode for a known-plaintext attack: ${mode.name}."
                "Generic KDF" -> "Key-derivation function: ${mode.name}."
                "Network Protocol" -> "Network protocol data or handshake: ${mode.name}."
                "Operating System" -> "Operating-system password format: ${mode.name}."
                "Database Server", "Application Database" -> "Database or local application storage: ${mode.name}."
                "FTP, HTTP, SMTP, LDAP Server" -> "Server authentication scheme: ${mode.name}."
                "Enterprise Application Software (EAS)" -> "Enterprise application format: ${mode.name}."
                "Full-Disk Encryption (FDE)" -> "Full-disk encryption or encrypted volume: ${mode.name}."
                "File-Based Encryption (FBE)" -> "File encryption or encrypted container: ${mode.name}."
                "Document" -> "Protected document: ${mode.name}."
                "Password Manager" -> "Password-manager vault: ${mode.name}."
                "Archive" -> "Protected archive: ${mode.name}."
                "Forums, CMS, E-Commerce" -> "Password format from a web platform, CMS or shop: ${mode.name}."
                "One-Time Password" -> "One-time password or OTP/TOTP format: ${mode.name}."
                "Plaintext" -> "Special mode for plaintext data: ${mode.name}."
                "Framework" -> "Framework authentication format: ${mode.name}."
                "Private Key" -> "Protected private key: ${mode.name}."
                "Instant Messaging Service" -> "Messaging-service credential format: ${mode.name}."
                "Cryptocurrency Wallet" -> "Cryptocurrency wallet or protected wallet storage: ${mode.name}."
                else -> "Hashcat format: ${mode.name}."
            }
        }
    }

    private fun modeSearchAliases(mode: HashcatRunner.HashMode): String
    {
        val lowerName = mode.name.lowercase(Locale.ROOT)
        val aliases = ArrayList<String>()

        when (mode.category)
        {
            "Database Server",
            "Application Database" -> aliases += "database db sql база данных базы данных бд субд"
            "Network Protocol" -> aliases += "network protocol сеть протокол handshake рукопожатие"
            "Document" -> aliases += "document doc документы документ"
            "Archive" -> aliases += "archive архив zip rar 7zip 7-zip"
            "Operating System" -> aliases += "os operating system windows linux unix macos операционная система пароль"
            "Cryptocurrency Wallet" -> aliases += "wallet crypto cryptocurrency кошелек кошелёк криптокошелек криптокошелёк"
        }

        if (lowerName.contains("wpa"))
        {
            aliases += "wifi wi-fi wi fi wlan wireless вай фай вайфай беспроводной сеть wpa2 wpa3 pmkid eapol"
        }
        if (lowerName.contains("office"))
        {
            aliases += "microsoft office word excel xls xlsx powerpoint ppt pptx doc docx офис эксель ворд документы"
        }
        if (lowerName.contains("pdf"))
        {
            aliases += "pdf document документ"
        }
        if (lowerName.contains("mysql") ||
            lowerName.contains("mssql") ||
            lowerName.contains("postgres") ||
            lowerName.contains("oracle") ||
            lowerName.contains("sqlite") ||
            lowerName.contains("mongodb"))
        {
            aliases += "database db sql база данных бд субд"
        }

        return aliases.joinToString(" ")
    }

    private fun isRussianLocale(context: Context): Boolean
    {
        return context.resources.configuration.locales[0].language == "ru"
    }

    private fun normalizeText(text: String): String
    {
        return text
            .lowercase(Locale.getDefault())
            .replace('ё', 'е')
            .replace('‑', '-')
            .replace('–', '-')
            .replace('—', '-')
    }

    private fun compactSearchText(text: String): String
    {
        return text.filter { char ->
            char.isLetterOrDigit()
        }
    }

    private val CATEGORY_ORDER = listOf(
        "Raw Hash",
        "Raw Hash salted and/or iterated",
        "Raw Hash authenticated",
        "Raw Checksum",
        "Raw Cipher, Known-plaintext attack",
        "Generic KDF",
        "Network Protocol",
        "Operating System",
        "Database Server",
        "FTP, HTTP, SMTP, LDAP Server",
        "Enterprise Application Software (EAS)",
        "Full-Disk Encryption (FDE)",
        "File-Based Encryption (FBE)",
        "Document",
        "Password Manager",
        "Archive",
        "Forums, CMS, E-Commerce",
        "One-Time Password",
        "Plaintext",
        "Framework",
        "Private Key",
        "Instant Messaging Service",
        "Cryptocurrency Wallet",
        "Application Database",
        "Undefined"
    )
}
