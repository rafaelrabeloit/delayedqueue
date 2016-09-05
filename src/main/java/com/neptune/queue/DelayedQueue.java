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
 * is consumed.
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
     * Default array capacity.
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 11;

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
    private static final OnTimeListener<Delayed> DUMMY = new OnTimeListener<Delayed>() {
        @Override
        public void onTime(final Delayed e) {
        }

        @Override
        public Delayed onBeforeRun(Delayed e) {
            return null;
        }
    };

    /**
     * Timing listener. Starts with null, but is assigned to a dummy later.
     */
    @SuppressWarnings("unchecked")
    private transient OnTimeListener<E> mOnTimeListener = (OnTimeListener<E>) DUMMY;

    /**
     * Lock used in blocking operations.
     */
    private final transient ReentrantLock lock;

    /**
     * Executor that runs this Delayed Queue.
     */
    private transient ScheduledExecutorService scheduler;

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
    private final transient Callable<E> consumer = new Callable<E>() {

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
     * Executor for callback threads. Callback have their own thread because the
     * scheduler thread cannot stop
     */
    private transient ExecutorService callbacks;

    private int capacity = DEFAULT_INITIAL_CAPACITY;

    private boolean grows;

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     */
    public DelayedQueue() {
        this(DEFAULT_INITIAL_CAPACITY, true);
    }

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     * 
     * @param initialCapacity
     *            used to define the size of the queue, and also the capacity of
     *            the ThreadPool for callbacks
     */
    public DelayedQueue(final int initialCapacity) {
        this(initialCapacity, true);
    }

    /**
     * Initialize the Queue and its {@link ScheduledExecutorService}.
     * 
     * @param initialCapacity
     *            used to define the size of the queue, and also the capacity of
     *            the ThreadPool for callbacks
     * @param shouldGrow
     *            define if the queue is bounded by its initial capacity.
     *            Meaning that it will never have more elements than its initial
     *            size
     */
    public DelayedQueue(final int initialCapacity, boolean shouldGrow) {
        // add 1 if the queue cannot grow because that will be the max size
        // The queue actually will grow in 1 if a more priority element wants
        // to be added
        super(initialCapacity + (shouldGrow ? 0 : 1));

        this.lock = new ReentrantLock();
        this.capacity = initialCapacity;
        this.grows = shouldGrow;

        this.start();
    }

    /**
     * Force a {@link DelayedQueue#reschedule(Delayed)}.
     */
    public void start() {
        LOGGER.info("Starting Delayed Queue");
        if (!this.started) {
            this.started = true;

            this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    Executors.privilegedThreadFactory());

            this.callbacks = Executors.newFixedThreadPool(this.capacity);

            this.reschedule(this.peek());
        }
    }

    /**
     * Cancel the {@link DelayedQueue#future} and prevent any reschedule.
     * 
     * @throws InterruptedException
     */
    public void stop() throws InterruptedException {
        LOGGER.info("Stopping Delayed Queue");
        this.started = false;
        this.cancel();
        this.scheduler.shutdownNow();
        this.scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);

        this.callbacks.shutdownNow();
        this.callbacks.awaitTermination(1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancel the {@link DelayedQueue#future}.
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
    private void reschedule(final E item) {
        final ReentrantLock locking = this.lock;

        locking.lock();
        try {
            LOGGER.trace("reschedule() {");

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
            locking.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private E last() {
        if (this.size() == 0) {
            return null;
        }

        return (E) this.toArray()[this.size() - 1];
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
        Runnable run = () -> {

            E processingItem = DelayedQueue.this.mOnTimeListener
                    .onBeforeRun(item);

            if (processingItem == null) {
                processingItem = item;
            }

            if (processingItem != null
                    && (processingItem instanceof Runnable)) {
                LOGGER.info("Running 'data' because it is runnable!");

                ((Runnable) processingItem).run();
            }

            DelayedQueue.this.mOnTimeListener.onTime(processingItem);
            LOGGER.debug(processingItem + " consumed");
        };
        callbacks.submit(run);
    }

    /**
     * Wait until all elements are processed.
     */
    public void stay() {
        LOGGER.trace("stay() {");

        if (this.future != null) {
            while (super.size() > 0 && this.started) {
                try {
                    this.future.get();
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

            // see if the queue is allowed to grow, or if still has space
            // or if it doesn't have space, but the new item is more priority
            if (this.grows || this.size() < this.capacity
                    || (this.size() >= this.capacity && this.last()
                            .getDelay(TIMEUNIT) > e.getDelay(TIMEUNIT))) {
                // calling super
                returned = super.offer(e);

                // if it is necessary...
                if (returned && peek() == e) {
                    // reschedule
                    reschedule(peek());
                }

                // if the queue grew, and shouldn't
                if (!this.grows && this.size() > this.capacity) {
                    this.remove(this.last());
                }
            }
        }

        return returned;
    }

    @Override
    public void put(E e) {
        while (!this.offer(e)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
            }
        }
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

    @Override
    public int remainingCapacity() {
        if (this.grows) {
            return super.remainingCapacity();
        } else {
            return this.capacity - this.size();
        }
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

        /**
         * Executed before any kind of process is made by the queue.
         * 
         * @param e
         *            element that is about to be processed
         * @return The instance after any kind of transformation. A {@code null}
         *         will make the queue process the original element.
         */
        E onBeforeRun(E e);
    }

}
