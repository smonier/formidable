import org.jahia.services.content.JCRObservationManager
import org.jahia.services.content.JCRSessionFactory

// Simulates a field stored by Formidable 0.4.0, whose options-source and date-bounds
// mixins carried fmdb:-prefixed properties (#310): every unprefixed property of that
// family present on the node is rewritten under its prefixed name and removed; the
// i18n properties (option list, empty-option label) move the same way on each
// j:translation_* subnode. The prefixed
// definitions still exist in the CND (hidden, deprecated), so the writes are accepted.
def fieldPath = "__FIELD_PATH__"
def nodeProperties = [
    "optionsMode", "optionsSourceKey", "optionsRootCategory", "optionsRootNode", "optionsNodeType",
    "minBoundMode", "maxBoundMode", "minRelativeAmount", "minRelativeUnit", "maxRelativeAmount", "maxRelativeUnit"
]

def prefix = { owner, name ->
    def property = owner.getProperty(name)
    if (property.isMultiple()) {
        owner.setProperty("fmdb:" + name, property.getValues())
    } else {
        owner.setProperty("fmdb:" + name, property.getValue())
    }
    property.remove()
}

def report = []
["default", "live"].each { workspace ->
    def session = JCRSessionFactory.getInstance().getCurrentSystemSession(workspace, null, null)
    if (!session.nodeExists(fieldPath)) {
        report << "${workspace}: missing node ${fieldPath}"
        return
    }

    def node = session.getNode(fieldPath)
    def renamed = []
    nodeProperties.each { name ->
        if (node.hasProperty(name)) {
            prefix(node, name)
            renamed << name
        }
    }
    node.getNodes("j:translation_*").each { translation ->
        ["options", "optionsEmptyLabel"].each { name ->
            if (translation.hasProperty(name)) {
                prefix(translation, name)
                renamed << name + "@" + translation.name
            }
        }
    }
    // A 0.4 site never wrote these nodes in live directly: keep Jahia's UGCListener
    // from marking the simulated state as live-owned (#281).
    JCRObservationManager.setAllEventListenersDisabled(workspace == "live")
    try {
        session.save()
    } finally {
        JCRObservationManager.setAllEventListenersDisabled(false)
    }
    report << "${workspace}: prefixed ${renamed.join(', ')} on ${node.path}"
}

return report.join(" | ")
