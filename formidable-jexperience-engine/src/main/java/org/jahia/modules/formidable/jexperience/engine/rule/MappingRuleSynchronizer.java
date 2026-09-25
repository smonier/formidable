package org.jahia.modules.formidable.jexperience.engine.rule;

import org.jahia.modules.formidable.engine.api.ChoiceOptionsResolver;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.utils.LanguageCodeConverters;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertiesUnavailableException;
import org.jahia.modules.formidable.jexperience.engine.profile.ProfilePropertyCatalog;
import org.jahia.modules.formidable.jexperience.engine.util.JExperienceSite;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a form's mapping rule in jCustomer equal to what the form publishes: built from the live
 * workspace in the site's default language (where option values live), compared with the stored
 * rule, posted only when it differs, deleted when the form leaves live or maps nothing any more.
 *
 * <p>A publication reaches the listener as several bursts of events — the form first, its fields
 * after — so a synchronisation asked by the listener waits {@link #COALESCE_SECONDS} and is
 * replaced by any newer request for the same form: the rule is built once, from the whole
 * publication. A site with no jExperience configuration is left alone. A jCustomer that cannot be
 * asked — module absent, connection down, request failed — puts the form on a pending list,
 * retried every minute, so a publication made during an outage reaches jCustomer once it is
 * back (docs/architecture/jexperience-integration.md, "Publishing: keeping the mapping rule in
 * sync").
 */
@Component(service = MappingRuleSynchronizer.class, immediate = true)
public class MappingRuleSynchronizer {

    static final String WORKSPACE_LIVE = "live";
    static final String RULES_ENDPOINT = "/cxs/rules";
    static final long COALESCE_SECONDS = 2;
    static final long RETRY_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(MappingRuleSynchronizer.class);

    /** Where the rules live: jCustomer through jExperience's admin client, or a fake in the tests. */
    interface RuleStore {
        /** Whether the site has a jExperience configuration at all — a site without one gets no rule and no retry. */
        boolean connected(String siteKey);

        Optional<Map<String, Object>> fetch(String siteKey, String ruleId) throws IOException;

        void save(String siteKey, Map<String, Object> rule) throws IOException;

        void delete(String siteKey, String ruleId) throws IOException;
    }

    /** What live says about a form: its mapping, or empty when the form is not published. */
    @FunctionalInterface
    interface LiveMappings {
        Optional<MappingRule.FormMapping> read(String siteKey, String formUuid) throws RepositoryException, ProfilePropertiesUnavailableException;
    }

    private final AtomicReference<ContextServerService> contextServerService = new AtomicReference<>();
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> coalesced = new ConcurrentHashMap<>();
    private RuleStore store;
    private LiveMappings live;
    private ScheduledExecutorService scheduler;

    @Reference
    private ProfilePropertyCatalog catalog;

    @Reference
    private ChoiceOptionsResolver optionsResolver;

    public MappingRuleSynchronizer() {
    }

    MappingRuleSynchronizer(RuleStore store, LiveMappings live, ScheduledExecutorService scheduler) {
        this.store = store;
        this.live = live;
        this.scheduler = scheduler;
    }

    @Activate
    public void activate() {
        store = new JExperienceRuleStore();
        live = this::readLive;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "formidable-jexperience-rule-sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::retryPending, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
    }

    @Deactivate
    public void deactivate() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void bindContextServerService(ContextServerService service) {
        contextServerService.set(service);
    }

    public void unbindContextServerService(ContextServerService service) {
        contextServerService.compareAndSet(service, null);
    }

    /**
     * Synchronises the form once the publication has settled: a request replaces any earlier one
     * still waiting for the same form, and runs {@link #COALESCE_SECONDS} after the last.
     */
    public void syncLater(String siteKey, String formUuid) {
        ScheduledFuture<?> earlier = coalesced.put(formUuid, scheduler.schedule(() -> {
            coalesced.remove(formUuid);
            sync(siteKey, formUuid);
        }, COALESCE_SECONDS, TimeUnit.SECONDS));
        if (earlier != null) {
            earlier.cancel(false);
        }
    }

    /**
     * Brings jCustomer's rule for the form in line with live, now; a form absent from live loses its
     * rule. A site without jExperience is left alone; a jCustomer that cannot be asked leaves the
     * form pending; a form that cannot be read is logged and dropped — not an outage, retrying
     * would not help.
     */
    public void sync(String siteKey, String formUuid) {
        String ruleId = MappingRule.idOf(siteKey, formUuid);
        if (!store.connected(siteKey)) {
            pending.remove(formUuid);
            log.debug("[MappingRuleSynchronizer] Site '{}' has no jExperience configuration: rule '{}' not synchronised", siteKey, ruleId);
            return;
        }
        try {
            Optional<MappingRule.FormMapping> mapping = live.read(siteKey, formUuid);
            apply(siteKey, ruleId, mapping);
            pending.remove(formUuid);
        } catch (ProfilePropertiesUnavailableException | IOException e) {
            pending.put(formUuid, siteKey);
            log.warn("[MappingRuleSynchronizer] Rule '{}' left pending, jCustomer cannot be asked: {}", ruleId, e.getMessage());
        } catch (RepositoryException e) {
            pending.remove(formUuid);
            log.error("[MappingRuleSynchronizer] Could not read form {} in live, rule '{}' not synchronised: {}", formUuid, ruleId, e.getMessage(), e);
        }
    }

    private void apply(String siteKey, String ruleId, Optional<MappingRule.FormMapping> mapping) throws IOException {
        Optional<Map<String, Object>> stored = store.fetch(siteKey, ruleId);
        if (mapping.isEmpty() || mapping.get().fields().isEmpty()) {
            if (stored.isPresent()) {
                store.delete(siteKey, ruleId);
                log.info("[MappingRuleSynchronizer] Deleted rule '{}': the form {}", ruleId, mapping.isEmpty() ? "is not published" : "maps nothing");
            }
            return;
        }
        Map<String, Object> rule = MappingRule.build(mapping.get());
        if (stored.isPresent() && MappingRule.owned(stored.get()).equals(MappingRule.owned(rule))) {
            log.debug("[MappingRuleSynchronizer] Rule '{}' unchanged", ruleId);
            return;
        }
        store.save(siteKey, rule);
        log.info("[MappingRuleSynchronizer] {} rule '{}' with {} mapped field(s)", stored.isPresent() ? "Updated" : "Created", ruleId, mapping.get().fields().size());
    }

    /**
     * The forms whose last synchronisation found jCustomer unreachable, retried in order and
     * stopping at the first still unreachable. Nothing may escape: the scheduler drops a task that
     * throws, and with it every later retry — so an unexpected error drops the form instead.
     */
    void retryPending() {
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(pending).entrySet()) {
            try {
                sync(entry.getValue(), entry.getKey());
            } catch (RuntimeException e) {
                pending.remove(entry.getKey());
                log.error("[MappingRuleSynchronizer] Unexpected error retrying form {}, dropped from the pending list: {}", entry.getKey(), e.getMessage(), e);
                continue;
            }
            if (pending.containsKey(entry.getKey())) {
                return;
            }
        }
    }

    Map<String, String> pending() {
        return Map.copyOf(pending);
    }

    /** Jahia's language codes are {@code Locale.toString()} forms ({@code en_US}): the BCP-47 parser would return the root locale. */
    static Locale localeOf(String languageCode) {
        return LanguageCodeConverters.languageCodeToLocale(languageCode);
    }

    private Optional<MappingRule.FormMapping> readLive(String siteKey, String formUuid) throws RepositoryException, ProfilePropertiesUnavailableException {
        FormMappingReader reader = new FormMappingReader(catalog, optionsResolver);
        JCRTemplate template = JCRTemplate.getInstance();
        // the site's default language: where the option values live, and the title jExperience's screen shows
        String language = template.doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, null, session -> {
            try {
                JCRSiteNode site = session.getNodeByIdentifier(formUuid).getResolveSite();
                return site == null ? null : site.getDefaultLanguage();
            } catch (ItemNotFoundException e) {
                return null;
            }
        });
        if (language == null) {
            // not in live (unpublished, removed), or outside a site: no rule
            return Optional.empty();
        }
        try {
            return Optional.of(template.doExecuteWithSystemSessionAsUser(null, WORKSPACE_LIVE, localeOf(language), session -> {
                JCRNodeWrapper form = session.getNodeByIdentifier(formUuid);
                try {
                    return reader.read(session, form, siteKey, language, form.getDisplayableName());
                } catch (ProfilePropertiesUnavailableException e) {
                    throw new UnavailableSchemaException(e);
                }
            }));
        } catch (ItemNotFoundException e) {
            // gone between the two reads: an unpublication in progress
            return Optional.empty();
        } catch (UnavailableSchemaException e) {
            throw e.cause;
        }
    }

    /** Carries the checked exception through the JCR callback, which only lets RepositoryException out. */
    private static final class UnavailableSchemaException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient ProfilePropertiesUnavailableException cause;

        UnavailableSchemaException(ProfilePropertiesUnavailableException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /** jCustomer through jExperience's admin client, site-scoped like every call of the module. */
    private final class JExperienceRuleStore implements RuleStore {

        @Override
        public boolean connected(String siteKey) {
            return JExperienceSite.configured(contextServerService.get(), siteKey);
        }

        private ContextServerService service(String siteKey) throws IOException {
            ContextServerService service = contextServerService.get();
            if (service == null) {
                throw new IOException("the jExperience module is not available");
            }
            if (!service.isAvailable(siteKey)) {
                throw new IOException("jCustomer is not available for site '" + siteKey + "'");
            }
            return service;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<Map<String, Object>> fetch(String siteKey, String ruleId) throws IOException {
            // jCustomer answers 204 for an unknown rule id, which the admin client hands back as null
            Map<String, Object> rule = service(siteKey).executeGetRequest(siteKey, RULES_ENDPOINT + "/" + ruleId, null, null, Map.class);
            return Optional.ofNullable(rule);
        }

        @Override
        public void save(String siteKey, Map<String, Object> rule) throws IOException {
            service(siteKey).executePostRequest(siteKey, RULES_ENDPOINT, rule, null, null, Object.class);
        }

        @Override
        public void delete(String siteKey, String ruleId) throws IOException {
            service(siteKey).executeDeleteRequest(siteKey, RULES_ENDPOINT + "/" + ruleId, null, null, Object.class);
        }
    }
}
