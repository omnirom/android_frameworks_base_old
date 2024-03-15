package com.android.systemui.qs.pipeline.shared

import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.RefactorFlagUtils
import javax.inject.Inject

/** Encapsulate the different QS pipeline flags and their dependencies */
@SysUISingleton
class QSPipelineFlagsRepository @Inject constructor() {

    val pipelineEnabled: Boolean
        get() = AconfigFlags.qsNewPipeline()

    val tilesEnabled: Boolean
        get() = AconfigFlags.qsNewTiles()

    companion object Utils {
        fun assertInLegacyMode() =
            RefactorFlagUtils.assertInLegacyMode(
                AconfigFlags.qsNewPipeline(),
                AconfigFlags.FLAG_QS_NEW_PIPELINE
            )

        fun assertNewTiles() =
            RefactorFlagUtils.assertInNewMode(
                AconfigFlags.qsNewTiles(),
                AconfigFlags.FLAG_QS_NEW_TILES
            )
    }
}
