package app.reflex.mobile.features.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.sample.SampleDetailViewModel
import app.reflex.core.features.sample.SampleListViewModel

// REFERENCE FEATURE: delete with the rest of "Sample". Views read state and call send(action). Nothing else:
// no business rules here (e.g. the Decrement button's enabled state comes from state.canDecrement).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleListScreen(viewModel: SampleListViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Items") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.loadError?.let { error ->
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f))
                    Button(onClick = { viewModel.send(SampleListViewModel.Action.Refresh) }) { Text("Retry") }
                }
            }
            LazyColumn {
                items(state.items, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        trailingContent = { Text("${item.count}") },
                        modifier = Modifier.clickable { viewModel.send(SampleListViewModel.Action.SelectItem(item.id)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleDetailScreen(viewModel: SampleDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { viewModel.send(SampleDetailViewModel.Action.Save) }, enabled = !state.isSaving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(onClick = { viewModel.send(SampleDetailViewModel.Action.Decrement) }, enabled = state.canDecrement) { Text("−") }
                Text("${state.count}")
                Button(onClick = { viewModel.send(SampleDetailViewModel.Action.Increment) }) { Text("+") }
            }
            if (state.isSaved) Text("Saved")
            state.saveError?.let { Text(it) }
            state.loadError?.let { Text(it) }
        }
    }
}
