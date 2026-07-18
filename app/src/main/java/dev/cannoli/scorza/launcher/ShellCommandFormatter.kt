package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.net.Uri

data class ResolvedIntent(
    val component: ComponentName?,
    val packageName: String?,
    val action: String,
    val dataUri: Uri?,
    val mimeType: String?,
    val flagsHex: String,
    val extras: List<ResolvedExtra>,
)

sealed class ResolvedExtra {
    abstract val key: String
    data class StringExtra(override val key: String, val value: String) : ResolvedExtra()
    data class UriExtra(override val key: String, val value: Uri) : ResolvedExtra()
    data class StringArrayExtra(override val key: String, val values: List<String>) : ResolvedExtra()
}

object ShellCommandFormatter {
    fun format(resolved: ResolvedIntent, launchDisplayId: Int? = null): List<String> {
        validate(resolved)
        return buildList {
            add("am"); add("start")
            launchDisplayId?.let {
                add("--display"); add(it.toString())
            }
            resolved.component?.let { c ->
                add("-n"); add("${c.packageName}/${c.className}")
            }
            add("-a"); add(resolved.action)
            if (resolved.component == null && resolved.packageName != null) {
                add("-p"); add(resolved.packageName)
            }
            resolved.dataUri?.let { add("-d"); add(it.toString()) }
            resolved.mimeType?.let { add("-t"); add(it) }
            add("-f"); add(resolved.flagsHex)
            for (extra in resolved.extras) when (extra) {
                is ResolvedExtra.StringExtra -> { add("--es"); add(extra.key); add(extra.value) }
                is ResolvedExtra.UriExtra    -> { add("--eu"); add(extra.key); add(extra.value.toString()) }
                is ResolvedExtra.StringArrayExtra -> {
                    add("--esa"); add(extra.key)
                    add(extra.values.joinToString(",") { it.replace(",", "\\,") })
                }
            }
        }
    }

    private fun validate(resolved: ResolvedIntent) {
        val values = buildList<String> {
            resolved.component?.let { add(it.packageName); add(it.className) }
            resolved.packageName?.let(::add)
            add(resolved.action)
            resolved.dataUri?.toString()?.let(::add)
            resolved.mimeType?.let(::add)
            for (e in resolved.extras) {
                add(e.key)
                when (e) {
                    is ResolvedExtra.StringExtra -> add(e.value)
                    is ResolvedExtra.UriExtra    -> add(e.value.toString())
                    is ResolvedExtra.StringArrayExtra -> addAll(e.values)
                }
            }
        }
        for (v in values) require(!v.contains('\'')) {
            "ShellCommandFormatter: single quote in argument is not supported: $v"
        }
    }
}
