package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;

/**
 * Serializes access to each supplied public Spring Session identifier.
 *
 * <p>This filter is deliberately registered outside and immediately before Spring Session so
 * the lease also covers the repository's request-end save.</p>
 */
public final class SessionPersistenceConcurrencyFilter extends OncePerRequestFilter {
    private static final String RETRY_AFTER_SECONDS = "1";
    private static final int MAXIMUM_RAW_SESSION_IDS = 2;

    private final HttpSessionIdResolver sessionIds;
    private final LoginSubjectHasher subjects;
    private final int maximumHolders;
    private final SecurityProblemWriter problems;
    private final Map<String, LockHolder> holders = new HashMap<>();

    public SessionPersistenceConcurrencyFilter(
            HttpSessionIdResolver sessionIds,
            LoginSubjectHasher subjects,
            RateLimitProperties rateLimits,
            SecurityProblemWriter problems) {
        this.sessionIds = Objects.requireNonNull(sessionIds, "session id resolver is required");
        this.subjects = Objects.requireNonNull(subjects, "subject hasher is required");
        this.maximumHolders = Objects.requireNonNull(
                rateLimits, "rate-limit properties are required").maximumSubjects();
        this.problems = Objects.requireNonNull(problems, "problem writer is required");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        List<String> lockKeys;
        try {
            lockKeys = lockKeys(request);
        } catch (CapacityExceeded failure) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
            return;
        } catch (RuntimeException failure) {
            problems.write(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
            return;
        }

        if (lockKeys.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        List<Lease> leases = new ArrayList<>(lockKeys.size());
        try {
            for (String lockKey : lockKeys) {
                Lease lease = reserve(lockKey);
                leases.add(lease);
                lease.acquire();
            }
        } catch (CapacityExceeded failure) {
            releaseReverse(leases);
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
            return;
        } catch (RuntimeException failure) {
            releaseReverse(leases);
            problems.write(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            releaseReverse(leases);
        }
    }

    private List<String> lockKeys(HttpServletRequest request) {
        Objects.requireNonNull(request, "request is required");
        List<String> resolved = Objects.requireNonNull(
                sessionIds.resolveSessionIds(request), "resolved session ids are required");
        if (resolved.size() > MAXIMUM_RAW_SESSION_IDS) {
            throw new CapacityExceeded();
        }
        Set<String> canonicalIds = new LinkedHashSet<>();
        for (String candidate : resolved) {
            if (isCanonicalUuid(candidate)) {
                canonicalIds.add(candidate);
            }
        }

        Set<String> sortedDigests = new TreeSet<>();
        for (String canonicalId : canonicalIds) {
            String digest = subjects.hashSessionId(canonicalId);
            if (!isLowercaseSha256(digest)) {
                throw new IllegalStateException("session lock hashing failed");
            }
            sortedDigests.add(digest);
        }
        return List.copyOf(sortedDigests);
    }

    private Lease reserve(String key) {
        synchronized (holders) {
            LockHolder holder = holders.get(key);
            if (holder == null) {
                if (holders.size() >= maximumHolders) {
                    throw new CapacityExceeded();
                }
                holder = new LockHolder();
                holders.put(key, holder);
            }
            holder.references++;
            return new Lease(key, holder);
        }
    }

    private void releaseReverse(List<Lease> leases) {
        for (int index = leases.size() - 1; index >= 0; index--) {
            release(leases.get(index));
        }
    }

    private void release(Lease lease) {
        if (lease.released) {
            return;
        }
        lease.released = true;
        try {
            if (lease.acquired) {
                lease.holder.lock.unlock();
            }
        } finally {
            synchronized (holders) {
                lease.holder.references--;
                if (lease.holder.references < 0) {
                    throw new IllegalStateException("session lock reference count underflow");
                }
                if (lease.holder.references == 0
                        && holders.get(lease.key) == lease.holder) {
                    holders.remove(lease.key);
                }
            }
        }
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean isLowercaseSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "SessionPersistenceConcurrencyFilter[sessionIds=<redacted>, "
                + "subjects=<redacted>, maximumHolders=" + maximumHolders + ']';
    }

    private static final class LockHolder {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }

    private static final class Lease {
        private final String key;
        private final LockHolder holder;
        private boolean acquired;
        private boolean released;

        private Lease(String key, LockHolder holder) {
            this.key = key;
            this.holder = holder;
        }

        private void acquire() {
            holder.lock.lock();
            acquired = true;
        }
    }

    private static final class CapacityExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CapacityExceeded() {
            super("session lock capacity exceeded", null, false, false);
        }
    }
}
