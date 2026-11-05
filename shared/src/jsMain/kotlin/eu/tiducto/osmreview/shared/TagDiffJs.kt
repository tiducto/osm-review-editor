@file:Suppress("UnsafeCastFromDynamic")

package eu.tiducto.osmreview.shared

// TagDiff logic for the Vue UI, so it previews an edit without a server round trip. Plain JS objects
// in and out (`Record<string, string>`), matching the JSON wire shape; web/src/lib/tagdiff.ts types them.

/** Returns `{added, removed, changed, unchanged}`, identical to the server's TagDiff JSON. */
@JsExport
fun tagDiff(base: dynamic, proposed: dynamic): dynamic {
    val d = TagDiff.between(base.toStringMap(), proposed.toStringMap())
    val changed: dynamic = js("({})")
    d.changed.forEach { (k, v) -> changed[k] = v.toTypedArray() }
    val out: dynamic = js("({})")
    out.added = d.added.toJsObject()
    out.removed = d.removed.toJsObject()
    out.changed = changed
    out.unchanged = d.unchanged.toTypedArray()
    return out
}

/** Applies a `{set, remove}` edit to the tags and returns the new tag object (keys sorted). */
@JsExport
fun applyTagEdit(base: dynamic, set: dynamic, remove: Array<String>): dynamic =
    TagEdit(set.toStringMap(), remove.toList()).applyTo(base.toStringMap()).toJsObject()

/** One-line summary such as `+area=yes ~surface=asphalt −sidewalk`. */
@JsExport
fun tagDiffSummary(base: dynamic, proposed: dynamic): String =
    TagDiff.between(base.toStringMap(), proposed.toStringMap()).summary()

private fun Any?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val obj: dynamic = this
    val keys: Array<String> = js("Object.keys")(obj)
    return keys.associateWith { obj[it].toString() }
}

private fun Map<String, String>.toJsObject(): dynamic {
    val o: dynamic = js("({})")
    forEach { (k, v) -> o[k] = v }
    return o
}
