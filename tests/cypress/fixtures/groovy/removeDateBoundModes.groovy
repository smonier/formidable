import org.jahia.services.content.JCRObservationManager
import org.jahia.services.content.JCRSessionFactory

// Simulates a field stored before the bound modes existed by removing the
// minBoundMode / maxBoundMode properties while keeping the fixed
// values. NOTE: this is the closest state the JCR API can produce — a genuine
// 0.3 node also lacks the fixed-bound mixins, but that shape is not
// reproducible (a raw write without an applicable definition is rejected, and
// removing a mixin drops its properties); the definition-less branch of the
// migration is unit-tested with mocks instead.
def fieldPath = "__FIELD_PATH__"

def report = []
["default", "live"].each { workspace ->
    def session = JCRSessionFactory.getInstance().getCurrentSystemSession(workspace, null, null)
    if (!session.nodeExists(fieldPath)) {
        report << "${workspace}: missing node ${fieldPath}"
        return
    }

    def node = session.getNode(fieldPath)
    ["minBoundMode", "maxBoundMode"].each { property ->
        if (node.hasProperty(property)) {
            node.getProperty(property).remove()
        }
    }
    // A 0.3 site never wrote these nodes in live directly: keep Jahia's UGCListener
    // from marking the simulated legacy state as live-owned (#281).
    JCRObservationManager.setAllEventListenersDisabled(workspace == "live")
    try {
        session.save()
    } finally {
        JCRObservationManager.setAllEventListenersDisabled(false)
    }
    report << "${workspace}: removed bound modes on ${node.path}"
}

return report.join(" | ")
