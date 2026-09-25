package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldAction;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.modules.formidable.engine.config.FormidableConfigService.FieldActionSettings;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the two entry points of the field actions share: the Java actions registered as OSGi services, the one
 * {@link VerdictCache} — so that the verdict the pre-check endpoint gave on a value is the one the submission
 * pipeline finds, and the provider is called once — the {@link RateLimiter} of the endpoint, the
 * {@link FieldActionsCache} of the forms' declared actions, and the {@link FieldActionDispatcher} built on them. The submit servlet and the field-action servlet both reference it.
 */
@Component(service = FieldActionRuntime.class, immediate = true)
public class FieldActionRuntime {

    private final AtomicReference<FormidableConfigService> config = new AtomicReference<>();
    private final List<FieldAction> fieldActions = new CopyOnWriteArrayList<>();
    private final VerdictCache cache = new VerdictCache();
    private final RateLimiter rateLimiter = new RateLimiter();
    private final AtomicReference<FieldActionDispatcher> dispatcher = new AtomicReference<>();
    private final FieldActionsCache formActions = new FieldActionsCache();

    @Reference
    public void setConfig(FormidableConfigService service) {
        config.set(service);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "unbindFieldAction")
    protected void bindFieldAction(FieldAction action) {
        fieldActions.add(action);
    }

    protected void unbindFieldAction(FieldAction action) {
        fieldActions.remove(action);
    }

    /** The registered Java field actions, a snapshot. */
    public List<FieldAction> fieldActions() {
        return List.copyOf(fieldActions);
    }

    /** The dispatcher over the registered actions, the shared cache and the configured TTL. */
    public FieldActionDispatcher dispatcher() {
        return dispatcher.updateAndGet(current -> current != null ? current
                : new FieldActionDispatcher(this::fieldActions, cache, () -> settings().verdictCacheTtl()));
    }

    /** Replaces the dispatcher — a seam for the tests, which hand over one built on a fake repository. */
    void useDispatcher(FieldActionDispatcher fixed) {
        dispatcher.set(fixed);
    }

    RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** The field actions of the published forms, by form and locale, kept for the endpoint between two walks. */
    FieldActionsCache formActions() {
        return formActions;
    }

    /** The field-action settings of the current configuration. */
    public FieldActionSettings settings() {
        FormidableConfigService service = config.get();
        if (service == null) {
            throw new IllegalStateException("FormidableConfigService is not available.");
        }
        return service.getFieldActionSettings();
    }
}
