package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.LearningSessionSource

/** Selection settings, frozen materials and reference recommendations remain separate data. */
class AiLearningApi internal constructor(internal val client: LearningApi) {
    constructor(context: Context, origin: String, sessions: LearningSessionSource, allowLocalHttp: Boolean = false) :
        this(featureClient(context, origin, sessions, allowLocalHttp))
}
