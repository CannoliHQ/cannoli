package dev.cannoli.scorza.input.v2.runtime

/**
 * Folds the kernel's multi-endpoint InputDevices for a single physical controller into one logical
 * cluster.
 *
 * Background: a physical pad can produce 1-N InputDevices. On a typical host the gamepad is a
 * single endpoint (one InputDevice). On Retroid handhelds the kernel rewrites a paired BT pad's
 * gamepad endpoint into a virtual USB-HID InputDevice with the built-in's vid/pid and an empty
 * uniqueId, while leaving the original BT endpoints (touchpad, IMU) intact with their real Sony
 * vid/pid and a populated uniqueId. Three InputDevices appear for one DualSense. The One35
 * handheld similarly emits "One35 Virtual Gamepad" / "Keyboard" / "Mouse" for one physical pad.
 *
 * To both:
 *   (a) merge the auxiliary endpoints onto the gamepad's logical port (port routing), and
 *   (b) recover a stable per-physical-pad persistence key (filename, match descriptor),
 * we cluster InputDevices using two signals together:
 *   - Shared name prefix at a word boundary (>= [MIN_PREFIX_LEN] characters).
 *   - InputDevice.id contiguity (gap <= [MAX_ID_GAP] between adjacent members).
 * The gamepad-source endpoint in each cluster is the primary; the others are aliases. The
 * cluster's persistence descriptor is taken from a sibling InputDevice with a non-degenerate
 * descriptor when the gamepad's own is degenerate (Retroid phantom case), otherwise the
 * gamepad's own descriptor.
 *
 * A gamepad-source endpoint is treated as a cluster terminator: once a group contains a gamepad,
 * no more devices are appended to it. This is what keeps two same-model pads with consecutive
 * IDs (e.g. two DualSenses producing {10,11,12} and {13,14,15}) from merging into one bogus
 * cluster — each gamepad endpoint anchors its own cluster boundary.
 */
object SiblingFolder {

    /** Minimum shared-prefix length (in characters) for two InputDevices to be considered siblings. */
    private const val MIN_PREFIX_LEN = 5

    /** Maximum InputDevice.id gap between adjacent siblings in the same cluster. */
    private const val MAX_ID_GAP = 1

    data class Candidate(
        val androidDeviceId: Int,
        val name: String,
        val descriptor: String,
        val isGamepad: Boolean,
    )

    data class Cluster(
        val gamepad: Candidate,
        val aliases: List<Candidate>,
        val persistenceDescriptor: String,
    )

    fun fold(candidates: List<Candidate>): List<Cluster> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.androidDeviceId }
        val groups = mutableListOf<List<Candidate>>()
        var current = mutableListOf<Candidate>()
        var currentRoot = ""
        for (c in sorted) {
            if (current.isEmpty()) {
                current.add(c)
                currentRoot = c.name
                continue
            }
            val terminated = current.any { it.isGamepad }
            val lastId = current.last().androidDeviceId
            val idAdjacent = (c.androidDeviceId - lastId) <= MAX_ID_GAP
            val sharedLen = sharedPrefixLength(currentRoot, c.name)
            val nameShared = sharedLen >= MIN_PREFIX_LEN
            if (!terminated && idAdjacent && nameShared) {
                current.add(c)
                // Tighten the running root to the shared prefix so subsequent matches use the
                // narrowed root rather than the first device's full name.
                if (sharedLen < currentRoot.length) {
                    currentRoot = currentRoot.substring(0, sharedLen)
                }
            } else {
                groups.add(current)
                current = mutableListOf(c)
                currentRoot = c.name
            }
        }
        if (current.isNotEmpty()) groups.add(current)

        val out = mutableListOf<Cluster>()
        for (group in groups) {
            val gamepad = group.firstOrNull { it.isGamepad } ?: continue
            val aliases = group.filter { it.androidDeviceId != gamepad.androidDeviceId }
            val gamepadDescriptor = gamepad.descriptor.takeIf { it.isNotEmpty() }
            val siblingDescriptor = aliases.map { it.descriptor }.firstOrNull { it.isNotEmpty() }
            val persistenceDescriptor = gamepadDescriptor ?: siblingDescriptor ?: ""
            out.add(Cluster(gamepad = gamepad, aliases = aliases, persistenceDescriptor = persistenceDescriptor))
        }
        return out
    }

    /**
     * Length of the longest common prefix between [a] and [b] that ends at a word boundary. A
     * boundary exists when:
     *   - the common prefix's trailing character is whitespace (clean boundary on both sides), or
     *   - one of the remainders is empty (one name is a prefix of the other), or
     *   - the remainder begins with whitespace (boundary at the prefix-divergence point).
     * Returns the length of the common prefix with any trailing whitespace trimmed, so the caller
     * never sees a root that ends in a space.
     */
    private fun sharedPrefixLength(a: String, b: String): Int {
        val rawCommon = a.commonPrefixWith(b)
        if (rawCommon.length < MIN_PREFIX_LEN) return 0
        val aRest = a.substring(rawCommon.length)
        val bRest = b.substring(rawCommon.length)
        val endsInWhitespace = rawCommon.last().isWhitespace()
        val aBoundary = aRest.isEmpty() || aRest[0].isWhitespace()
        val bBoundary = bRest.isEmpty() || bRest[0].isWhitespace()
        if (!endsInWhitespace && !aBoundary && !bBoundary) return 0
        return rawCommon.trimEnd().length
    }
}
