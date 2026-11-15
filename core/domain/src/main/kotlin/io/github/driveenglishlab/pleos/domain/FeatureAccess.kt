package io.github.driveenglishlab.pleos.domain

/** A value is usable only for the current policy-source generation. Unknown is not a denial. */
data class ComponentAccess(val known:Boolean=false,val allowed:Boolean=false,val sourceGeneration:Long=-1,val permissionGeneration:Long=0) {
    init { require(permissionGeneration>=0) }
    fun current(generation:Long)=known && generation>=0 && sourceGeneration==generation
}
data class ComponentPolicies(val generation:Long=0,val audio:ComponentAccess=ComponentAccess(),
    val media:ComponentAccess=ComponentAccess(),val microphone:ComponentAccess=ComponentAccess())
enum class FeatureScope { NOTICE, DETAILS, MEDIA, DIRECT_CAPTURE, GLEO, PERSISTENCE }
enum class FeatureAccessReason {
    ACTUAL_SUSPEND, OWNER_UNAVAILABLE, PARK_REQUIRED, UXR_UNKNOWN, DETAILS_RESTRICTED, SYSTEM_BLOCKED,
    WINDOW_HIDDEN, WINDOW_UNFOCUSED, AUDIO_POLICY_UNKNOWN, AUDIO_POLICY_DENIED, MEDIA_POLICY_UNKNOWN,
    MEDIA_POLICY_DENIED, MICROPHONE_POLICY_UNKNOWN, MICROPHONE_POLICY_DENIED, MEDIA_ROUTE_UNAVAILABLE,
    MEDIA_FOCUS_UNAVAILABLE, BACKGROUND_MEDIA_UNAVAILABLE, MICROPHONE_PERMISSION_MISSING,
    DIRECT_INPUT_RESTRICTED, DIRECT_PROVIDER_UNAVAILABLE, INPUT_ARBITRATION_UNAVAILABLE,
    REPLY_FOCUS_UNAVAILABLE, GLEO_ROUTE_UNAVAILABLE, GLEO_VEHICLE_UNAVAILABLE,
}
data class FeatureAccessDecision(val allowed:Boolean,val reasons:Set<FeatureAccessReason>)

/** Evaluates only conditions relevant to one feature. It never rewrites a raw power fact. */
object FeatureAccessEvaluator {
    fun evaluate(scope:FeatureScope,access:AccessState,requireFocus:Boolean=true):FeatureAccessDecision {
        val reasons=linkedSetOf<FeatureAccessReason>()
        fun need(value:Boolean,reason:FeatureAccessReason){if(!value)reasons+=reason}
        fun component(value:ComponentAccess,unknown:FeatureAccessReason,denied:FeatureAccessReason){
            if(!value.current(access.components.generation))reasons+=unknown else if(!value.allowed)reasons+=denied
        }
        fun details(){
            need(access.safety.position==VehiclePosition.PARKED,FeatureAccessReason.PARK_REQUIRED)
            need(access.safety.uxKnown,FeatureAccessReason.UXR_UNKNOWN)
            need(access.safety.detailsAllowed,FeatureAccessReason.DETAILS_RESTRICTED)
            need(!access.safety.systemBlocked,FeatureAccessReason.SYSTEM_BLOCKED)
            need(access.window.visible,FeatureAccessReason.WINDOW_HIDDEN)
        }
        // Mandatory atomic preservation uses original repository owner/epoch fences even at Suspend.
        // Generic limited-status display is still governed by the platform-visible window.
        if(scope !in setOf(FeatureScope.NOTICE,FeatureScope.PERSISTENCE) && access.suspending)reasons+=FeatureAccessReason.ACTUAL_SUSPEND
        if(scope!=FeatureScope.NOTICE)need(access.ownerValid,FeatureAccessReason.OWNER_UNAVAILABLE)
        when(scope){
            FeatureScope.NOTICE->{need(access.window.visible,FeatureAccessReason.WINDOW_HIDDEN);need(!access.safety.systemBlocked,FeatureAccessReason.SYSTEM_BLOCKED)}
            FeatureScope.DETAILS->details()
            FeatureScope.MEDIA->{
                component(access.components.audio,FeatureAccessReason.AUDIO_POLICY_UNKNOWN,FeatureAccessReason.AUDIO_POLICY_DENIED)
                component(access.components.media,FeatureAccessReason.MEDIA_POLICY_UNKNOWN,FeatureAccessReason.MEDIA_POLICY_DENIED)
                need(access.mediaRouteAvailable,FeatureAccessReason.MEDIA_ROUTE_UNAVAILABLE)
                if(requireFocus)need(access.mediaFocusAllowed,FeatureAccessReason.MEDIA_FOCUS_UNAVAILABLE)
                if(!access.window.visible)need(access.backgroundMediaAllowed,FeatureAccessReason.BACKGROUND_MEDIA_UNAVAILABLE)
            }
            FeatureScope.DIRECT_CAPTURE->{
                details();need(access.window.focused,FeatureAccessReason.WINDOW_UNFOCUSED)
                component(access.components.microphone,FeatureAccessReason.MICROPHONE_POLICY_UNKNOWN,FeatureAccessReason.MICROPHONE_POLICY_DENIED)
                need(access.safety.directInputAllowed,FeatureAccessReason.DIRECT_INPUT_RESTRICTED)
                need(access.microphoneGranted,FeatureAccessReason.MICROPHONE_PERMISSION_MISSING)
                need(access.directProviderVerified,FeatureAccessReason.DIRECT_PROVIDER_UNAVAILABLE)
                need(access.inputArbitrationVerified,FeatureAccessReason.INPUT_ARBITRATION_UNAVAILABLE)
                need(access.directReplyFocusAllowed,FeatureAccessReason.REPLY_FOCUS_UNAVAILABLE)
            }
            FeatureScope.GLEO->{
                need(access.gleoRouteAvailable,FeatureAccessReason.GLEO_ROUTE_UNAVAILABLE)
                need(access.gleoVehicleAllowed,FeatureAccessReason.GLEO_VEHICLE_UNAVAILABLE)
                if(requireFocus)need(access.gleoReplyFocusAllowed,FeatureAccessReason.REPLY_FOCUS_UNAVAILABLE)
            }
            FeatureScope.PERSISTENCE->Unit
        }
        return FeatureAccessDecision(reasons.isEmpty(),reasons)
    }
}
