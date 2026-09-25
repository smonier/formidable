package org.jahia.modules.formidable.engine.actions.form.forward;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Resolves hostnames off the servlet thread with an application-level timeout and a short-lived cache.
 * This bounds the time spent waiting on DNS lookups during form submission, even though the underlying
 * {@link InetAddress#getAllByName(String)} call does not expose a native timeout API. The resolver pool
 * uses a bounded bulkhead with no queue so stuck DNS lookups cannot accumulate an unbounded backlog.
 */
@Component(service = HostnameResolutionService.class, immediate = true)
public class HostnameResolutionService {

    static final Duration DEFAULT_RESOLUTION_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
    static final int DEFAULT_RESOLVER_THREADS = 2;

    private final Duration resolutionTimeout;
    private final Duration cacheTtl;
    private final ExecutorService executor;
    private final Resolver resolver;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public HostnameResolutionService() {
        this(
                DEFAULT_RESOLUTION_TIMEOUT,
                DEFAULT_CACHE_TTL,
                createExecutor(DEFAULT_RESOLVER_THREADS),
                InetAddress::getAllByName,
                System::nanoTime
        );
    }

    HostnameResolutionService(
            Duration resolutionTimeout,
            Duration cacheTtl,
            ExecutorService executor,
            Resolver resolver,
            LongSupplier nanoTimeSupplier
    ) {
        this.resolutionTimeout = Objects.requireNonNull(resolutionTimeout, "resolutionTimeout");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
    }

    public InetAddress[] resolveAll(String hostname) throws UnknownHostException, TimeoutException {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname must not be blank");
        }

        long now = nanoTimeSupplier.getAsLong();
        CacheEntry cached = cache.get(hostname);
        if (cached != null && cached.expiresAtNanos > now) {
            return Arrays.copyOf(cached.addresses, cached.addresses.length);
        }
        if (cached != null) {
            cache.remove(hostname, cached);
        }

        final Future<InetAddress[]> future;
        try {
            future = executor.submit(() -> resolver.resolve(hostname));
        } catch (RejectedExecutionException e) {
            TimeoutException timeout = new TimeoutException(
                    "Hostname resolution executor saturated for '" + hostname + "'");
            timeout.initCause(e);
            throw timeout;
        }
        try {
            InetAddress[] resolved = future.get(resolutionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            InetAddress[] copy = Arrays.copyOf(resolved, resolved.length);
            cache.put(hostname, new CacheEntry(copy, now + cacheTtl.toNanos()));
            return Arrays.copyOf(copy, copy.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving hostname '" + hostname + "'", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException unknownHostException) {
                throw unknownHostException;
            }
            throw new IllegalStateException("Hostname resolution failed for '" + hostname + "'", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    @Deactivate
    public void deactivate() {
        cache.clear();
        executor.shutdownNow();
    }

    private static ExecutorService createExecutor(int threads) {
        AtomicInteger index = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "formidable-dns-resolver-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }

    private static final class CacheEntry {
        private final InetAddress[] addresses;
        private final long expiresAtNanos;

        private CacheEntry(InetAddress[] addresses, long expiresAtNanos) {
            this.addresses = addresses;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
