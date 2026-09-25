package org.jahia.modules.formidable.jexperience.engine.profile;

import org.apache.unomi.api.PropertyType;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The profile properties of a site's jCustomer, read through jExperience's admin client and
 * kept for a minute, per site, in this single service — shared by every author of the Jahia
 * node. The Content Editor evaluates the choicelist initializer each time a mappable field is
 * opened or created, whether or not the jExperience section is unfolded, so without this memory
 * every field opening would carry a jCustomer round trip (10-17 ms next door, 50-200 ms across
 * a network). One duration rules the list: it is served while it is less than a minute old;
 * past that, the next opening reads jCustomer again, and a read that fails reports the schema
 * unavailable — the initializer turns it into a message — rather than serving a list that may
 * no longer be true. A property created in jExperience shows at the first opening after the
 * minute; the property's tooltip says so.
 *
 * <p>A failed read is remembered too, for a few seconds: jExperience's admin client waits up to
 * its configured timeout (30 s by default) on a hung jCustomer, and without that memory every
 * opening of every mappable field by every author would start a fresh call and wait on it,
 * while Jahia kept loading a jCustomer already in trouble. The entry then reads "unavailable,
 * ask again after {@link #UNAVAILABLE_TIME_TO_LIVE}"; a recovery shows within the tooltip's
 * minute. Details and the decision: docs/architecture/jexperience-integration.md, "The
 * profile-property catalog".
 */
@Component(service = ProfilePropertyCatalog.class, immediate = true)
public class ProfilePropertyCatalog {

    static final String PROPERTY_TYPES_ENDPOINT = "/cxs/profiles/properties/targets/profiles";
    static final Duration TIME_TO_LIVE = Duration.ofMinutes(1);
    static final Duration UNAVAILABLE_TIME_TO_LIVE = Duration.ofSeconds(10);

    /** A site's entry: the list, or — with a null list — the memory of a failed read and its reason. */
    private record Entry(List<ProfilePropertyDescriptor> properties, String failure, Instant expires) {
        boolean unavailable() {
            return properties == null;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    // jExperience may start after, restart, or be absent: the reference follows it
    private final AtomicReference<ContextServerService> contextServerService = new AtomicReference<>();

    public ProfilePropertyCatalog() {
        this(Instant::now);
    }

    ProfilePropertyCatalog(Supplier<Instant> clock) {
        this.clock = clock;
    }

    ProfilePropertyCatalog(ContextServerService contextServerService, Supplier<Instant> clock) {
        this(clock);
        this.contextServerService.set(contextServerService);
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void bindContextServerService(ContextServerService service) {
        contextServerService.set(service);
    }

    public void unbindContextServerService(ContextServerService service) {
        contextServerService.compareAndSet(service, null);
    }

    /** The mappable profile properties of the site, sorted by label, less than a minute old. */
    public List<ProfilePropertyDescriptor> profileProperties(String siteKey) throws ProfilePropertiesUnavailableException {
        Objects.requireNonNull(siteKey, "siteKey");
        Entry cached = cache.get(siteKey);
        Instant now = clock.get();
        if (cached != null && cached.expires().isAfter(now)) {
            if (cached.unavailable()) {
                throw new ProfilePropertiesUnavailableException(cached.failure()
                        + " (remembered for " + UNAVAILABLE_TIME_TO_LIVE.toSeconds() + " s before jCustomer is asked again)");
            }
            return cached.properties();
        }
        try {
            List<ProfilePropertyDescriptor> fresh = fetch(siteKey);
            // the clock is read again: the call may have waited out the admin client's timeout (30 s by default)
            cache.put(siteKey, new Entry(fresh, null, clock.get().plus(TIME_TO_LIVE)));
            return fresh;
        } catch (ProfilePropertiesUnavailableException e) {
            // an expired list is not served: what the author sees is under a minute old, or a message —
            // and the failure is remembered a few seconds, so an outage is not paid at every field opening
            cache.put(siteKey, new Entry(null, e.getMessage(), clock.get().plus(UNAVAILABLE_TIME_TO_LIVE)));
            throw e;
        }
    }

    /** Forgets every cached list and every remembered failure — after a mapping was rejected for an unknown property, for instance. */
    public void invalidate() {
        cache.clear();
    }

    private List<ProfilePropertyDescriptor> fetch(String siteKey) throws ProfilePropertiesUnavailableException {
        ContextServerService service = contextServerService.get();
        if (service == null) {
            throw new ProfilePropertiesUnavailableException("the jExperience module is not available");
        }
        if (!service.isAvailable(siteKey)) {
            throw new ProfilePropertiesUnavailableException("jCustomer is not available for site '" + siteKey + "'");
        }
        try {
            PropertyType[] types = service.executeGetRequest(siteKey, PROPERTY_TYPES_ENDPOINT, null, null, PropertyType[].class);
            if (types == null) {
                // a bodyless answer is an empty schema, not an outage
                return List.of();
            }
            return Arrays.stream(types)
                    .map(ProfilePropertyFilter::describe)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(ProfilePropertyDescriptor::label, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw new ProfilePropertiesUnavailableException("could not read the profile properties of site '" + siteKey + "': " + e.getMessage(), e);
        }
    }
}
