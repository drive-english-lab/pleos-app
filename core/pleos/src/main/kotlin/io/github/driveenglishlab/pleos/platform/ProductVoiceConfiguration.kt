package io.github.driveenglishlab.pleos.platform

/** No public SDK query can manufacture project approval, review or actual runtime signals. */
data class ProductVoiceConfiguration(val direct: SpeechRuntimeApproval? = null, val gleo: GleoProjectAdapter? = null,
    val pleosTts: PleosTtsProjectApproval? = null,
    val pleosRegistration: suspend () -> PleosTtsRegistration? = { null })
