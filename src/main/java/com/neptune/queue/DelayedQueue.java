package com.neptune.queue;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTimeUtils;

/**
 * Delayed Queue collection class. It is a blocking queue {@link DelayedItem}
 * should be used as item, because it ensures timing and id.
 *
 * There are 3 thread systems running for each instance of this class. The
 * Consumer thread is a single instance thread that is scheduled when an element
 * should be consumed in the future. The OnTime Threads are the ones that are
 * fired for the Listener, and therefore the listener events happens in those
 * threads and not in the main. The Data Thread are threads that can be created
 * if the object Data D implements runnable, and can be executed when the item
 * is consumed.
 * 
 * @author Rafael
 *
 * @param <I>
 *            The type for Id
 * @param <D>
 *            The type for Data
 *
 * @see DelayedItem
 */
public final class DelayedQueue<I, D>
        extends PriorityBlockingQueue<DelayedItem<I, D>> {

    /**
     * Logger.
     */
    static final Logger LOGGER = LogManager.getLogger(DelayedQueue.class);

    /**
     * UID serial version.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Dummy listener to avoid null pointer exceptions.
     */
    private static final OnTimeListener<Object, Object> DUMMY = new OnTimeListener<Object, Object>() {
        @Override
        public void onTime(final DelayedItem<Object, Object> e) {
        }
    };

    /**
     * Timing listener. Starts with null, but is assigned to a dummy later.
     */
    @SuppressWarnings("unchecked")
    private OnTimeListener<I, D> mOnTimeListener = (OnTimeListener<I, D>) DUMMY;

    /**
     * Lock used in blocking operations.
     */
    private Lock lock = new ReentrantLock();

    /**
     * Executor that runs this Delayed Queue.
     */
    private ScheduledExecutorService executor;

    /**
     * The Future representation of the Consumer
     */
    private ScheduledFuture<DelayedItem<I, D>> future;

    /**
     * This class is responsible for Consuming the Queue elements and fire the
     * listener methods when appropriate. It is a class used as one instance
     * only, because 1) Hides the run method ensuring that this is the only way
     * to control the DelayedQueue 2) It is a better pattern and 3) The Thread
     * Pool can take advantage of having the same Runnable firing multiple
     * times.
     */
    private Callable<DelayedItem<I, D>> consumer = new Callable<DelayedItem<I, D>>() {

        /**
         * Remove the queue's head and fire the {@link OnTimeListener#onTime}
         * event.
         * 
         * @see OnTimeListener#onTime
         */
        public DelayedItem<I, D> call() {
            LOGGER.trace("call() {");
            lock.lock();

            try {

                DelayedItem<I, D> item = DelayedQueue.this.poll();
                LOGGER.debug("Consuming (" + item + ")");

                DelayedQueue.this.consume(item);

                return item;
            } finally {
                lock.unlock();
                LOGGER.trace("call() }");
            }
        }
    };

    /**
     * Consuming Thread(s) state.
     */
    private boolean started;

    /**
     * Initialize the Queue with an Executor and a Thread Timer in it.
     */
    public DelayedQueue() {
        super();

        executor = Executors.newScheduledThreadPool(1);
        this.start();
    }

    /**
     * Start the thread executor, and the thread itself.
     */
    public void start() {
        LOGGER.info("Starting Delayed Queue");
        if (!started) {
            started = true;
            reschedule();
        }
    }

    /**
     * Shutdown the thread executor, and the thread by consequence, by
     * interrupting it.
     */
    public void stop() {
        LOGGER.info("Stopping Delayed Queue");
        started = false;
        interrupt();
    }

    /**
     * Interrupt a waiting thread and wait for it to finish.
     */
    private void interrupt() {
        // Does not cancel a running task
        if (future != null && future.getDelay(TimeUnit.MILLISECONDS) <= 0) {
            LOGGER.debug("Consumer registred, interrupting it...");
            future.cancel(false);
        }
    }

    /**
     * Reschedule the queue thread.
     */
    private void reschedule() {
        LOGGER.trace("reschedule() {");
        
        if (!started) {
            return;
        }

        lock.lock();

        try {
            interrupt();

            if (peek() != null) {
                // Calculate the waiting time for the HEAD
                long waitingTime = peek().getTime()
                        - DateTimeUtils.currentTimeMillis();

                if (waitingTime < 0) {
                    LOGGER.info("Negative time! Was the Queue stopped? "
                            + "Setting time to NOW.");
                    waitingTime = 0;
                }

                LOGGER.info("Scheduling element (" + peek() + ")" + " to "
                        + waitingTime + " ms");

                future = executor.schedule(consumer, waitingTime,
                        TimeUnit.MILLISECONDS);

            } else {
                LOGGER.info("Empty Queue");
            }
        } finally {
            lock.unlock();
            LOGGER.trace("reschedule() }");
        }
    }

    /**
     * Set the listener for when the timer for the top object is fired. Setting
     * it to null will disable listening.
     * 
     * @param onTimeListener
     *            Listener to be used when a element is fired
     */
    @SuppressWarnings("unchecked")
    public void setOnTimeListener(final OnTimeListener<I, D> onTimeListener) {
        if (onTimeListener != null) {
            this.mOnTimeListener = onTimeListener;
        } else {
            // Empty listener that doesn't cause null pointer exceptions
            this.mOnTimeListener = (OnTimeListener<I, D>) DUMMY;
        }
    }

    /**
     * Consumes an item. It creates a separated thread for the listener method
     * call. Also, if the Data element implements Runnable, runs it.
     * 
     * @param item
     */
    private void consume(DelayedItem<I, D> item) {
        if (item.getData() != null
                && item.getData().getClass().isAssignableFrom(Runnable.class)) {

            Runnable runnableData = (Runnable) item.getData();

            Thread threadData = new Thread(runnableData);
            threadData.start();

        }

        Runnable runnableOnTime = () -> {
            DelayedQueue.this.mOnTimeListener.onTime(item);
            LOGGER.debug("Consumed");
        };

        Thread threadOnTime = new Thread(runnableOnTime);
        threadOnTime.start();
    }

    /**
     * Get the item, waiting for its timeout to happen
     * @return the head element, null if it is not available or if error.
     * @throws InterruptedException if the consumer thread is 
     *         interrupted (probably never)
     * @throws ExecutionException if the consumer thread fired an exception
     */
    public DelayedItem<I, D> get()
            throws InterruptedException, ExecutionException {
        LOGGER.trace("get() {");
        try {
//            if (future == null) {
//                return null;
//            } else {
//                return future.get();
//            }
            ScheduledFuture<DelayedItem<I, D>> future = this.future;
            while(!future.isDone()) {
                Thread.sleep(10);
                LOGGER.debug("Timing is " + future.getDelay(TimeUnit.MILLISECONDS));
            }
            return null;
        } catch (CancellationException e) {
            return null;
        } finally {
            LOGGER.trace("get() }");
        }
        
    }

    /**
     * Depends on offer() implementation.
     */
    @Override
    public void put(final DelayedItem<I, D> e) {
        super.put(e);
    }

    /**
     * Depends on offer() implementation.
     */
    @Override
    public boolean add(final DelayedItem<I, D> e) {
        return super.add(e);
    }

    /**
     * Depends on offer() implementation.
     */
    @Override
    public boolean addAll(final Collection<? extends DelayedItem<I, D>> c) {
        return super.addAll(c);
    }

    /**
     * Depends on offer() implementation.
     */
    @Override
    public boolean offer(final DelayedItem<I, D> e, final long timeout,
            final TimeUnit unit) {
        return super.offer(e, timeout, unit);
    }

    @Override
    public boolean offer(final DelayedItem<I, D> e) {

        boolean returned = false;

        lock.lock();

        try {

            // If the time has passed already,
            // doesn't even add to the queue
            if (e.getTime() < DateTimeUtils.currentTimeMillis() && started) {
                LOGGER.debug("Consuming Early");
                consume(e);

                return true;
            } else {

                returned = super.offer(e);

                if (returned && peek() == e) {
                    reschedule();
                }
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    /**
     * Depends on poll() implementation.
     */
    @Override
    public DelayedItem<I, D> remove() {
        return super.remove();
    }

    @Override
    public boolean remove(final Object o) {

        boolean returned = false;
        boolean shouldNotify = false;

        lock.lock();

        try {

            shouldNotify = o.equals(peek());
            returned = super.remove(o);

            if (returned && shouldNotify) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> poll(final long timeout, final TimeUnit unit)
            throws InterruptedException {

        DelayedItem<I, D> returned;

        lock.lock();

        try {
            returned = super.poll(timeout, unit);

            if (returned != null) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> poll() {

        DelayedItem<I, D> returned;

        lock.lock();

        try {

            returned = super.poll();

            if (returned != null) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> take() throws InterruptedException {

        DelayedItem<I, D> returned;

        lock.lock();

        try {

            returned = super.take();

            if (returned != null) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public boolean removeAll(final Collection<?> c) {

        boolean returned = false;
        boolean shouldNotify = false;

        lock.lock();

        try {

            shouldNotify = c.contains(peek());
            returned = super.removeAll(c);

            if (returned && shouldNotify) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public boolean retainAll(final Collection<?> c) {

        boolean returned = false;
        boolean shouldNotify = false;

        lock.lock();

        try {

            shouldNotify = !c.contains(peek());
            returned = super.retainAll(c);

            if (returned && shouldNotify) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    /**
     * Depends on drainTo() implementation.
     */
    @Override
    public int drainTo(final Collection<? super DelayedItem<I, D>> c) {
        return super.drainTo(c);
    }

    @Override
    public int drainTo(final Collection<? super DelayedItem<I, D>> c,
            final int maxElements) {

        int returned;

        lock.lock();

        try {

            returned = super.drainTo(c, maxElements);

            if (returned > 0) {
                reschedule();
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public void clear() {

        lock.lock();

        try {

            super.clear();

            reschedule();

        } finally {
            lock.unlock();
        }
    }

    /**
     * Interface for Listening on timings.
     *
     * @param <I>
     *            The type for Id
     * @param <D>
     *            The type for Data
     */
    public interface OnTimeListener<I, D> {
        /**
         * Fired when a thread finishes waiting.
         * 
         * @param e
         *            element that was fired
         */
        void onTime(DelayedItem<I, D> e);
    }

}
