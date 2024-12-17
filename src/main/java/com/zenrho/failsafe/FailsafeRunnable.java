package com.zenrho.failsafe;

@FunctionalInterface
public interface FailsafeRunnable {
    void run() throws Exception;
}
