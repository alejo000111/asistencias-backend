package com.asistencia.erp.service.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
public class ParentLockManager {

    private static final long LOCK_TIMEOUT_SECONDS = 5;

    private final Cache<Long, ReentrantLock> lockRegistry = Caffeine.newBuilder()
        .maximumSize(10_000)
        .weakValues()
        .build();

    private ReentrantLock getLock(Long parentId) {
        return lockRegistry.get(parentId, key -> new ReentrantLock());
    }

    private void acquireLock(ReentrantLock lock, Long parentId) {
        try {
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException(
                    "No se pudo adquirir el lock para el padre " + parentId +
                    " después de " + LOCK_TIMEOUT_SECONDS + " segundos. Reintente la operación."
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operación interrumpida al adquirir lock para el padre " + parentId, e);
        }
    }

    public void executeWithLock(Long parentId, Runnable action) {
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T executeWithLock(Long parentId, Supplier<T> action) {
        ReentrantLock lock = getLock(parentId);
        acquireLock(lock, parentId);
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
