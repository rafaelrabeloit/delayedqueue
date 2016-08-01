package com.neptune.queue;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Delayed Queue collection class. It is a blocking queue {@link Delayed} should
 * be used as item, because it ensures timing.
 *
 * There are 3 thread systems running for each instance of this class. The
 * Consumer thread is a single instance thread that is scheduled when an element
 * should be consumed in the future. The OnTime Threads are the ones that are
 * fired for the Listener, and therefore the listener events happens in those
 * threads and not in the main. The Data Thread are threads that can be created
 * if the object Data D implements runnable, and can be executed when the item
 * is consumed. FIXME: Remove implementations that just wrappers super methods
 * 
 * @author Rafael
 *
 * @param <E>
 *            The element type for this queue. Should implement {@link Delayed}
 *
 */
public final class DelayedQueue<E extends Delayed>
        extends PriorityBlockingQueue<E> {

    /**
     * UID serial version.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The time unit used as minimal unit measurable by this scheduler.
     */
    private static final TimeUnit TIMEUNIT = TimeUnit.MILLISECONDS;

    /**
     * Logger.
     */
    private static final Logger LOGGER = LogManager
            .getLogger(DelayedQueue.class);

    /**
     * Dummy listener to avoid null pointer exceptions.
     */
    private static final OnTimeListener<?> DUMMY = new OnTimeListener<Delayed>() {
        @Override
        public void onTime(final Delayed e) {
        }
    };

    /**
     * Timing listener. Starts with null, but is assigned to a dummy later.
     */
    @SuppressWarnings("unchecked")
    private OnTimeListener<E> mOnTimeListener = (OnTimeListener<E>) DUMMY;

    /**
     * Lock used in blocking operations.
     */
    private final ReentrantLock lock;

    /**
     * Executor that runs this Delayed Queue.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * The Future representation of the Consumer.
     */
    private transient volatile ScheduledFuture<E> future;

    /**
     * This class is responsible for Consuming the Queue elements and fire the
     * listener methods when appropriate. It is a class used as one instance
     * only, because 1) Hides the run method ensuring that this is the only way
     * to control the DelayedQueue 2) It is a better pattern and 3) The Thread
     * Pool can take advantage of having the same Runnable firing multiple
     * times.
     */
    private Callable<E> consumer = new Callable<E>() {

        /**
         * Remove the queue's head and fire the {@link DelayedQueue#consume(E)}
         * method.
         * 
         * @return The element that was pulled.
         */
        public E call() {
            LOGGER.trace("call() {");

            E item = DelayedQueue.this.poll();
            LOGGER.debug("Consuming (" + item + ")");

            DelayedQueue.this.consume(item);

            LOGGER.trace("call() }");
            return item;
        }
    };

    /**
     * "Started" flag.
     */
    private boolean started;

    /**
     * Executor for callback threads.
     * Callback have their own thread because the scheduler thread cannot stop
     */
    private ExecutorService callbacks;

    /**
     * Executor for item threads.
     * If item also implement a Runnable, then it will execute in this executor
     * when is consumed.
     */
    private ExecutorService runners;

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     */
    public DelayedQueue() {
        this(1);
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

        this.lock = new ReentrantLock();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Executors.privilegedThreadFactory());

        this.callbacks = Executors.newFixedThreadPool(initialCapacity);
        this.runners = Executors.newFixedThreadPool(initialCapacity);

        this.start();
    }

    /**
     * Force a {@link DelayedQueue#reschedule(Delayed)}.
     */
    public void start() {
        LOGGER.info("Starting Delayed Queue");
        if (!this.started) {
            this.started = true;
            this.reschedule(this.peek());
        }
    }

    /**
     * Cancel the {@link DelayedQueue#future} and prevent any reschedule.
     */
    public void stop() {
        LOGGER.info("Stopping Delayed Queue");
        this.started = false;
        this.cancel();
    }

    /**
     * Cancel the {@link DelayedQueue#future}
     */
    private void cancel() {
        // Does not cancel a future that doesn't exist or a running task
        if (this.future != null && this.future.getDelay(TIMEUNIT) > 0) {
            LOGGER.debug(
                    "Consumer registred to " + this.future.getDelay(TIMEUNIT)
                            + " " + TIMEUNIT.toString() + ". Cancelling it...");

            this.future.cancel(false);
        } else {
            LOGGER.debug("Skipping cancellation because "
                    + "future doesn't exist or is being processed now");
        }
    }

    /**
     * Reschedule the {@link DelayedQueue#scheduler} with a "new" consumer.
     * 
     * @param item
     *            element that needs to be rescheduled!
     * @see DelayedQueue#consumer
     */
    private synchronized void reschedule(final E item) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        LOGGER.trace("reschedule() {");

        try {
            if (!this.started) {
                return;
            }

            cancel();

            if (item != null) {
                // Calculate the waiting time for the head
                long waitingTime = item.getDelay(TIMEUNIT);

                if (waitingTime < 0) {
                    LOGGER.info("Negative time! Was the Queue stopped? "
                            + "Setting time to NOW.");
                    waitingTime = 0;
                }

                LOGGER.info("Scheduling element (" + item + ")" + " to "
                        + waitingTime + " " + TIMEUNIT.toString());

                this.future = this.scheduler.schedule(this.consumer,
                        waitingTime, TIMEUNIT);

            } else {
                LOGGER.info("Empty Queue");
            }
        } finally {
            LOGGER.trace("reschedule() }");
            lock.unlock();
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
    public void setOnTimeListener(final OnTimeListener<E> onTimeListener) {
        if (onTimeListener != null) {
            this.mOnTimeListener = onTimeListener;
        } else {
            // Empty listener that doesn't cause null pointer exceptions
            this.mOnTimeListener = (OnTimeListener<E>) DUMMY;
        }
    }

    /**
     * Consumes an item. It creates a separated thread for the listener method
     * call. Also, if the Data element implements Runnable, runs it.
     * 
     * @param item
     *            The item that has been consumed
     */
    private void consume(final E item) {
        if (item != null && item.getClass().isAssignableFrom(Runnable.class)) {
            LOGGER.info("Running 'data' because it is runnable!");

            this.runners.execute((Runnable) item);
        }

        Runnable run = () -> {
            DelayedQueue.this.mOnTimeListener.onTime(item);
            LOGGER.debug(item + " consumed");
        };
        callbacks.submit(run);
    }

    /**
     * Wait until all elements are processed.
     */
    public void stay() {
        LOGGER.trace("stay() {");

        if (future != null) {
            while (super.size() > 0 && this.started) {
                try {
                    future.get();
                } catch (CancellationException e) {
                    LOGGER.debug("Soft Failing at stay() cancellation");
                } catch (InterruptedException | ExecutionException e) {
                    LOGGER.error(e);
                    break;
                }
            }
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
    public E get() throws CancellationException {
        LOGGER.trace("get() {");

        try {
            if (this.future == null) {
                LOGGER.debug("Future is null");
                return null;
            } else {
                return this.future.get();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Current element interrupted");
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("Current element exception");
            return null;
        } finally {
            LOGGER.trace("get() }");
        }

    }

    /**
     * Depends on {@link DelayedQueue#offer(Delayed)} implementation.
     */
    @Override
    public void put(final E e) {
        super.put(e);
    }

    /**
     * Depends on {@link DelayedQueue#offer(Delayed)} implementation.
     */
    @Override
    public boolean add(final E e) {
        return super.add(e);
    }

    /**
     * Depends on {@link DelayedQueue#offer(Delayed)} implementation.
     */
    @Override
    public boolean addAll(final Collection<? extends E> c) {
        return super.addAll(c);
    }

    /**
     * Depends on {@link DelayedQueue#offer(Delayed)} implementation.
     */
    @Override
    public boolean offer(final E e, final long timeout, final TimeUnit unit) {
        return super.offer(e, timeout, unit);
    }

    /**
     * Basically all insertions depends on this method.
     */
    @Override
    public boolean offer(final E e) {
        // super value
        boolean returned = false;

        // If the time has passed already,
        // doesn't even add to the queue
        if (e.getDelay(TIMEUNIT) <= 0 && this.started) {
            LOGGER.debug("Consuming " + e + " early");
            this.consume(e);
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

        return returned;
    }

    /**
     * Depends on {@link DelayedQueue#poll()} implementation.
     */
    @Override
    public E remove() {
        return super.remove();
    }

    @Override
    public boolean remove(final Object o) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

        // check if the head will be affected
        shouldNotify = o.equals(peek());

        // calling super
        returned = super.remove(o);

        // if it is necessary...
        if (returned && shouldNotify) {
            // reschedule
            reschedule(peek());
        }

        return returned;
    }

    @Override
    public E poll(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        // super value
        E returned;

        // calling super
        returned = super.poll(timeout, unit);

        // if it is necessary...
        if (returned != null) {
            // reschedule
            reschedule(peek());
        }

        return returned;
    }

    @Override
    public E poll() {
        // super value
        E returned;

        // calling super
        returned = super.poll();

        // if it is necessary...
        if (returned != null) {
            // reschedule
            reschedule(peek());
        }

        return returned;
    }

    @Override
    public E take() throws InterruptedException {
        // super value
        E returned;

        // calling super
        returned = super.take();

        // if it is necessary...
        if (returned != null) {
            // reschedule
            reschedule(peek());
        }

        return returned;
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

        // check if the head will be affected
        shouldNotify = c.contains(peek());

        // calling super
        returned = super.removeAll(c);

        if (returned && shouldNotify) {
            // reschedule
            reschedule(peek());
        }

        return returned;

    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        // super value
        boolean returned = false;

        // flag to check if the head is affected
        boolean shouldNotify = false;

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

    }

    /**
     * Depends on drainTo() implementation.
     */
    @Override
    public int drainTo(final Collection<? super E> c) {
        return super.drainTo(c);
    }

    @Override
    public int drainTo(final Collection<? super E> c, final int maxElements) {
        // super value
        int returned;

        // calling super
        returned = super.drainTo(c, maxElements);

        // if it is necessary...
        if (returned > 0) {
            // reschedule
            reschedule(peek());
        }

        return returned;
    }

    @Override
    public void clear() {
        // calling super
        super.clear();

        // reschedule
        reschedule(null);
    }

    /**
     * Interface for Listening on timings.
     *
     * @param <E>
     *            The element type for this queue. Should implement
     *            {@link Delayed}
     */
    public interface OnTimeListener<E> {
        /**
         * Fired when a thread finishes waiting.
         * 
         * @param e
         *            element that was fired
         */
        void onTime(E e);
    }

}
