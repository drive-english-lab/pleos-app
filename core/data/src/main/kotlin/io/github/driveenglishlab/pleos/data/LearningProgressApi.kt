package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.LearningSessionSource

/** Independent feature1 client. A stored reply is not playback, feedback ACK or success evidence. */
class LearningProgressApi internal constructor(internal val client: LearningApi) {
    constructor(context: Context, origin: String, sessions: LearningSessionSource, allowLocalHttp: Boolean = false) :
        this(featureClient(context, origin, sessions, allowLocalHttp))
}
