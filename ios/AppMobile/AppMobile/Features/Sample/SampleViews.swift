import AppCore
import SwiftUI

// REFERENCE FEATURE: delete with the rest of "Sample". Views read state and call send(action:). Nothing else:
// no business rules here (e.g. the Decrement button's disabled state comes from state.canDecrement).

struct SampleListView: View {
    let viewModel: SampleListViewModel

    var body: some View {
        let state = viewModel.state
        List {
            if let error = state.loadError {
                HStack {
                    Text(error)
                    Spacer()
                    Button("Retry") { viewModel.send(action: .refresh) }
                }
            }
            ForEach(state.items, id: \.id) { item in
                Button { viewModel.send(action: .selectItem(id: item.id)) } label: {
                    LabeledContent(item.title, value: "\(item.count)")
                }
            }
        }
        .overlay { if state.isLoading && state.items.isEmpty { ProgressView() } }
        .refreshable {
            viewModel.send(action: .refresh)
            await waitUntil { !viewModel.state.isLoading }   // keeps the spinner up until Core has the result
        }
        .navigationTitle("Items")
    }
}

struct SampleDetailView: View {
    let viewModel: SampleDetailViewModel

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 16) {
            HStack(spacing: 24) {
                Button("−") { viewModel.send(action: .decrement) }
                    .disabled(!state.canDecrement)
                Text("\(state.count)").font(.largeTitle.monospacedDigit())
                Button("+") { viewModel.send(action: .increment) }
            }
            .buttonStyle(.bordered)
            if state.isSaved { Text("Saved") }
            if let error = state.saveError ?? state.loadError { Text(error).foregroundStyle(.red) }
        }
        .navigationTitle(state.title)
        .toolbar {
            Button("Save") { viewModel.send(action: .save) }
                .disabled(state.isSaving)
        }
    }
}
