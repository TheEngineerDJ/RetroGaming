package com.retrovault.dat.xml

import java.io.Reader

/** One thing the scanner found. */
sealed interface XmlEvent {
    data class StartElement(val name: String, val attributes: Map<String, String>) : XmlEvent

    data class EndElement(val name: String) : XmlEvent

    data class Text(val text: String) : XmlEvent

    data object EndDocument : XmlEvent
}

/**
 * Hard bounds on untrusted XML.
 *
 * SECURITY_SPEC.md section 4/5: bound input sizes, and malformed input must
 * fail as data rather than crash the application.
 */
data class XmlLimits(
    val maxDepth: Int = 64,
    val maxNameLength: Int = 256,
    val maxAttributeCount: Int = 64,
    val maxAttributeValueLength: Int = 8_192,
    val maxTextLength: Int = 65_536,
)

/** The document is not well formed. Carries where, so a report can say so. */
class MalformedXmlException(
    message: String,
    val characterOffset: Long,
) : Exception("$message (at character $characterOffset)")

/**
 * A streaming, pull-style XML scanner.
 *
 * Written by hand rather than delegating to StAX or `XmlPullParser` for three
 * reasons:
 *
 * 1. StAX does not exist on Android and `XmlPullParser` is awkward off it, so a
 *    shared parser removes a platform divergence in identity-critical code.
 * 2. It is XXE-proof by construction: there is no code path that resolves an
 *    external entity, opens a URL or expands a general entity, so the classic
 *    entity-expansion and external-entity attacks have nothing to attach to
 *    (SECURITY_SPEC.md section 4).
 * 3. Every limit is explicit and testable.
 *
 * It is not a general-purpose XML processor. It handles what DAT files
 * actually contain: elements, attributes, text, CDATA, comments, processing
 * instructions and a skipped DOCTYPE.
 */
class XmlPullScanner(
    private val reader: Reader,
    private val limits: XmlLimits = XmlLimits(),
) {
    /**
     * Characters read and given back.
     *
     * A deque rather than a single slot: recognising `<!--` versus `<![CDATA[`
     * versus `<!DOCTYPE` needs several characters of lookahead, and a failed
     * match must restore every one of them.
     */
    private val pushback = ArrayDeque<Int>()
    private var offset: Long = 0
    private val openElements = ArrayDeque<String>()
    private var pendingSelfClose: String? = null
    private var finished = false

    /** Reads the next event. Returns [XmlEvent.EndDocument] once, at the end. */
    fun next(): XmlEvent {
        pendingSelfClose?.let { name ->
            pendingSelfClose = null
            openElements.removeLast()
            return XmlEvent.EndElement(name)
        }
        if (finished) return XmlEvent.EndDocument

        while (true) {
            val character = read()
            if (character == EOF) {
                finished = true
                if (openElements.isNotEmpty()) {
                    throw MalformedXmlException(
                        "Unclosed element <${openElements.last()}> at end of document",
                        offset,
                    )
                }
                return XmlEvent.EndDocument
            }
            if (character.toChar() != '<') {
                unread(character)
                val text = readText()
                if (text.isBlank()) continue
                return XmlEvent.Text(text)
            }
            when (val marker = read()) {
                EOF -> throw MalformedXmlException("Document ends inside a tag", offset)

                '?'.code -> skipUntil("?>")

                '!'.code -> when {
                    consumeIfMatches("--") -> skipUntil("-->")
                    consumeIfMatches("[CDATA[") -> {
                        val text = readUntil("]]>")
                        if (text.isNotBlank()) return XmlEvent.Text(text)
                    }

                    // A DOCTYPE is skipped without being interpreted. Nothing
                    // here declares or expands entities, which is what makes
                    // entity-expansion attacks inapplicable.
                    else -> skipDeclaration()
                }

                '/'.code -> return readEndElement()

                else -> {
                    unread(marker)
                    return readStartElement()
                }
            }
        }
    }

    private fun readStartElement(): XmlEvent.StartElement {
        val name = readName()
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            skipWhitespace()
            when (val character = read()) {
                EOF -> throw MalformedXmlException("Document ends inside <$name>", offset)

                '>'.code -> {
                    push(name)
                    return XmlEvent.StartElement(name, attributes)
                }

                '/'.code -> {
                    expect('>')
                    push(name)
                    pendingSelfClose = name
                    return XmlEvent.StartElement(name, attributes)
                }

                else -> {
                    unread(character)
                    val attributeName = readName()
                    skipWhitespace()
                    expect('=')
                    skipWhitespace()
                    val value = readAttributeValue()
                    if (attributes.size >= limits.maxAttributeCount) {
                        throw MalformedXmlException(
                            "Element <$name> exceeds ${limits.maxAttributeCount} attributes",
                            offset,
                        )
                    }
                    // Last one wins; DATs occasionally repeat an attribute.
                    attributes[attributeName] = value
                }
            }
        }
    }

    private fun readEndElement(): XmlEvent.EndElement {
        val name = readName()
        skipWhitespace()
        expect('>')
        val open = openElements.removeLastOrNull()
            ?: throw MalformedXmlException("Closing tag </$name> with no open element", offset)
        if (open != name) {
            throw MalformedXmlException("Closing tag </$name> does not match <$open>", offset)
        }
        return XmlEvent.EndElement(name)
    }

    private fun push(name: String) {
        if (openElements.size >= limits.maxDepth) {
            throw MalformedXmlException("Nesting deeper than ${limits.maxDepth} elements", offset)
        }
        openElements.addLast(name)
    }

    private fun readName(): String {
        val builder = StringBuilder()
        while (true) {
            val character = read()
            if (character == EOF) throw MalformedXmlException("Document ends inside a name", offset)
            val char = character.toChar()
            if (char.isWhitespace() || char == '>' || char == '/' || char == '=') {
                unread(character)
                break
            }
            builder.append(char)
            if (builder.length > limits.maxNameLength) {
                throw MalformedXmlException("Name longer than ${limits.maxNameLength} characters", offset)
            }
        }
        if (builder.isEmpty()) throw MalformedXmlException("Empty element or attribute name", offset)
        return builder.toString()
    }

    private fun readAttributeValue(): String {
        val quote = read()
        if (quote != '"'.code && quote != '\''.code) {
            throw MalformedXmlException("Attribute value is not quoted", offset)
        }
        val builder = StringBuilder()
        while (true) {
            val character = read()
            if (character == EOF) throw MalformedXmlException("Document ends inside an attribute", offset)
            if (character == quote) break
            builder.append(character.toChar())
            if (builder.length > limits.maxAttributeValueLength) {
                throw MalformedXmlException(
                    "Attribute value longer than ${limits.maxAttributeValueLength} characters",
                    offset,
                )
            }
        }
        return decodeEntities(builder.toString())
    }

    private fun readText(): String {
        val builder = StringBuilder()
        while (true) {
            val character = read()
            if (character == EOF) break
            if (character.toChar() == '<') {
                unread(character)
                break
            }
            builder.append(character.toChar())
            if (builder.length > limits.maxTextLength) {
                throw MalformedXmlException("Text longer than ${limits.maxTextLength} characters", offset)
            }
        }
        return decodeEntities(builder.toString())
    }

    /**
     * Expands only the five predefined entities and numeric character
     * references.
     *
     * An unrecognised entity is left as literal text rather than resolved or
     * rejected: resolving it is the attack, and rejecting it would throw away a
     * whole DAT over one stray `&nbsp;`.
     */
    private fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character != '&') {
                builder.append(character)
                index++
                continue
            }
            val end = raw.indexOf(';', index + 1)
            if (end < 0 || end - index > MAX_ENTITY_LENGTH) {
                builder.append(character)
                index++
                continue
            }
            val entity = raw.substring(index + 1, end)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                builder.append(raw, index, end + 1)
            } else {
                builder.append(decoded)
            }
            index = end + 1
        }
        return builder.toString()
    }

    private fun decodeEntity(entity: String): String? = when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" -> "'"
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.drop(2).toIntOrNull(16)?.takeIf { it in 1..MAX_CODE_POINT }?.let { String(Character.toChars(it)) }

        entity.startsWith("#") ->
            entity.drop(1).toIntOrNull()?.takeIf { it in 1..MAX_CODE_POINT }?.let { String(Character.toChars(it)) }

        else -> null
    }

    /** Skips a `<!...>` declaration, tracking quotes and an internal subset. */
    private fun skipDeclaration() {
        var bracketDepth = 0
        var quote = 0
        while (true) {
            val character = read()
            if (character == EOF) throw MalformedXmlException("Document ends inside a declaration", offset)
            val char = character.toChar()
            when {
                quote != 0 -> if (character == quote) quote = 0
                char == '"' || char == '\'' -> quote = character
                char == '[' -> bracketDepth++
                char == ']' -> bracketDepth--
                char == '>' && bracketDepth <= 0 -> return
            }
        }
    }

    private fun skipUntil(terminator: String) {
        readUntil(terminator)
    }

    private fun readUntil(terminator: String): String {
        val builder = StringBuilder()
        while (true) {
            val character = read()
            if (character == EOF) {
                throw MalformedXmlException("Document ends before '$terminator'", offset)
            }
            builder.append(character.toChar())
            if (builder.length >= terminator.length && builder.endsWith(terminator)) {
                return builder.substring(0, builder.length - terminator.length)
            }
            if (builder.length > limits.maxTextLength) {
                throw MalformedXmlException("Section longer than ${limits.maxTextLength} characters", offset)
            }
        }
    }

    private fun consumeIfMatches(text: String): Boolean {
        val consumed = StringBuilder()
        for (expected in text) {
            val character = read()
            if (character == EOF || character.toChar() != expected) {
                if (character != EOF) consumed.append(character.toChar())
                // Restore every character consumed by the failed match, in
                // order, so the caller sees an untouched stream.
                for (index in consumed.length - 1 downTo 0) unread(consumed[index].code)
                return false
            }
            consumed.append(expected)
        }
        return true
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        val character = read()
        if (character == EOF || character.toChar() != expected) {
            throw MalformedXmlException("Expected '$expected'", offset)
        }
    }

    private fun skipWhitespace() {
        while (true) {
            val character = read()
            if (character == EOF) return
            if (!character.toChar().isWhitespace()) {
                unread(character)
                return
            }
        }
    }

    private fun read(): Int {
        pushback.removeLastOrNull()?.let {
            offset++
            return it
        }
        val value = reader.read()
        if (value >= 0) offset++
        return value
    }

    private fun unread(character: Int) {
        if (character != EOF) {
            pushback.addLast(character)
            offset--
        }
    }

    private companion object {
        const val EOF = -1
        const val MAX_ENTITY_LENGTH = 12
        const val MAX_CODE_POINT = 0x10FFFF
    }
}
