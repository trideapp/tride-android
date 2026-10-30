/**
 * What the view last handed the bridge of each node material's uniforms — the bridge's half of the
 * core's changed-only rule: only uniforms whose lanes moved are sent again.
 *
 * Public API: none (`internal`, `TrideKitScene`): `changes(slug, family, values)`, `clear()`.
 *
 * Non-obvious constraints:
 *   - The core hands a node material's whole table on every pull (`materialGraphValues`), however few
 *     inputs a tick wrote; `FilamentGenerators` keeps the table and an instance made later starts from
 *     it, so a send carries the moved uniforms only and the others keep theirs.
 *   - Keyed by palette slug and family; `clear()` at every attach (a new bridge, a scene reload) — the
 *     first push after it is the whole table, so nothing stale survives a re-bind.
 *   - Lanes compare exactly. The same rule on iOS (`SentUniforms.swift`) and the web
 *     (`filament-material-graphs.ts`).
 */
package studio.tride.kit

import studio.tride.core.TrideMaterialGraphValue

internal class SentUniforms {
    private val sent = HashMap<String, HashMap<String, List<Double>>>()

    /** The uniforms of [values] whose lanes differ from the ones last handed over for [slug] on [family]; they become the last. */
    fun changes(slug: String, family: String, values: List<TrideMaterialGraphValue>): List<TrideMaterialGraphValue> {
        val held = sent.getOrPut("$slug\u0000$family") { HashMap() }
        val moved = values.filter { held[it.name] != it.values }
        for (value in moved) held[value.name] = value.values
        return moved
    }

    fun clear() = sent.clear()
}
