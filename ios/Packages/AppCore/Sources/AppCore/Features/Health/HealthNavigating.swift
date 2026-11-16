/// Navigation the Health screens ask for. Implemented by `AppRoot` (pushes on the current tab's stack); held weakly.
@MainActor
public protocol HealthNavigating: AnyObject {
    func showReminders()
    /// nil: add mode; an id: edit that medication.
    func showMedicationEditor(medicationId: String?)
    func showMedicationDetail(id: String)
    /// Pops `screen` if it is the top of its stack.
    func close(_ screen: AnyObject)
}
