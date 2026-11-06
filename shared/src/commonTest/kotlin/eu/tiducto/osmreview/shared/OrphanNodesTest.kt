package eu.tiducto.osmreview.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphanNodesTest {
    private fun change(
        id: String,
        ref: OsmRef,
        action: ChangeAction,
        status: ChangeStatus = ChangeStatus.PENDING,
        tags: Map<String, String> = emptyMap(),
        nodes: List<Long>? = null,
        members: List<RelationMember>? = null,
    ) = Change(
        id = id,
        draftId = "d",
        ref = ref,
        action = action,
        newTags = tags,
        diff = TagDiff.between(emptyMap(), tags),
        confidence = Confidence.NEEDS_REVIEW,
        status = status,
        createdAt = "",
        updatedAt = "",
        newShape = when {
            nodes != null -> ElementShape(nodes = nodes)
            members != null -> ElementShape(members = members)
            action == ChangeAction.CREATE && ref.type == OsmType.NODE -> ElementShape(location = LngLat(17.9, 49.9))
            else -> null
        },
    )

    private fun node(id: String, ref: Long, status: ChangeStatus = ChangeStatus.PENDING, tags: Map<String, String> = emptyMap()) =
        change(id, OsmRef(OsmType.NODE, ref), ChangeAction.CREATE, status, tags)

    private fun way(id: String, ref: Long, nodes: List<Long>, status: ChangeStatus = ChangeStatus.PENDING, action: ChangeAction = ChangeAction.CREATE) =
        change(id, OsmRef(OsmType.WAY, ref), action, status, mapOf("highway" to "footway"), nodes = nodes)

    @Test
    fun untaggedNewNodeAloneIsUnowned() {
        val all = listOf(node("n1", -1), node("n2", -2), way("w", -3, listOf(-1, -2)))
        assertEquals(listOf("n1"), OrphanNodes.unowned(all, setOf("n1")).map { it.id })
        assertTrue(OrphanNodes.unowned(all, setOf("n1", "n2", "w")).isEmpty())
        assertEquals(listOf("w"), OrphanNodes.owners(all, all[0]).map { it.id })
    }

    @Test
    fun taggedNodesAndExistingNodesAreNeverOrphans() {
        val bench = node("b", -1, tags = mapOf("amenity" to "bench"))
        val existing = change("e", OsmRef(OsmType.NODE, 5), ChangeAction.MODIFY, tags = emptyMap())
        assertTrue(OrphanNodes.unowned(listOf(bench, existing), setOf("b", "e")).isEmpty())
        assertFalse(OrphanNodes.isUntaggedNewNode(bench))
        assertTrue(OrphanNodes.isUntaggedNewNode(node("x", -9)))
        assertFalse(OrphanNodes.isUntaggedNewNode(node("x", -9), mapOf("barrier" to "gate")))
    }

    @Test
    fun anyApprovedOrUploadedOwnerIsEnough() {
        val shared = node("n", -1)
        val a = way("a", -2, listOf(-1, 7), ChangeStatus.REJECTED)
        val b = way("b", 3, listOf(8, -1, 9), action = ChangeAction.MODIFY)
        val all = listOf(shared, a, b)
        assertTrue(OrphanNodes.unowned(all, setOf("n", "b")).isEmpty())
        assertEquals(listOf("n"), OrphanNodes.unowned(all, setOf("n")).map { it.id })
        val uploaded = listOf(shared, a.copy(status = ChangeStatus.UPLOADED))
        assertTrue(OrphanNodes.unowned(uploaded, setOf("n")).isEmpty())
    }

    @Test
    fun relationMembershipOwnsANode() {
        val n = node("n", -1)
        val r = change("r", OsmRef(OsmType.RELATION, -2), ChangeAction.CREATE, tags = mapOf("type" to "site"), members = listOf(RelationMember(OsmType.NODE, -1)))
        assertTrue(OrphanNodes.unowned(listOf(n, r), setOf("n", "r")).isEmpty())
    }

    @Test
    fun deletedWaysDoNotOwnNodes() {
        val n = node("n", -1)
        val del = change("d", OsmRef(OsmType.WAY, 4), ChangeAction.DELETE, nodes = listOf(-1))
        assertEquals(listOf("n"), OrphanNodes.unowned(listOf(n, del), setOf("n", "d")).map { it.id })
    }

    @Test
    fun abandonedNodesAreThoseWhoseEveryOwnerIsRejected() {
        val only = node("only", -1, ChangeStatus.APPROVED)
        val shared = node("shared", -2)
        val loose = node("loose", -5)
        val gone = node("gone", -6, ChangeStatus.REJECTED)
        val rejected = way("w1", -3, listOf(-1, -2, -6), ChangeStatus.REJECTED)
        val kept = way("w2", -4, listOf(-2, 10))
        val all = listOf(only, shared, loose, gone, rejected, kept)
        assertEquals(listOf("only"), OrphanNodes.abandoned(all).map { it.id })
    }

    @Test
    fun describeNamesTheWays() {
        val n = node("n", -1)
        val w = way("w", -3, listOf(-1))
        assertTrue(OrphanNodes.describe(n, listOf(w)).contains("w-3 (pending)"))
        assertTrue(OrphanNodes.describe(n, emptyList()).contains("no way or relation"))
    }
}
