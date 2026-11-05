package eu.tiducto.osmreview.server.osm

import eu.tiducto.osmreview.shared.OsmRef
import eu.tiducto.osmreview.shared.OsmType
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

// OSM API v0.6 XML: element reads, osmChange uploads and diffResult responses.
// Coordinates stay strings so values round-trip exactly as the API sent them.

@Serializable
@XmlSerialName("tag")
data class XTag(val k: String, val v: String)

@Serializable
@XmlSerialName("nd")
data class XNd(val ref: Long)

@Serializable
@XmlSerialName("member")
data class XMember(val type: String, val ref: Long, val role: String = "")

@Serializable
@XmlSerialName("node")
data class XNode(
    val id: Long,
    val version: Int? = null,
    val changeset: Long? = null,
    val visible: Boolean? = null,
    val lat: String? = null,
    val lon: String? = null,
    @XmlElement(true) val tags: List<XTag> = emptyList(),
)

@Serializable
@XmlSerialName("way")
data class XWay(
    val id: Long,
    val version: Int? = null,
    val changeset: Long? = null,
    val visible: Boolean? = null,
    @XmlElement(true) val nds: List<XNd> = emptyList(),
    @XmlElement(true) val tags: List<XTag> = emptyList(),
)

@Serializable
@XmlSerialName("relation")
data class XRelation(
    val id: Long,
    val version: Int? = null,
    val changeset: Long? = null,
    val visible: Boolean? = null,
    @XmlElement(true) val members: List<XMember> = emptyList(),
    @XmlElement(true) val tags: List<XTag> = emptyList(),
)

@Serializable
@XmlSerialName("osm")
data class XOsm(
    val version: String? = null,
    val generator: String? = null,
    @XmlElement(true) val nodes: List<XNode> = emptyList(),
    @XmlElement(true) val ways: List<XWay> = emptyList(),
    @XmlElement(true) val relations: List<XRelation> = emptyList(),
    @XmlElement(true) val users: List<XUser> = emptyList(),
)

@Serializable
@XmlSerialName("user")
data class XUser(val id: Long, @XmlSerialName("display_name") val displayName: String)

@Serializable
data class XBlock(
    @XmlElement(true) val nodes: List<XNode> = emptyList(),
    @XmlElement(true) val ways: List<XWay> = emptyList(),
    @XmlElement(true) val relations: List<XRelation> = emptyList(),
)

@Serializable
@XmlSerialName("osmChange")
data class XOsmChange(
    val version: String = "0.6",
    val generator: String,
    @XmlElement(true) @XmlSerialName("create") val create: XBlock? = null,
    @XmlElement(true) @XmlSerialName("modify") val modify: XBlock? = null,
    @XmlElement(true) @XmlSerialName("delete") val delete: XBlock? = null,
)

@Serializable
data class XDiffEntry(
    @XmlSerialName("old_id") val oldId: Long,
    @XmlSerialName("new_id") val newId: Long? = null,
    @XmlSerialName("new_version") val newVersion: Int? = null,
)

@Serializable
@XmlSerialName("diffResult")
data class XDiffResult(
    @XmlElement(true) @XmlSerialName("node") val nodes: List<XDiffEntry> = emptyList(),
    @XmlElement(true) @XmlSerialName("way") val ways: List<XDiffEntry> = emptyList(),
    @XmlElement(true) @XmlSerialName("relation") val relations: List<XDiffEntry> = emptyList(),
)

@Serializable
@XmlSerialName("osm")
data class XChangesetCreate(@XmlElement(true) val changeset: XChangeset)

@Serializable
@XmlSerialName("changeset")
data class XChangeset(@XmlElement(true) val tags: List<XTag>)

/** A live OSM element as fetched from the API: full tags, version, node list or members. */
data class OsmElement(
    val type: OsmType,
    val id: Long,
    val version: Int,
    val visible: Boolean,
    val tags: Map<String, String>,
    val lat: String? = null,
    val lon: String? = null,
    val nodeIds: List<Long> = emptyList(),
    val members: List<XMember> = emptyList(),
) {
    val ref: OsmRef get() = OsmRef(type, id)
}

object OsmXml {
    val xml: XML = XML.v1.recommended {
        xmlDeclMode = nl.adaptivity.xmlutil.XmlDeclMode.Charset
        xmlVersion = nl.adaptivity.xmlutil.core.XmlVersion.XML10
        indentString = ""
        policy {
            ignoreUnknownChildren()
        }
    }

    fun parseElements(text: String): List<OsmElement> {
        val doc = xml.decodeFromString(XOsm.serializer(), text)
        return doc.nodes.map {
            OsmElement(OsmType.NODE, it.id, it.version ?: 0, it.visible ?: true, it.tags.toMap(), it.lat, it.lon)
        } + doc.ways.map {
            OsmElement(OsmType.WAY, it.id, it.version ?: 0, it.visible ?: true, it.tags.toMap(), nodeIds = it.nds.map(XNd::ref))
        } + doc.relations.map {
            OsmElement(OsmType.RELATION, it.id, it.version ?: 0, it.visible ?: true, it.tags.toMap(), members = it.members)
        }
    }

    fun parseUser(text: String): XUser = xml.decodeFromString(XOsm.serializer(), text).users.first()

    fun parseDiff(text: String): XDiffResult = xml.decodeFromString(XDiffResult.serializer(), text)

    fun changesetCreate(tags: Map<String, String>): String =
        xml.encodeToString(XChangesetCreate.serializer(), XChangesetCreate(XChangeset(tags.toXTags())))

    fun osmChange(doc: XOsmChange): String = xml.encodeToString(XOsmChange.serializer(), doc)

    /** The element as it goes into an osmChange `<modify>`/`<create>`: complete, with the changeset id. */
    fun toXml(e: OsmElement, changeset: Long): Any = when (e.type) {
        OsmType.NODE -> XNode(e.id, e.version, changeset, null, e.lat, e.lon, e.tags.toXTags())
        OsmType.WAY -> XWay(e.id, e.version, changeset, null, e.nodeIds.map(::XNd), e.tags.toXTags())
        OsmType.RELATION -> XRelation(e.id, e.version, changeset, null, e.members, e.tags.toXTags())
    }

    private fun List<XTag>.toMap(): Map<String, String> = associate { it.k to it.v }.toSortedMap()

    fun Map<String, String>.toXTags(): List<XTag> = entries.sortedBy { it.key }.map { XTag(it.key, it.value) }
}
