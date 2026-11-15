package io.github.driveenglishlab.pleos.platform

/** No public SDK query can manufacture project approval, review or actual runtime signals. */
data class ProductVoiceConfiguration(val direct: SpeechRuntimeApproval? = null, val gleo: GleoProjectAdapter? = null,
    val pleosTts: PleosTtsProjectApproval? = null,
    val ttsRuntimeIdentity:TtsRuntimeIdentity1?=null,
    val pleosRegistration: suspend () -> PleosTtsRegistration? = { null },
    /** Explicit ON_DEVICE input selection; missing review never activates a fallback. */
    val parkedInput: ParkedSpeechRuntimeApproval? = null,
    /** Actual reviewed first-input disclosure for the retained generic route. */
    val directInputNotice: String? = null)
