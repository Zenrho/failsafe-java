package com.zenrho.failsafe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class FailsafeTest {

    @Test
    void testBasicExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        
        Failsafe.run(() -> counter.incrementAndGet())
               .start();
        
        assertEquals(1, counter.get());
    }

    @Test
    void testRetryMechanism() {
        AtomicInteger attempts = new AtomicInteger(0);
        
        assertThrows(RuntimeException.class, () -> {
            Failsafe.run(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Test exception");
            })
            .onException()
                .retry(3)
            .start();
        });
        
        assertEquals(3, attempts.get());
    }

    @Test
    void testSuccessCallback() {
        AtomicInteger successCounter = new AtomicInteger(0);
        
        Failsafe.run(() -> {/* do nothing */})
               .onSuccess(() -> successCounter.incrementAndGet())
               .start();
        
        assertEquals(1, successCounter.get());
    }

    @Test
    void testFinallyCallback() {
        AtomicInteger finallyCounter = new AtomicInteger(0);
        
        Failsafe.run(() -> {/* do nothing */})
               .finallyDo(() -> finallyCounter.incrementAndGet())
               .start();
        
        assertEquals(1, finallyCounter.get());
    }

    @Test
    void testFinallyCallbackOnException() {
        AtomicInteger finallyCounter = new AtomicInteger(0);
        
        assertThrows(RuntimeException.class, () -> {
            Failsafe.run(() -> { throw new RuntimeException(); })
                   .finallyDo(() -> finallyCounter.incrementAndGet())
                   .start();
        });
        
        assertEquals(1, finallyCounter.get());
    }

    @Test
    void testSpecificExceptionHandling() {
        AtomicInteger illegalStateCounter = new AtomicInteger(0);
        AtomicInteger runtimeCounter = new AtomicInteger(0);
        
        Failsafe.run(() -> { throw new IllegalStateException(); })
               .onException(IllegalStateException.class)
                   .modify(() -> illegalStateCounter.incrementAndGet())
                   .ignore()
               .onException(RuntimeException.class)
                   .modify(() -> runtimeCounter.incrementAndGet())
                   .ignore()
               .start();
        
        assertEquals(1, illegalStateCounter.get());
        assertEquals(0, runtimeCounter.get());
    }

    @Test
    void testIterableExecution() {
        List<String> inputs = Arrays.asList("1", "2", "3");
        List<String> processed = new ArrayList<>();
        
        Failsafe.iterate(inputs, processed::add)
               .start();
        
        assertEquals(inputs, processed);
    }

    @Test
    void testModifyWithConsumer() {
        List<String> errorMessages = new ArrayList<>();
        
        Failsafe.run(() -> { throw new IllegalArgumentException("test message"); })
               .onException(IllegalArgumentException.class)
                   .modify(e -> errorMessages.add(e.getMessage()))
                   .ignore()
               .start();
        
        assertEquals(1, errorMessages.size());
        assertEquals("test message", errorMessages.get(0));
    }

    @Test
    void testExecuteCallback() {
        AtomicInteger callbackCounter = new AtomicInteger(0);
        
        Failsafe.run(() -> { throw new RuntimeException(); })
               .onException()
                .undo()
                .modify(callbackCounter::incrementAndGet)
                .ignore()
               .start();
        
        assertEquals(1, callbackCounter.get());
    }

    @Test
    void testMultipleExceptionHandlers() {
        AtomicInteger handler1Counter = new AtomicInteger(0);
        AtomicInteger handler2Counter = new AtomicInteger(0);
        
        Failsafe.run(() -> { throw new IllegalStateException(); })
               .onException(IllegalStateException.class)
                   .modify(handler2Counter::incrementAndGet)
                   .ignore()
               .onException(RuntimeException.class)
                   .modify(handler1Counter::incrementAndGet)
                   .ignore()
               .start();

        assertEquals(0, handler1Counter.get());
        assertEquals(1, handler2Counter.get());
    }

    @Test
    void testNoBaseActionProvided() {
        assertThrows(IllegalStateException.class, () -> {
            new Failsafe.FailsafeBuilder().start();
        });
    }

    @Test
    void testUnhandledException() {
        assertThrows(RuntimeException.class, () -> {
            Failsafe.run(() -> { throw new Exception("Unhandled"); })
                   .start();
        });
    }

    @Test
    void testSingleValueExecution() {
        AtomicInteger result = new AtomicInteger(0);
        
        Failsafe.run(42, result::set)
               .start();
        
        assertEquals(42, result.get());
    }

    @Test
    void testChainedRetryThenignore() {
        AtomicInteger retryCounter = new AtomicInteger(0);
        AtomicInteger ignoreCounter = new AtomicInteger(0);
        
        Failsafe.run(() -> { throw new RuntimeException("test"); })
               .onException(RuntimeException.class)
                   .modify(retryCounter::incrementAndGet)
                   .retry(2)
                   .modify(ignoreCounter::incrementAndGet)
                   .ignore()
               .start();
        
        assertEquals(2, retryCounter.get());
        assertEquals(1, ignoreCounter.get());
    }

    @Test
    void testChainedRetryThenUndoThenignore() {
        List<String> actions = new ArrayList<>();
        
        Failsafe.run(() -> { throw new RuntimeException("test"); })
               .onException(RuntimeException.class)
                   .modify(() -> actions.add("retry"))
                   .retry(1)
                   .modify(() -> actions.add("undo"))
                   .undo()
                   .modify(() -> actions.add("ignore"))
                   .ignore()
               .start();
        
        assertEquals(Arrays.asList("retry", "undo", "ignore"), actions);
    }

    @Test
    void testChainedWithConsumerModifiers() {
        List<String> errorMessages = new ArrayList<>();
        AtomicInteger retryCount = new AtomicInteger(0);
        
        Failsafe.run(() -> { 
            retryCount.incrementAndGet();
            throw new IllegalArgumentException("error " + retryCount.get());
        })
        .onException(IllegalArgumentException.class)
            .modify((e) -> errorMessages.add("Attempt " + retryCount.get() + ": " + e.getMessage()))
            .retry(2)
            .modify(() -> errorMessages.add("Giving up after " + retryCount.get() + " attempts"))
            .ignore()
        .start();
        
        assertEquals(Arrays.asList(
            "Attempt 1: error 1",
            "Attempt 2: error 2",
            "Giving up after 2 attempts"
        ), errorMessages);
    }

    @Test
    void testChainedWithDifferentExceptionTypes() {
        List<String> handlerOrder = new ArrayList<>();
        
        Failsafe.run(() -> {
            try {
                throw new IllegalStateException("state");
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("arg", e);
            }
        })
        .onException(IllegalArgumentException.class)
        .modify(() -> handlerOrder.add("arg-handler"))
            .retry(1)
            .ignore()
        .onException(IllegalStateException.class)
            .modify(() -> handlerOrder.add("state-handler"))
            .ignore()
        .start();
        
        assertEquals(Arrays.asList("arg-handler"), handlerOrder);
    }

    @Test
    void testChainedRetryWithMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger ignoreCount = new AtomicInteger(0);

        Failsafe.run(() -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("test");
                })
                .onException()
                    .retry(2)
                    .modify(ignoreCount::incrementAndGet)
                    .ignore()
                .start();
        
        assertEquals(2, attempts.get());
        assertEquals(1, ignoreCount.get());
    }

    @Test
    void testEmptyChainDefaultsToignore() {
        AtomicInteger counter = new AtomicInteger(0);
        
        Failsafe.run(() -> {
            counter.incrementAndGet();
            throw new RuntimeException();
        })
        .onException()
        .start();
        
        assertEquals(1, counter.get());
    }

    @Test
    void testModifierResetBetweenChains() {
        List<String> modifications = new ArrayList<>();
        
        Failsafe.run(() -> { throw new RuntimeException(); })
               .onException()
                   .modify(() -> modifications.add("first"))
                   .retry(1)
                   .ignore()  // Should not use the first modifier
               .start();
        
        assertEquals(Arrays.asList("first"), modifications);
    }
}