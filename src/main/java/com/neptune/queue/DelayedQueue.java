package com.neptune.queue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * FIXME: Remove implementations that just wrappers super methods
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
    private ScheduledExecutorService scheduler;

    /**
     * The Future representation of the Consumer.
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
         * Remove the queue's head and fire the
         * {@link DelayedQueue#consume(DelayedItem)} method.
         * 
         * @return The element that was pulled.
         */
        public DelayedItem<I, D> call() {
            lock.lock();
            LOGGER.trace("call() {");

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
     * "Started" flag.
     */
    private boolean started;

    private ExecutorService callbacks;

    private ExecutorService runners;

    // FIXME this should be delegated to be client app
    private Map<Runnable, Future<?>> futures;

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     */
    public DelayedQueue() {
        super();
        init(1);
    }

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     * 
     * @param initialCapacity
     *            used to define the size of the queue, and also the capacity of
     *            the ThreadPool for callbacks
     */
    public DelayedQueue(int initialCapacity) {
        super(initialCapacity);
        init(initialCapacity);
    }

    /**
     * Initialize Executors and start thread
     * 
     * @param i
     *            number of threads to the pools
     */
    private void init(int i) {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Executors.privilegedThreadFactory());

        callbacks = Executors.newFixedThreadPool(i);
        runners = Executors.newFixedThreadPool(i);

        futures = new HashMap<Runnable, Future<?>>();
        this.start();
    }

    /**
     * Force a {@link DelayedQueue#reschedule(DelayedItem)}.
     */
    public void start() {
        LOGGER.info("Starting Delayed Queue");
        if (!started) {
            started = true;
            reschedule(peek());
        }
    }

    /**
     * Cancel the {@link DelayedQueue#future} and prevent any reschedule.
     */
    public void stop() {
        LOGGER.info("Stopping Delayed Queue");
        started = false;
        cancel();
    }

    /**
     * Cancel the {@link DelayedQueue#future}
     */
    private void cancel() {
        // Does not cancel a future that doesn't exist or a running task
        if (future != null && future.getDelay(TimeUnit.MILLISECONDS) > 0) {
            LOGGER.debug("Consumer registred to "
                    + future.getDelay(TimeUnit.MILLISECONDS) + "ms."
                    + " Canceling it...");

            future.cancel(false);
        } else {
            LOGGER.debug("Skipping cancelation because "
                    + "future doesnt exist or is being processed now");
        }
    }

    /**
     * Reschedule the {@link DelayedQueue#scheduler} with a "new" consumer.
     * 
     * @param item
     *            element that needs to be rescheduled!
     * @see DelayedQueue#consumer
     */
    private void reschedule(final DelayedItem<I, D> item) {
        LOGGER.trace("reschedule() {");

        if (!started) {
            return;
        }

        cancel();

        if (item != null) {
            // Calculate the waiting time for the head
            long waitingTime = item.getTime()
                    - DateTimeUtils.currentTimeMillis();

            if (waitingTime < 0) {
                LOGGER.info("Negative time! Was the Queue stopped? "
                        + "Setting time to NOW.");
                waitingTime = 0;
            }

            LOGGER.info("Scheduling element (" + item + ")" + " to "
                    + waitingTime + "ms");

            future = scheduler.schedule(consumer, waitingTime,
                    TimeUnit.MILLISECONDS);

        } else {
            LOGGER.info("Empty Queue");
        }

        LOGGER.trace("reschedule() }");
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
     *            The item that has been consumed
     */
    private void consume(final DelayedItem<I, D> item) {
        if (item.getData() != null
                && item.getData().getClass().isAssignableFrom(Runnable.class)) {
            LOGGER.info("Running 'data' because it is runnable!");

            runners.execute((Runnable) item.getData());
        }

        Runnable run = () -> {
            DelayedQueue.this.mOnTimeListener.onTime(item);
            LOGGER.debug(item + " consumed");
            futures.remove(this);
        };
        futures.put(run, callbacks.submit(run));
    }

    /**
     * Wait until all callback have executed. FIXME: This should be
     * reimplemented with different meaning
     */
    public void stay() {
        // Lock the queue because the Map could be edited
        // with more runnables otherwise
        lock.lock();
        LOGGER.trace("stay() {");

        try {
            for (Runnable r : futures.keySet()) {
                futures.get(r).get();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Future was interrupted!", e);
        } catch (ExecutionException e) {
            LOGGER.error("Execution had an error!", e);
        } catch (CancellationException e) {
            LOGGER.debug("Future was canceled");
        } finally {
            lock.unlock();
        }

        LOGGER.trace("stay() }");
    }

    /**
     * Get the item, waiting for its timeout to happen.
     *
     * @return the head element, null if it is not available or if error.
     * @throws CancellationException
     *             if the current head element was replaced
     */
    public DelayedItem<I, D> get() throws CancellationException {
        LOGGER.trace("get() {");

        try {
            if (future == null) {
                LOGGER.debug("Future is null");
                return null;
            } else {
                return future.get();
            }
        } catch (InterruptedException e) {
            LOGGER.debug("Current element cancelled");
            return null;
        } catch (ExecutionException e) {
            LOGGER.debug("Current element cancelled");
            return null;
        } finally {
            LOGGER.trace("get() }");
        }

    }

    /**
     * Depends on {@link DelayedQueue#offer(DelayedItem)} implementation.
     */
    @Override
    public void put(final DelayedItem<I, D> e) {
        super.put(e);
    }

    /**
     * Depends on {@link DelayedQueue#offer(DelayedItem)} implementation.
     */
    @Override
    public boolean add(final DelayedItem<I, D> e) {
        return super.add(e);
    }

    /**
     * Depends on {@link DelayedQueue#offer(DelayedItem)} implementation.
     */
    @Override
    public boolean addAll(final Collection<? extends DelayedItem<I, D>> c) {
        return super.addAll(c);
    }

    /**
     * Depends on {@link DelayedQueue#offer(DelayedItem)} implementation.
     */
    @Override
    public boolean offer(final DelayedItem<I, D> e, final long timeout,
            final TimeUnit unit) {
        return super.offer(e, timeout, unit);
    }

    /**
     * Basically all insertions depends on this method.
     */
    @Override
    public boolean offer(final DelayedItem<I, D> e) {
        // super value
        boolean returned = false;

        // locking
        lock.lock();

        try {
            // If the time has passed already,
            // doesn't even add to the queue
            if (e.getTime() < DateTimeUtils.currentTimeMillis() && started) {
                LOGGER.debug("Consuming " + e + " early");
                consume(e);
                returned = true;
            } else {
                // calling super
                returned = super.offer(e);

                // if it is necessary...
                if (returned && peek() == e) {
                    // reschedule
                    reschedule(peek());
                }
            }
        } finally {
            lock.unlock();
        }

        return returned;
    }

    /**
     * Depends on {@link DelayedQueue#poll()} implementation.
     */
    @Override
    public DelayedItem<I, D> remove() {
        return super.remove();
    }

    @Override
    public boolean remove(final Object o) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

        // locking
        lock.lock();

        try {
            // check if the head will be affected
            shouldNotify = o.equals(peek());

            // calling super
            returned = super.remove(o);

            // if it is necessary...
            if (returned && shouldNotify) {
                // reschedule
                reschedule(peek());
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> poll(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        // super value
        DelayedItem<I, D> returned;

        // locking
        lock.lock();

        try {
            // calling super
            returned = super.poll(timeout, unit);

            // if it is necessary...
            if (returned != null) {
                // reschedule
                reschedule(peek());
            }
        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> poll() {
        // super value
        DelayedItem<I, D> returned;

        // locking
        lock.lock();

        try {
            // calling super
            returned = super.poll();

            // if it is necessary...
            if (returned != null) {
                // reschedule
                reschedule(peek());
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public DelayedItem<I, D> take() throws InterruptedException {
        // super value
        DelayedItem<I, D> returned;

        // locking
        lock.lock();

        try {
            // calling super
            returned = super.take();

            // if it is necessary...
            if (returned != null) {
                // reschedule
                reschedule(peek());
            }

        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

        // locking
        lock.lock();

        try {
            // check if the head will be affected
            shouldNotify = c.contains(peek());

            // calling super
            returned = super.removeAll(c);

            if (returned && shouldNotify) {
                // reschedule
                reschedule(peek());
            }

            return returned;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

        // locking
        lock.lock();

        try {
            // check if the head will be affected
            shouldNotify = !c.contains(peek());

            // calling super
            returned = super.retainAll(c);

            // if it is necessary...
            if (returned && shouldNotify) {
                // reschedule
                reschedule(peek());
            }

            return returned;
        } finally {
            lock.unlock();
        }

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
        // super value
        int returned;

        // locking
        lock.lock();

        try {
            // calling super
            returned = super.drainTo(c, maxElements);

            // if it is necessary...
            if (returned > 0) {
                // reschedule
                reschedule(peek());
            }
        } finally {
            lock.unlock();
        }

        return returned;
    }

    @Override
    public void clear() {
        // locking
        lock.lock();

        try {
            // calling super
            super.clear();

            // reschedule
            reschedule(null);
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
