package eu.tiducto.osmreview.server.replication

import eu.tiducto.osmreview.shared.OsmType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLOutputFactory

enum class OscAction(val tag: String) { CREATE("create"), MODIFY("modify"), DELETE("delete") }

class OscChild(val name: String, val attrs: List<Pair<String, String>>) {
    fun attr(k: String): String? = attrs.firstOrNull { it.first == k }?.second
}

class OscElement(
    val action: OscAction,
    val type: OsmType,
    val id: Long,
    val attrs: List<Pair<String, String>>,
    val children: List<OscChild>,
) {
    private fun attr(k: String): String? = attrs.firstOrNull { it.first == k }?.second

    val lon: Double? get() = attr("lon")?.toDoubleOrNull()
    val lat: Double? get() = attr("lat")?.toDoubleOrNull()

    val nodeRefs: List<Long> get() = children.mapNotNull { c -> if (c.name == "nd") c.attr("ref")?.toLongOrNull() else null }

    val members: List<Pair<OsmType, Long>>
        get() = children.mapNotNull { c ->
            if (c.name != "member") return@mapNotNull null
            val t = when (c.attr("type")) {
                "node" -> OsmType.NODE
                "way" -> OsmType.WAY
                "relation" -> OsmType.RELATION
                else -> return@mapNotNull null
            }
            c.attr("ref")?.toLongOrNull()?.let { t to it }
        }
}

object OscDiff {
    private val DROPPED_ATTRS = setOf("user", "uid", "visible")
    private val TYPES = mapOf("node" to OsmType.NODE, "way" to OsmType.WAY, "relation" to OsmType.RELATION)
    private val inputs = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
    private val outputs = XMLOutputFactory.newFactory()

    fun parseGz(gz: ByteArray): List<OscElement> = GZIPInputStream(ByteArrayInputStream(gz)).use { parse(it.readBytes()) }

    fun parseOsm(xml: ByteArray): List<OscElement> = parse(xml, OscAction.MODIFY, skipInvisible = true)

    fun parse(xml: ByteArray, defaultAction: OscAction? = null, skipInvisible: Boolean = false): List<OscElement> {
        val r = inputs.createXMLStreamReader(ByteArrayInputStream(xml))
        val out = ArrayList<OscElement>()
        var action: OscAction? = defaultAction
        var current: Triple<OsmType, List<Pair<String, String>>, MutableList<OscChild>>? = null
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val name = r.localName
                        val attrs = (0 until r.attributeCount).map { r.getAttributeLocalName(it) to r.getAttributeValue(it) }
                        when {
                            name == "osmChange" -> Unit
                            OscAction.entries.any { it.tag == name } -> action = OscAction.entries.first { it.tag == name }
                            name in TYPES && action != null -> current = Triple(TYPES.getValue(name), attrs, ArrayList())
                            current != null -> current.third += OscChild(name, attrs)
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        val name = r.localName
                        val cur = current
                        if (name in TYPES && cur != null && action != null) {
                            val id = cur.second.first { it.first == "id" }.second.toLong()
                            val invisible = cur.second.any { it.first == "visible" && it.second == "false" }
                            if (!(skipInvisible && invisible)) out += OscElement(action, cur.first, id, cur.second.filter { it.first !in DROPPED_ATTRS }, cur.third)
                            current = null
                        } else if (OscAction.entries.any { it.tag == name }) {
                            action = defaultAction
                        }
                    }
                }
            }
        } finally {
            r.close()
        }
        return out
    }

    fun write(elements: List<OscElement>, generator: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        OutputStreamWriter(bytes, Charsets.UTF_8).use { writer ->
            val w = outputs.createXMLStreamWriter(writer)
            w.writeStartDocument("UTF-8", "1.0")
            w.writeCharacters("\n")
            w.writeStartElement("osmChange")
            w.writeAttribute("version", "0.6")
            w.writeAttribute("generator", generator)
            w.writeCharacters("\n")
            var open: OscAction? = null
            for (e in elements) {
                if (e.action != open) {
                    if (open != null) {
                        w.writeCharacters("  ")
                        w.writeEndElement()
                        w.writeCharacters("\n")
                    }
                    w.writeCharacters("  ")
                    w.writeStartElement(e.action.tag)
                    w.writeCharacters("\n")
                    open = e.action
                }
                w.writeCharacters("    ")
                w.writeStartElement(e.type.apiName)
                e.attrs.forEach { (k, v) -> w.writeAttribute(k, v) }
                if (e.children.isNotEmpty()) {
                    w.writeCharacters("\n")
                    for (c in e.children) {
                        w.writeCharacters("      ")
                        w.writeEmptyElement(c.name)
                        c.attrs.forEach { (k, v) -> w.writeAttribute(k, v) }
                        w.writeCharacters("\n")
                    }
                    w.writeCharacters("    ")
                }
                w.writeEndElement()
                w.writeCharacters("\n")
            }
            if (open != null) {
                w.writeCharacters("  ")
                w.writeEndElement()
                w.writeCharacters("\n")
            }
            w.writeEndElement()
            w.writeCharacters("\n")
            w.writeEndDocument()
            w.flush()
        }
        return bytes.toByteArray()
    }

    fun writeGz(elements: List<OscElement>, generator: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(write(elements, generator)) }
        return out.toByteArray()
    }
}
