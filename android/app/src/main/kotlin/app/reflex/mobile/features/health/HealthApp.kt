package app.reflex.mobile.features.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.app.AppRoot
import app.reflex.core.app.Route
import app.reflex.mobile.designsystem.HairlineDivider
import app.reflex.mobile.designsystem.HealthIcons
import app.reflex.mobile.designsystem.HealthSpacing
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.exposeTestTags

/**
 * The navigation UI only *reflects* Core's navigators: the tab bar reads and selects [AppRoot.tabs], the screen is
 * the top of the selected tab's stack, and system back goes to that stack's `goBack()` (at a tab root, back leaves
 * the app). Screens never push anything themselves; their actions do, in Core.
 */
@Composable
fun HealthApp(app: AppRoot) {
    val selectedTab by app.tabs.selectedTab.collectAsStateWithLifecycle()
    val stack = app.tabs.stack(selectedTab) ?: return
    val routes by stack.routes.collectAsStateWithLifecycle()
    val top = routes.lastOrNull() ?: return
    BackHandler(enabled = routes.size > 1) { stack.goBack() }
    // Each pushed screen keeps its own saveable UI state (scroll position) while it stays in a stack.
    val saveableState = rememberSaveableStateHolder()

    Scaffold(
        modifier = Modifier.fillMaxSize().exposeTestTags(),
        containerColor = HealthTheme.colors.background,
        // The editor is a full-screen form: no tab bar while it is on top.
        bottomBar = { if (top !is Route.MedicationEditor) TabBar(app, selectedTab) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            saveableState.SaveableStateProvider(System.identityHashCode(top.viewModel).toString()) {
                when (top) {
                    is Route.Today -> TodayScreen(top.viewModel)
                    is Route.Medications -> MedicationsScreen(top.viewModel)
                    is Route.History -> HistoryScreen(top.viewModel)
                    is Route.MedicationDetail -> MedicationDetailScreen(top.viewModel, backTitle(routes), onBack = stack::goBack)
                    is Route.MedicationEditor -> MedicationEditorScreen(top.viewModel, onCancel = stack::goBack)
                    is Route.Reminders -> RemindersScreen(top.viewModel, backTitle(routes), onBack = stack::goBack)
                }
            }
        }
    }
}

/** The back button names the screen below (static chrome per route type). */
private fun backTitle(routes: List<Route>): String = when (routes.getOrNull(routes.size - 2)) {
    is Route.Today -> "Today"
    is Route.Medications -> "Medications"
    is Route.History -> "History"
    else -> "Back"
}

private data class TabItem(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TAB_ITEMS = listOf(
    TabItem(AppRoot.TAB_TODAY, "Today", HealthIcons.Calendar),
    TabItem(AppRoot.TAB_MEDICATIONS, "Medications", HealthIcons.Pill),
    TabItem(AppRoot.TAB_HISTORY, "History", HealthIcons.History),
)

@Composable
private fun TabBar(app: AppRoot, selectedTab: String) {
    val colors = HealthTheme.colors
    Column {
        HairlineDivider()
        NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
            for (tab in TAB_ITEMS.filter { item -> app.tabs.tabs.any { it.id == item.id } }) {
                NavigationBarItem(
                    selected = tab.id == selectedTab,
                    onClick = { app.tabs.select(tab.id) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.title, style = HealthType.footnote) },
                    modifier = Modifier.testTag("tab.${tab.id}"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary, selectedTextColor = colors.primary,
                        unselectedIconColor = colors.secondary, unselectedTextColor = colors.secondary,
                        indicatorColor = colors.secondaryBackground,
                    ),
                )
            }
        }
    }
}

/** A scrolling screen: 20 dp gutters, section gaps, and [bottomReserve] below the content (floating Add). */
@Composable
fun HealthScreen(modifier: Modifier = Modifier, bottomReserve: Dp = HealthSpacing.lg, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(HealthTheme.colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = HealthSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(HealthSpacing.lg),
    ) {
        content()
        Spacer(Modifier.height(bottomReserve))
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = HealthType.title, color = HealthTheme.colors.primary, modifier = modifier)
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = HealthSpacing.xl), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HealthTheme.colors.primary, modifier = Modifier.semantics { contentDescription = "Loading" })
    }
}

/** At accessibility font scales the floating Add moves into the normal flow. */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= 1.3f
