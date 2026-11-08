package app.reflex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.app.AppRoot
import app.reflex.core.app.Route
import app.reflex.mobile.features.sample.SampleDetailScreen
import app.reflex.mobile.features.sample.SampleListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as ReflexApplication).core
        setContent {
            MaterialTheme { RootScreen(core.app) }
        }
    }
}

/**
 * The navigation UI only *reflects* Core's navigator; system back is routed to `goBack()`,
 * so navigation state has a single source of truth (the same one the CLI walks).
 */
@Composable
fun RootScreen(app: AppRoot) {
    val routes by app.navigator.routes.collectAsStateWithLifecycle()
    BackHandler(enabled = app.navigator.canGoBack) { app.navigator.goBack() }
    when (val top = routes.last()) {
        is Route.SampleList -> SampleListScreen(top.viewModel)
        is Route.SampleDetail -> SampleDetailScreen(top.viewModel, onBack = app.navigator::goBack)
    }
}
