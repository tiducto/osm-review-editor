@file:Suppress("UnsafeCastFromDynamic")

package eu.tiducto.osmreview.shared

@JsExport
fun tagDiff(base: dynamic, proposed: dynamic): dynamic {
    val d = TagDiff.between(stringMapOf(base), stringMapOf(proposed))
    val changed: dynamic = js("({})")
    d.changed.forEach { (k, v) -> changed[k] = v.toTypedArray() }
    val out: dynamic = js("({})")
    out.added = d.added.toJsObject()
    out.removed = d.removed.toJsObject()
    out.changed = changed
    out.unchanged = d.unchanged.toTypedArray()
    return out
}

@JsExport
fun applyTagEdit(base: dynamic, set: dynamic, remove: Array<String>): dynamic =
    TagEdit(stringMapOf(set), remove.toList()).applyTo(stringMapOf(base)).toJsObject()

@JsExport
fun tagDiffSummary(base: dynamic, proposed: dynamic): String =
    TagDiff.between(stringMapOf(base), stringMapOf(proposed)).summary()

@JsExport
fun roadWidthMetres(tags: dynamic): Double? = HalfWidth.width(stringMapOf(tags))

@JsExport
fun halfWidthMetres(tags: dynamic): Double? = HalfWidth.metres(stringMapOf(tags))

private fun stringMapOf(obj: dynamic): Map<String, String> {
    if (obj == null) return emptyMap()
    val keys: Array<String> = js("Object.keys")(obj)
    return keys.associateWith { key -> obj[key].toString() }
}

private fun Map<String, String>.toJsObject(): dynamic {
    val o: dynamic = js("({})")
    forEach { (k, v) -> o[k] = v }
    return o
}
