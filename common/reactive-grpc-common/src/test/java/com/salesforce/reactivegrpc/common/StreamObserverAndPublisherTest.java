/*
 *  Copyright (c) 2017, salesforce.com, inc.
 *  All rights reserved.
 *  Licensed under the BSD 3-Clause license.
 *  For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.reactivegrpc.common;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import io.grpc.stub.CallStreamObserver;
import io.reactivex.Flowable;
import io.reactivex.functions.Consumer;
import io.reactivex.internal.fuseable.QueueFuseable;
import io.reactivex.internal.fuseable.QueueSubscription;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subscribers.TestSubscriber;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class StreamObserverAndPublisherTest {

    @Parameterized.Parameters
    public static Object[][] data() {
        return new Object[5][0];
    }

    static final ExecutorService executorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    static final ExecutorService requestExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    final Queue<Throwable> unhandledThrowable = new ConcurrentLinkedQueue<Throwable>();

    public StreamObserverAndPublisherTest() {
    }

    @Before
    public void setUp() {
        RxJavaPlugins.setErrorHandler(new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) {
                unhandledThrowable.offer(throwable);
            }
        });
    }

    @Test
    public void multithreadingRegularTest() {
        TestStreamObserverAndPublisher<Integer> processor =
            new TestStreamObserverAndPublisher<Integer>(null);
        int countPerThread = 1000000;
        TestCallStreamObserverProducer observer =
            new TestCallStreamObserverProducer(executorService, processor, countPerThread);
        processor.onSubscribe(observer);
        final TestSubscriber<Integer> testSubscriber = Flowable
            .fromPublisher(processor)
            .test(0);

        for (int i = 0; i < countPerThread; i++) {
            requestExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    LockSupport.parkNanos(10);
                    testSubscriber.request(1);
                }
            });
        }

        Assertions.assertThat(testSubscriber.awaitTerminalEvent(1, TimeUnit.MINUTES)).isTrue();
        testSubscriber.assertValueCount(countPerThread);

        Assertions.assertThat(processor.outputFused).isFalse();
        Assertions.assertThat(observer.requestsQueue.size()).isBetween(countPerThread / 16, countPerThread / 16 + 1);
        Integer i;
        while ((i = observer.requestsQueue.poll()) != null) {
            Assertions.assertThat(i).isEqualTo(16);
        }
    }

    @Test
    public void multithreadingFussedTest() {

        TestStreamObserverAndPublisher<Integer> processor =
            new TestStreamObserverAndPublisher<Integer>(null);
        int countPerThread = 1000000;
        TestCallStreamObserverProducer observer =
            new TestCallStreamObserverProducer(executorService, processor, countPerThread);
        processor.onSubscribe(observer);
        final TestSubscriber<Integer> testSubscriber = Flowable
            .fromPublisher(processor)
            .subscribeWith(new FussedTestSubscriber<Integer>());

        for (int i = 0; i < countPerThread; i++) {
            requestExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    LockSupport.parkNanos(10);
                    testSubscriber.request(1);
                }
            });
        }

        Assertions.assertThat(testSubscriber.awaitTerminalEvent(1, TimeUnit.MINUTES)).isTrue();
        testSubscriber.assertValueCount(countPerThread);

        Assertions.assertThat(processor.outputFused).isTrue();
        Assertions.assertThat(observer.requestsQueue.size()).isBetween(countPerThread / 16, countPerThread / 16 + 1);
        Integer i;
        while ((i = observer.requestsQueue.poll()) != null) {
            Assertions.assertThat(i).isEqualTo(16);
        }
    }

    @Test
    public void shouldSupportOnlySingleSubscriberTest() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            final TestSubscriber<Integer> downstream1 = new TestSubscriber<Integer>(0);
            final TestSubscriber<Integer> downstream2 = new TestSubscriber<Integer>(0);
            final TestStreamObserverAndPublisher<Integer> processor = new TestStreamObserverAndPublisher<Integer>(null);
            final CountDownLatch latch = new CountDownLatch(1);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                    processor.subscribe(downstream1);
                    processor.onCompleted();
                }
            });
            latch.await();
            processor.subscribe(downstream2);
            processor.onCompleted();

            downstream1.awaitTerminalEvent();
            downstream2.awaitTerminalEvent();

            if (downstream1.errorCount() > 0) {
                downstream1.assertError(IllegalStateException.class)
                           .assertErrorMessage(
                               "TestStreamObserverAndPublisher allows only a single Subscriber");
            } else {
                downstream2.assertError(IllegalStateException.class)
                           .assertErrorMessage(
                               "TestStreamObserverAndPublisher allows only a single Subscriber");
            }
        }
    }

    @Test
    public void shouldSupportOnlySingleSubscriptionTest() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            final AtomicReference<Throwable> throwableAtomicReference = new AtomicReference<Throwable>();
            final TestStreamObserverAndPublisher<Integer> processor = new TestStreamObserverAndPublisher<Integer>(null);
            final TestCallStreamObserverProducer upstream = new TestCallStreamObserverProducer(executorService, processor, 100000000);
            final CountDownLatch latch = new CountDownLatch(1);
            final CountDownLatch throwingLatch = new CountDownLatch(1);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                    try {
                        processor.onSubscribe(upstream);

                    } catch (Throwable t) {
                        Assertions.assertThat(throwableAtomicReference.getAndSet(t)).isNull();
                        throwingLatch.countDown();
                    }
                }
            });
            latch.await();
            try {
                processor.onSubscribe(upstream);
            } catch (Throwable t) {
                Assertions.assertThat(throwableAtomicReference.getAndSet(t)).isNull();
                throwingLatch.countDown();
            }

            throwingLatch.await();

            Assertions.assertThat(upstream.requestsQueue).isEmpty();
            Assertions.assertThat(throwableAtomicReference.get())
                      .isExactlyInstanceOf(IllegalStateException.class)
                      .hasMessage("TestStreamObserverAndPublisher supports only a single subscription");
        }
    }

    @Test
    public void shouldSupportOnlySinglePrefetchTest() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            final TestSubscriber<Integer> downstream = new TestSubscriber<Integer>(0);
            final TestStreamObserverAndPublisher<Integer> processor = new TestStreamObserverAndPublisher<Integer>(null);
            final TestCallStreamObserverProducer upstream = new TestCallStreamObserverProducer(executorService, processor, 100000000);
            processor.onSubscribe(upstream);
            upstream.requested = 1; // prevents running elements sending but allows
            // checking how much elements requested at first
            processor.subscribe(downstream);

            for (int j = 0; j < 1000; j++) {
                final CountDownLatch latch = new CountDownLatch(1);
                executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        latch.countDown();
                        downstream.request(1);
                    }
                });
                latch.await();
                downstream.request(1);
            }

            Assertions.assertThat(upstream.requestsQueue)
                      .hasSize(1)
                      .containsOnly(16);
        }
    }

    static class FussedTestSubscriber<T> extends TestSubscriber<T> {
        public FussedTestSubscriber() {
            super(0);

            initialFusionMode = QueueSubscription.ANY;
        }
    }

    static class TestStreamObserverAndPublisher<T>
        extends AbstractStreamObserverAndPublisher<T>
        implements QueueSubscription<T> {

        public TestStreamObserverAndPublisher(
            com.salesforce.reactivegrpc.common.Consumer<CallStreamObserver<?>> onSubscribe) {
            super(new ConcurrentLinkedQueue<T>(), onSubscribe);
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & QueueFuseable.ASYNC) != 0) {
                outputFused = true;
                return QueueFuseable.ASYNC;
            }
            return QueueFuseable.NONE;
        }

        @Override
        public boolean offer(T t, T t1) {
            throw new UnsupportedOperationException();
        }
    }
}