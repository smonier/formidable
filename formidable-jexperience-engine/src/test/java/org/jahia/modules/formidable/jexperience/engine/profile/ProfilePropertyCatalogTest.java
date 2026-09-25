package org.jahia.modules.formidable.jexperience.engine.profile;

import org.apache.unomi.api.Metadata;
import org.apache.unomi.api.PropertyType;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfilePropertyCatalogTest {

    private static PropertyType type(String id, String name, String valueType) {
        Metadata metadata = new Metadata(id);
        metadata.setName(name);
        PropertyType type = new PropertyType(metadata);
        type.setValueTypeId(valueType);
        return type;
    }

    private static ContextServerService serviceAnswering(PropertyType... types) throws IOException {
        ContextServerService service = mock(ContextServerService.class);
        when(service.isAvailable("site")).thenReturn(true);
        when(service.executeGetRequest(eq("site"), eq(ProfilePropertyCatalog.PROPERTY_TYPES_ENDPOINT), isNull(), isNull(), eq(PropertyType[].class)))
                .thenReturn(types);
        return service;
    }

    @Test
    void theDurationsAreTheOnesTheDesignPromises() {
        // Verifies the two constants the tooltip and the design quote: a one-minute list, a failure
        // remembered for a few seconds — shorter than the minute, so a recovery shows within it.
        assertEquals(60, ProfilePropertyCatalog.TIME_TO_LIVE.toSeconds());
        assertEquals(10, ProfilePropertyCatalog.UNAVAILABLE_TIME_TO_LIVE.toSeconds());
        assertTrue(ProfilePropertyCatalog.UNAVAILABLE_TIME_TO_LIVE.compareTo(ProfilePropertyCatalog.TIME_TO_LIVE) < 0);
    }

    @Test
    void listsFilteredPropertiesSortedByLabel() throws Exception {
        // Verifies the end-to-end shape of a fetch: filter applied, labels sorted case-insensitively.
        PropertyType hidden = type("h", "Hidden", "string");
        hidden.getMetadata().setHidden(true);
        ContextServerService service = serviceAnswering(type("zip", "zip code", "string"), hidden, type("age", "Age", "integer"));
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, () -> Instant.EPOCH);
        List<ProfilePropertyDescriptor> properties = catalog.profileProperties("site");
        assertEquals(List.of("Age (age)", "zip code (zip)"), properties.stream().map(ProfilePropertyDescriptor::label).toList());
    }

    @Test
    void aBodylessAnswerIsAnEmptySchemaNotAnOutage() throws Exception {
        // Verifies that a jCustomer answering without a body yields an empty list, which the initializer
        // turns into the "no matching property" entry, not the "not connected" one.
        ContextServerService service = serviceAnswering((PropertyType[]) null);
        assertEquals(List.of(), new ProfilePropertyCatalog(service, () -> Instant.EPOCH).profileProperties("site"));
    }

    @Test
    void cachesForAMinuteThenRefreshes() throws Exception {
        // Verifies that a second call within the time to live does not reach jCustomer, and a later one does.
        ContextServerService service = serviceAnswering(type("a", "A", "string"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, now::get);
        catalog.profileProperties("site");
        now.set(Instant.EPOCH.plus(ProfilePropertyCatalog.TIME_TO_LIVE).minusSeconds(1));
        catalog.profileProperties("site");
        verify(service, times(1)).executeGetRequest(any(), any(), any(), any(), any());
        now.set(Instant.EPOCH.plus(ProfilePropertyCatalog.TIME_TO_LIVE).plusSeconds(1));
        catalog.profileProperties("site");
        verify(service, times(2)).executeGetRequest(any(), any(), any(), any(), any());
    }

    @Test
    void anExpiredListIsNotServedWhenTheRefreshFails() throws Exception {
        // Verifies the single-duration rule of the list: past the minute, a failed read reports the schema
        // unavailable instead of serving a list that may no longer be true — and the failure is what is
        // remembered, so the next opening within its memory gets the message without a call.
        ContextServerService service = serviceAnswering(type("a", "A", "string"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, now::get);
        catalog.profileProperties("site");
        when(service.executeGetRequest(any(), any(), any(), any(), any())).thenThrow(new IOException("connection refused"));
        Instant failure = Instant.EPOCH.plus(ProfilePropertyCatalog.TIME_TO_LIVE).plusSeconds(1);
        now.set(failure);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        // within the failure's memory: the message again, no call, and never the expired list
        now.set(failure.plus(ProfilePropertyCatalog.UNAVAILABLE_TIME_TO_LIVE).minusSeconds(1));
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        verify(service, times(2)).executeGetRequest(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedReadIsRememberedAFewSecondsThenRetried() throws Exception {
        // Verifies the failure memory: a hung or refusing jCustomer is asked once per memory span, not at
        // every field opening; once the span is over, a recovered jCustomer serves the list again.
        ContextServerService service = mock(ContextServerService.class);
        when(service.isAvailable("site")).thenReturn(true);
        when(service.executeGetRequest(any(), any(), any(), any(), any())).thenThrow(new IOException("timeout"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, now::get);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        now.set(Instant.EPOCH.plus(ProfilePropertyCatalog.UNAVAILABLE_TIME_TO_LIVE).minusSeconds(1));
        ProfilePropertiesUnavailableException remembered = assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        assertTrue(remembered.getMessage().contains("remembered"), remembered.getMessage());
        verify(service, times(1)).executeGetRequest(any(), any(), any(), any(), any());

        doReturn(new PropertyType[]{type("a", "A", "string")}).when(service).executeGetRequest(any(), any(), any(), any(), any());
        now.set(Instant.EPOCH.plus(ProfilePropertyCatalog.UNAVAILABLE_TIME_TO_LIVE).plusSeconds(1));
        assertEquals(List.of("A (a)"), catalog.profileProperties("site").stream().map(ProfilePropertyDescriptor::label).toList());
        verify(service, times(2)).executeGetRequest(any(), any(), any(), any(), any());
    }

    @Test
    void aFailureThatTookTheWholeTimeoutIsStillRemembered() throws Exception {
        // Verifies that the memory is dated from the end of the call, not its start: a hung jCustomer makes
        // the admin client wait out its timeout (30 s by default), and an entry dated from the start would be
        // born expired — the next author would wait the timeout again, as before the memory existed.
        ContextServerService service = mock(ContextServerService.class);
        when(service.isAvailable("site")).thenReturn(true);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        when(service.executeGetRequest(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            now.set(now.get().plus(Duration.ofSeconds(30)));
            throw new IOException("read timed out");
        });
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, now::get);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        now.set(now.get().plusSeconds(1));
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        verify(service, times(1)).executeGetRequest(any(), any(), any(), any(), any());
    }

    @Test
    void invalidateForgetsARememberedFailureToo() throws Exception {
        // Verifies that invalidate() drops the failure memory as well as the lists: the next read asks jCustomer.
        ContextServerService service = mock(ContextServerService.class);
        when(service.isAvailable("site")).thenReturn(true);
        when(service.executeGetRequest(any(), any(), any(), any(), any())).thenThrow(new IOException("timeout"));
        ProfilePropertyCatalog catalog = new ProfilePropertyCatalog(service, () -> Instant.EPOCH);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        catalog.invalidate();
        assertThrows(ProfilePropertiesUnavailableException.class, () -> catalog.profileProperties("site"));
        verify(service, times(2)).executeGetRequest(any(), any(), any(), any(), any());
    }

    @Test
    void reportsUnavailabilityWithoutAPreviousList() throws Exception {
        // Verifies the three ways the schema can be unavailable on a first read: no module, site not
        // connected, failed call.
        ProfilePropertyCatalog noModule = new ProfilePropertyCatalog(null, () -> Instant.EPOCH);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> noModule.profileProperties("site"));

        ContextServerService offline = mock(ContextServerService.class);
        when(offline.isAvailable("site")).thenReturn(false);
        assertThrows(ProfilePropertiesUnavailableException.class, () -> new ProfilePropertyCatalog(offline, () -> Instant.EPOCH).profileProperties("site"));

        ContextServerService failing = mock(ContextServerService.class);
        when(failing.isAvailable("site")).thenReturn(true);
        when(failing.executeGetRequest(any(), any(), any(), any(), any())).thenThrow(new IOException("timeout"));
        assertThrows(ProfilePropertiesUnavailableException.class, () -> new ProfilePropertyCatalog(failing, () -> Instant.EPOCH).profileProperties("site"));
    }
}
