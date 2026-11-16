import IntrospectionKit

extension RemindersViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: RemindersViewModel
        let name = "Reminders"

        var state: JSONObject {
            [
                "permission": .string(viewModel.state.permission),
                "mayBeLate": .bool(viewModel.state.mayBeLate),
                "statusLabel": .string(viewModel.state.statusLabel),
                "reminders": .array(viewModel.state.reminders.map { reminder in
                    [
                        "id": .string(reminder.id),
                        "kind": .string(reminder.kind),
                        "fireAt": .string(reminder.fireAt),
                        "title": .string(reminder.title),
                        "body": .string(reminder.body),
                    ]
                }),
            ]
        }

        var actions: [IntrospectionAction] {
            [IntrospectionAction(title: "TurnOnReminders") { viewModel.send(action: .turnOnReminders) }]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}
