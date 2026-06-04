package dev.cannoli.scorza.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.DataBinding
import dev.cannoli.scorza.config.ExtraSpec
import dev.cannoli.scorza.config.ExtraValueKind
import java.io.File

object EmulatorIntentBuilder {

    fun resolve(context: Context, config: AppConfig, romFile: File): ResolvedIntent {
        val component = config.activity?.let { ComponentName(config.packageName, it) }
        val pkgOnly = if (component == null) config.packageName else null
        val dataUri: Uri? = when (val d = config.data) {
            DataBinding.None -> null
            is DataBinding.FileProvider -> fileProviderUri(context, romFile)
            DataBinding.AbsolutePath -> Uri.fromFile(romFile)
            DataBinding.ExternalStorageSaf -> externalStorageSafUri(romFile) ?: Uri.fromFile(romFile)
            is DataBinding.CustomScheme -> Uri.parse("${d.scheme}://${d.authority}")
                .buildUpon().appendPath(romFile.absolutePath).build()
        }
        val resolvedExtras = config.extras.map { spec -> resolveExtra(context, spec, romFile) }
        return ResolvedIntent(
            component = component,
            packageName = pkgOnly,
            action = config.action,
            dataUri = dataUri,
            mimeType = if (dataUri != null) config.mimeType else null,
            flagsHex = "0x${Integer.toHexString(config.intentFlags)}",
            extras = resolvedExtras,
        )
    }

    fun toAndroidIntent(context: Context, resolved: ResolvedIntent, config: AppConfig): Intent {
        val intent = Intent(resolved.action).apply {
            resolved.component?.let { component = it } ?: resolved.packageName?.let(::setPackage)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(config.intentFlags)
            resolved.dataUri?.let { uri ->
                if (resolved.mimeType != null) setDataAndType(uri, resolved.mimeType)
                else data = uri
            }
        }
        if (intent.component == null && intent.action == Intent.ACTION_MAIN) {
            context.packageManager.getLaunchIntentForPackage(config.packageName)?.component?.let {
                intent.component = it
                intent.setPackage(null)
            }
        }
        if (resolved.dataUri != null && (config.data as? DataBinding.FileProvider)?.grantPermission == true) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var clipDataUri: Uri? = null
        for (extra in resolved.extras) when (extra) {
            is ResolvedExtra.StringExtra -> {
                intent.putExtra(extra.key, extra.value)
                if (extra.value.startsWith("content://")) {
                    val uri = Uri.parse(extra.value)
                    context.grantUriPermission(config.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (clipDataUri == null) clipDataUri = uri
                }
            }
            is ResolvedExtra.UriExtra -> {
                intent.putExtra(extra.key, extra.value)
                context.grantUriPermission(config.packageName, extra.value, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (clipDataUri == null) clipDataUri = extra.value
            }
            is ResolvedExtra.StringArrayExtra ->
                intent.putExtra(extra.key, extra.values.toTypedArray())
        }
        // Argosy pattern: when extras carry a content URI, attach it as ClipData
        // and add the intent-level grant flag. This routes emulators with
        // VIEW-style intent filters into the working code path even when the URI
        // itself isn't readable by the receiver.
        clipDataUri?.let { uri ->
            intent.clipData = android.content.ClipData.newRawUri(null, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    private fun resolveExtra(context: Context, spec: ExtraSpec, romFile: File): ResolvedExtra = when (spec.kind) {
        ExtraValueKind.FILE_PATH ->
            ResolvedExtra.StringExtra(spec.key, romFile.absolutePath)
        ExtraValueKind.FILE_URI_STRING ->
            ResolvedExtra.StringExtra(spec.key, fileProviderUri(context, romFile).toString())
        ExtraValueKind.FILE_URI_PARCELABLE ->
            ResolvedExtra.UriExtra(spec.key, fileProviderUri(context, romFile))
        ExtraValueKind.STRING_ARRAY -> {
            val romContents by lazy {
                romFile.readText().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }
            val resolved = spec.values.orEmpty().map {
                if (it == "{rom_contents}") romContents else it
            }
            ResolvedExtra.StringArrayExtra(spec.key, resolved)
        }
    }

    private fun fileProviderUri(context: Context, romFile: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", romFile)

    // Synthesize a tree-delegated SAF document URI on com.android.externalstorage.documents
    // from an absolute path. The tree segment is set to the rom's parent directory so the
    // framework's permission check picks up any persistent tree grant the receiver holds at
    // that level or above. A plain document URI would not tap into the receiver's grant.
    private fun externalStorageSafUri(romFile: File): Uri? {
        val path = romFile.absolutePath
        val emulatedPrefix = "/storage/emulated/0/"
        val storagePrefix = "/storage/"
        val (volume, relative) = when {
            path.startsWith(emulatedPrefix) -> "primary" to path.removePrefix(emulatedPrefix)
            path.startsWith(storagePrefix) -> {
                val rest = path.removePrefix(storagePrefix)
                val slash = rest.indexOf('/')
                if (slash <= 0) return null
                rest.substring(0, slash) to rest.substring(slash + 1)
            }
            else -> return null
        }
        val parent = relative.substringBeforeLast('/', "")
        val docId = "$volume:$relative"
        val treeId = "$volume:$parent"
        val authority = "com.android.externalstorage.documents"
        val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(authority, treeId)
        return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }
}
