package org.jahia.test.modules.formidable.samples.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.modules.formidable.engine.api.FieldActionResult;
import org.junit.jupiter.api.Test;

import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The built-in check of an address's domain: what it asks the domain name system, and what it makes of the answer.
 * The one seam is the lookup itself — a test that queried real DNS would be a test of the network.
 */
class EmailDeliverabilityFieldActionTest {

    private static FieldActionRequest of(String value) {
        return new FieldActionRequest("form-1", "email", value, Locale.ENGLISH);
    }

    private static FieldActionResult judge(EmailDeliverabilityFieldAction.MailRecords records, String value) {
        return new EmailDeliverabilityFieldAction(records).execute(null, of(value));
    }

    @Test
    void aDomainThatReceivesMailIsAccepted() {
        // Verifies the nominal pass, and the implicit exchanger of RFC 5321: a domain with no MX but an address
        // record still receives mail, so either answer accepts.
        assertEquals(FieldActionResult.Verdict.ACCEPT, judge(domain -> List.of("MX"), "ada@example.com").verdict());
        assertEquals(FieldActionResult.Verdict.ACCEPT, judge(domain -> List.of("A"), "ada@example.com").verdict());
    }

    @Test
    void onlyADomainTheResolverDeniesIsRefused() {
        // Verifies the single refusal this action allows itself: the resolver says the name does not exist, which
        // is the mistyped domain and the case worth catching.
        assertEquals(FieldActionResult.Verdict.REJECT, judge(domain -> {
            throw new NameNotFoundException(domain);
        }, "ada@nosuchdomain.example").verdict());
    }

    @Test
    void anEmptyAnswerIsNotProofAndNeverRefuses() {
        // Verifies the lesson of the live test: a resolver may decline a record type rather than deny the name —
        // Docker's embedded resolver answers nothing to a mail-exchanger question, and most installations run
        // behind one. Refusing on an empty answer refused every address on such a host.
        assertEquals(FieldActionResult.Verdict.UNAVAILABLE, judge(domain -> List.of(), "ada@example.com").verdict());
    }

    @Test
    void aResolverThatDoesNotAnswerIsAnUnavailableCheck() {
        // Verifies the distinction that the contributor's whenUnavailable setting rests on: a resolver failing is an
        // outage, not a verdict on the address. Refusing here would turn a DNS hiccup into refused submissions.
        FieldActionResult result = judge(domain -> {
            throw new CommunicationException("timed out");
        }, "ada@example.com");

        assertEquals(FieldActionResult.Verdict.UNAVAILABLE, result.verdict());
    }

    @Test
    void aValueThatIsNotOneAddressIsAcceptedWithoutAQuery() {
        // Verifies what this action is NOT: the shape of a value is the field's own validation, at step 9. Anything
        // it cannot read a single domain from passes untouched, and no query leaves the server — which also keeps
        // a crafted value from steering the resolver.
        AtomicReference<String> asked = new AtomicReference<>();
        EmailDeliverabilityFieldAction.MailRecords records = domain -> {
            asked.set(domain);
            return List.of("MX");
        };

        for (String value : List.of("", "   ", "ada", "@example.com", "ada@", "a@b@c.com", "ada@-bad-.com", "ada@nodot",
                // a label past sixty-three characters, a character no label may carry, and two empty labels — the
                // last of which the reading would throw on if its emptiness were checked after its first character
                "ada@" + "a".repeat(64) + ".com", "ada@ex_ample.com", "ada@.com", "ada@a..b.com")) {
            assertEquals(FieldActionResult.Verdict.ACCEPT, judge(records, value).verdict(), value);
        }
        assertNull(asked.get(), "no value of that shape may reach the resolver");
    }

    @Test
    void onlyTheDomainIsAskedAbout() {
        // Verifies the one privacy property worth a test: what leaves the server is the domain, lower-cased and
        // without its trailing dot — never the address, so the visitor's identity is not handed to a resolver.
        AtomicReference<String> asked = new AtomicReference<>();
        judge(domain -> {
            asked.set(domain);
            return List.of("MX");
        }, "  Ada.Lovelace+forms@Example.COM.  ");

        assertEquals("example.com", asked.get());
    }

    @Test
    void anAbsurdlyLongDomainIsNotAskedAbout() {
        // Verifies the guard before the query: past what a domain name may measure, there is nothing to ask.
        AtomicReference<String> asked = new AtomicReference<>();
        // five labels of sixty and a suffix: 308 characters, past the 253 a domain name may measure
        String tooLong = "ada@" + ("a".repeat(60) + ".").repeat(5) + "com";

        assertEquals(FieldActionResult.Verdict.ACCEPT, judge(domain -> {
            asked.set(domain);
            return List.of("MX");
        }, tooLong).verdict());
        assertNull(asked.get());
    }
}
