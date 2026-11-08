package app.reflex.core.introspection

import app.reflex.core.features.sample.SampleDetailViewModel
import app.reflex.core.features.sample.SampleDetailViewModelIntrospectionNode
import app.reflex.core.features.sample.SampleListViewModel
import app.reflex.core.features.sample.SampleListViewModelIntrospectionNode
import app.reflex.introspection.IntrospectionRegistry

/**
 * Registers every ViewModel's node. Called at startup by the CLI and the debug socket server only;
 * app code never references this file. Every new screen adds one line here.
 */
fun registerIntrospection() {
    IntrospectionRegistry.register(SampleListViewModel::class) { SampleListViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(SampleDetailViewModel::class) { SampleDetailViewModelIntrospectionNode(it) }
}
