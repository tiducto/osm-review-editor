package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.shared.ChangeAction
import eu.tiducto.osmreview.shared.OsmType
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ChangeBlock(val action: ChangeAction, val elements: List<OsmElement>, val ifUnused: Boolean = false)

data class OsmChangeEntry(val action: ChangeAction, val ifUnused: Boolean, val element: OsmElement)

object OsmChangeXml {
    const val MAX_ELEMENTS = 10_000

    fun parse(text: String): List<OsmChangeEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(InputSource(StringReader(text))) }
            .getOrElse { throw IllegalArgumentException("Not a valid osmChange document: ${it.message}") }
        val root = doc.documentElement
        require(root.tagName == "osmChange") { "Expected an <osmChange> root element, got <${root.tagName}>" }
        val out = ArrayList<OsmChangeEntry>()
        for (block in root.children()) {
            val action = when (block.tagName) {
                "create" -> ChangeAction.CREATE
                "modify" -> ChangeAction.MODIFY
                "delete" -> ChangeAction.DELETE
                else -> throw IllegalArgumentException("Unknown osmChange block <${block.tagName}>")
            }
            val ifUnused = block.hasAttribute("if-unused") && block.getAttribute("if-unused") != "false"
            for (el in block.children()) {
                out += OsmChangeEntry(action, ifUnused, element(el))
                require(out.size <= MAX_ELEMENTS) { "At most $MAX_ELEMENTS elements per osmChange" }
            }
        }
        return out
    }

    private fun element(el: Element): OsmElement {
        val type = runCatching { OsmType.fromApiName(el.tagName) }.getOrElse { throw IllegalArgumentException("Unknown element <${el.tagName}>") }
        val id = el.getAttribute("id").toLongOrNull() ?: throw IllegalArgumentException("<${el.tagName}> without a numeric id")
        require(id != 0L) { "${type.apiName} id 0 is not valid" }
        val kids = el.children()
        val tags = kids.filter { it.tagName == "tag" }.associate { it.getAttribute("k") to it.getAttribute("v") }.toSortedMap()
        val ref = "${type.apiName} $id"
        return OsmElement(
            type = type,
            id = id,
            version = el.getAttribute("version").toIntOrNull() ?: 0,
            visible = el.getAttribute("visible") != "false",
            tags = tags,
            lat = el.getAttribute("lat").ifBlank { null }?.also { require(it.toDoubleOrNull() != null) { "$ref has an invalid lat" } },
            lon = el.getAttribute("lon").ifBlank { null }?.also { require(it.toDoubleOrNull() != null) { "$ref has an invalid lon" } },
            nodeIds = kids.filter { it.tagName == "nd" }.map {
                it.getAttribute("ref").toLongOrNull() ?: throw IllegalArgumentException("$ref has an <nd> without a numeric ref")
            },
            members = kids.filter { it.tagName == "member" }.map {
                val t = runCatching { OsmType.fromApiName(it.getAttribute("type")) }
                    .getOrElse { _ -> throw IllegalArgumentException("$ref has a member with an unknown type") }
                XMember(t.apiName, it.getAttribute("ref").toLongOrNull() ?: throw IllegalArgumentException("$ref has a member without a numeric ref"), it.getAttribute("role"))
            },
        )
    }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    fun write(generator: String, blocks: List<ChangeBlock>, changeset: Long?): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<osmChange version="0.6" generator="${esc(generator)}">""")
        for (b in blocks) {
            if (b.elements.isEmpty()) continue
            val name = b.action.name.lowercase()
            append("<").append(name)
            if (b.action == ChangeAction.DELETE && b.ifUnused) append(""" if-unused="true"""")
            append(">")
            b.elements.forEach { e -> element(e, b.action, changeset) }
            append("</").append(name).append(">")
        }
        append("</osmChange>")
    }

    private fun StringBuilder.element(e: OsmElement, action: ChangeAction, changeset: Long?) {
        val name = e.type.apiName
        append("<").append(name).append(""" id="${e.id}"""")
        if (action != ChangeAction.CREATE) append(""" version="${e.version}"""")
        changeset?.let { append(""" changeset="$it"""") }
        if (e.type == OsmType.NODE && e.lat != null && e.lon != null) append(""" lat="${e.lat}" lon="${e.lon}"""")
        append(">")
        if (action != ChangeAction.DELETE) {
            e.nodeIds.forEach { append("""<nd ref="$it"/>""") }
            e.members.forEach { append("""<member type="${esc(it.type)}" ref="${it.ref}" role="${esc(it.role)}"/>""") }
            e.tags.entries.sortedBy { it.key }.forEach { append("""<tag k="${esc(it.key)}" v="${esc(it.value)}"/>""") }
        }
        append("</").append(name).append(">")
    }

    fun coord(v: Double): String = BigDecimal(v).setScale(7, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(ch)
            }
        }
    }
}
