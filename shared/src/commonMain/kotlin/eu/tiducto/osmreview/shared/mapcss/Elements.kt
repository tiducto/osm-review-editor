package eu.tiducto.osmreview.shared.mapcss

import eu.tiducto.osmreview.shared.OsmType

interface MapCssElement {
    val type: OsmType
    val id: Long
    val tags: Map<String, String>
    val isClosed: Boolean
    val parents: List<MapCssLink>
    val children: List<MapCssLink>
}

class MapCssLink(val element: MapCssElement, val role: String = "", val index: Int = 0)

interface MapCssHost {
    fun call(name: String, element: MapCssElement, args: List<Any?>): Any? = null

    fun pseudoClass(name: String, element: MapCssElement): Boolean? = null

    fun elementsInside(area: MapCssElement): List<MapCssElement> = emptyList()

    fun elementsCrossing(element: MapCssElement): List<MapCssElement> = emptyList()

    companion object {
        val NONE: MapCssHost = object : MapCssHost {}
    }
}

class SimpleElement(
    override val type: OsmType,
    override val id: Long = 0,
    override val tags: Map<String, String> = emptyMap(),
    override val isClosed: Boolean = false,
) : MapCssElement {
    override val parents: MutableList<MapCssLink> = mutableListOf()
    override val children: MutableList<MapCssLink> = mutableListOf()

    fun addChild(child: SimpleElement, role: String = ""): SimpleElement {
        val index = children.size
        children += MapCssLink(child, role, index)
        child.parents += MapCssLink(this, role, index)
        return this
    }
}
