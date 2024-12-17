package com.zenrho.failsafe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Failsafe {
    enum Strategy {
        CONTINUE,
        STOP,
        THROW,
        NO_THROW;

        static Strategy combine(Strategy... strategies) {
            Strategy result = CONTINUE;
            for (Strategy strategy : strategies) {
                result = combine(result, strategy);
            }
            return result;
        }

        static Strategy combine(Strategy a, Strategy b) {
            if (a != NO_THROW && b != NO_THROW)
                if (a == THROW || b == THROW) return THROW;
            if (a == CONTINUE || b == CONTINUE) return CONTINUE;
            return STOP;
        }
    }

    private interface Action {
        /**
         * Execute the logic associated with this action when handling an exception.
         *
         * @param builder The current failsafe builder.
         * @param e The exception that triggered this action.
         * @param attempt The current attempt count (1-based).
         * @param maxAttempts The maximum number of attempts allowed by this action.
         * @return true if done (stop), false if continue retrying.
         */
        Strategy execute(FailsafeBuilder builder, Exception e, int attempt, int maxAttempts);
    }

    private static final Action IGNORE = (builder, e, attempt, maxAttempts) -> Strategy.NO_THROW;
    private static final Action UNDO = (builder, e, attempt, maxAttempts) -> Strategy.STOP;
    private static final Action STOP = (builder, e, attempt, maxAttempts) -> Strategy.STOP;
    private static final Action THROW = new Action() {
        @Override
        public Strategy execute(FailsafeBuilder builder, Exception e, int attempt, int maxAttempts) {
            return Strategy.THROW;
        }
    };

    public static FailsafeBuilder run(FailsafeRunnable code) {
        return new FailsafeBuilder().run(code);
    }

    public static <T> FailsafeBuilder iterate(Iterable<T> values, Consumer<T> code) {
        return new FailsafeBuilder().run(values, code);
    }

    public static <T> FailsafeBuilder run(T value, Consumer<T> code) {
        return new FailsafeBuilder().run(value, code);
    }

    public static class FailsafeBuilder {
        private FailsafeRunnable baseAction;
        private FailsafeRunnable finalAction;
        private FailsafeRunnable successAction;

        private final List<ExceptionHandler<? extends Exception>> exceptionHandlers = new ArrayList<>();

        private FailsafeBuilder run(FailsafeRunnable code) {
            this.baseAction = code;
            return this;
        }

        private <T> FailsafeBuilder run(Iterable<T> values, Consumer<T> code) {
            this.baseAction = () -> {
                for (T val : values) {
                    code.accept(val);
                }
            };
            return this;
        }

        private <T> FailsafeBuilder run(T value, Consumer<T> code) {
            this.baseAction = () -> code.accept(value);
            return this;
        }

        public OnExceptionBuilder<Exception> onException() {
            return new OnExceptionBuilder<>(Exception.class, this);
        }

        public <E extends Exception> OnExceptionBuilder<E> onException(Class<E> exceptionType) {
            return new OnExceptionBuilder<>(exceptionType, this);
        }

        public FailsafeBuilder onSuccess(FailsafeRunnable action) {
            this.successAction = action;
            return this;
        }

        public FailsafeBuilder finallyDo(FailsafeRunnable action) {
            this.finalAction = action;
            return this;
        }

        public void start() {
            try {
                execute();
            } catch (Exception e) {
                throw asRuntimeException(e);
            }

        }

        private void execute() throws Exception {
            if (baseAction == null) {
                throw new IllegalStateException("No base action provided. Call run(...) before execute().");
            }

            try {
                runWithHandlers();
                if (successAction != null) {
                    successAction.run();
                }
            } finally {
                if (finalAction != null) {
                    finalAction.run();
                }
            }
        }

        private void runWithHandlers() {
            int attempt = 0;
            boolean done = false;

            while (!done) {
                attempt++;
                try {
                    baseAction.run();
                    done = true;
                } catch (Exception e) {
                    ExceptionHandler<? extends Exception> handler = findMatchingHandler(e);
                    if (handler == null) {
                        System.out.println("Unhandled exception: " + e);
                        throw asRuntimeException(e);
                    }

                    // Apply strategy modifiers
                    handler.applyStrategy(e);

                    // If exception occurred and action triggered, run the post callback if any
                    if (handler.getPostCallback() != null) {
                        handler.getPostCallback().run();
                    }

                    // Execute the action
                    Strategy result = handler.getAction().execute(this, e, attempt, handler.getMaxAttempts());

                    if (result == Strategy.THROW) {
                        throw asRuntimeException(e);
                    }

                    System.out.println("Combined Result: " + result);

                    done = result == Strategy.STOP;
                }
            }
        }

        private ExceptionHandler<? extends Exception> findMatchingHandler(Exception e) {
            for (ExceptionHandler<? extends Exception> handler : exceptionHandlers) {
                if (handler.handles(e)) {
                    return handler;
                }
            }
            return null;
        }

        void addExceptionHandler(ExceptionHandler<? extends Exception> handler) {
            exceptionHandlers.add(handler);
        }
    }

    private static RuntimeException asRuntimeException(Exception e) {
        return (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
    }

    private static class ExceptionHandler<E extends Exception> {
        private final Class<E> type;
        private final Action action;
        private final Runnable strategyModifierRunnable;
        private final Consumer<E> strategyModifierConsumer;
        private final int maxAttempts;
        private final Runnable postCallback;

        ExceptionHandler(
                Class<E> type,
                Action action,
                Runnable strategyModifierRunnable,
                Consumer<E> strategyModifierConsumer,
                int maxAttempts,
                Runnable postCallback
        ) {
            this.type = type;
            this.action = action;
            this.strategyModifierRunnable = strategyModifierRunnable;
            this.strategyModifierConsumer = strategyModifierConsumer;
            this.maxAttempts = maxAttempts;
            this.postCallback = postCallback;
        }

        boolean handles(Exception e) {
            return type.isAssignableFrom(e.getClass());
        }

        Action getAction() {
            return action;
        }

        int getMaxAttempts() {
            return maxAttempts;
        }

        Runnable getPostCallback() {
            return postCallback;
        }

        void applyStrategy(Exception e) {
            if (strategyModifierRunnable != null) {
                strategyModifierRunnable.run();
            } else if (strategyModifierConsumer != null) {
                @SuppressWarnings("unchecked")
                E castException = (E) e;
                strategyModifierConsumer.accept(castException);
            }
        }
    }

    public static class OnExceptionBuilder<E extends Exception> {
        private final Class<E> exceptionType;
        private final FailsafeBuilder parent;

        private Runnable strategyModifierRunnable;
        private Consumer<E> strategyModifierConsumer;
        private Runnable postCallback;
        private final List<ActionChain> actionChain = new ArrayList<>();

        private static class ActionChain {
            final Action action;
            final Runnable strategyModifier;
            final Consumer<?> strategyModifierConsumer;
            final int maxAttempts;

            ActionChain(Action action, Runnable modifier, Consumer<?> modifierConsumer, int maxAttempts) {
                this.action = action;
                this.strategyModifier = modifier;
                this.strategyModifierConsumer = modifierConsumer;
                this.maxAttempts = maxAttempts;
            }
        }

        OnExceptionBuilder(Class<E> exceptionType, FailsafeBuilder parent) {
            this.exceptionType = exceptionType;
            this.parent = parent;
        }

        public OnExceptionBuilder<E> retry(int attempts) {
            return retry(attempts, THROW); // Default to throwing after retries exhausted
        }

        public OnExceptionBuilder<E> retry(int attempts, Action afterRetries) {
            actionChain.add(new ActionChain(new RetryAction(attempts, afterRetries), 
                                           strategyModifierRunnable, 
                                           strategyModifierConsumer, 
                                           attempts));
            resetModifiers();
            return this;
        }

        public FailsafeBuilder ignore() {
            actionChain.add(new ActionChain(IGNORE, strategyModifierRunnable, strategyModifierConsumer, -1));
            resetModifiers();
            return and();
        }

        public OnExceptionBuilder<E> undo() {
            actionChain.add(new ActionChain(UNDO, strategyModifierRunnable, strategyModifierConsumer, -1));
            resetModifiers();
            return this;
        }

        public OnExceptionBuilder<E> modify(Runnable modifier) {
            this.strategyModifierRunnable = modifier;
            return this;
        }

        public OnExceptionBuilder<E> modify(Consumer<E> modifier) {
            this.strategyModifierConsumer = modifier;
            return this;
        }

        public FailsafeBuilder finallyDo(Runnable callback) {
            this.postCallback = callback;
            return and();
        }

        private void resetModifiers() {
            strategyModifierRunnable = null;
            strategyModifierConsumer = null;
        }

        public FailsafeBuilder and() {
            finalizeHandler();
            return parent;
        }

        private void finalizeHandler() {
            if (actionChain.isEmpty()) {
                actionChain.add(new ActionChain(IGNORE, null, null, -1));
            }
            
            ExceptionHandler<E> handler = new ChainedExceptionHandler<>(
                    exceptionType,
                    actionChain,
                    postCallback
            );
            parent.addExceptionHandler(handler);
        }

        public void start() {
            finalizeHandler();
            parent.start();
        }
    }

    private static class ChainedExceptionHandler<E extends Exception> extends ExceptionHandler<E> {
        ChainedExceptionHandler(
                Class<E> type,
                List<OnExceptionBuilder.ActionChain> actionChain,
                Runnable postCallback
        ) {
            super(type, 
                (builder, e, attempt, maxAttempts) -> {
                    Strategy result = Strategy.STOP;
                    for (OnExceptionBuilder.ActionChain chain : actionChain) {
                        // Execute pre-retry modifiers
                        if (chain.strategyModifier != null) {
                            chain.strategyModifier.run();
                        }

                        if (chain.strategyModifierConsumer != null) {
                            @SuppressWarnings("unchecked")
                            Consumer<E> consumer = (Consumer<E>) chain.strategyModifierConsumer;
                            consumer.accept((E) e);
                        }

                        // Execute the action
                        Strategy current = chain.action.execute(builder, e, attempt, chain.maxAttempts);
                        result = Strategy.combine(result, current);

                        System.out.println("Result: " + result + " Current: " + current);

                        if (result == Strategy.CONTINUE) {
                            return Strategy.CONTINUE;
                        }
                    }
                    return result;
                },
                null, null, -1, postCallback);
        }
    }

    private static class RetryAction implements Action {
        private final int maxAttempts;
        private final Action afterRetries;

        RetryAction(int maxAttempts, Action afterRetries) {
            this.maxAttempts = maxAttempts;
            this.afterRetries = afterRetries;
        }

        @Override
        public Strategy execute(FailsafeBuilder builder, Exception e, int attempt, int maxAttempts) {
            if (attempt < this.maxAttempts) {
                return Strategy.CONTINUE; // Continue retrying
            }
            // Max attempts reached, execute the after-retry action
            return afterRetries.execute(builder, e, attempt, maxAttempts);
        }
    }
}

