package io.github.driveenglishlab.pleos

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Explicit renderer-only entry point. Does not initialize Auth, vehicle SDK or media owners.
 * Use only with ProductScreensTest/PreparedProductScreensTest; never report as MainActivity QA.
 */
class RendererTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
