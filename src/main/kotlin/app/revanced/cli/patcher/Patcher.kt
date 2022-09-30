package app.revanced.cli.patcher

import app.revanced.patcher.Apk
import app.revanced.patcher.data.Data
import app.revanced.patcher.patch.Patch
import app.revanced.utils.patcher.addPatchesFiltered
import app.revanced.utils.patcher.applyPatchesVerbose
import app.revanced.utils.patcher.mergeFiles

internal object Patcher {
    internal fun app.revanced.patcher.Patcher.start(allPatches: List<Class<out Patch<Data>>>, baseApk: Apk.Base) {
        // merge files like necessary integrations
        mergeFiles()
        // add patches, but filter incompatible or excluded patches
        addPatchesFiltered(allPatches, baseApk)
        // apply patches
        applyPatchesVerbose()
    }
}
