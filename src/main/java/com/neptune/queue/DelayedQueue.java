package com.neptune.queue;

import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTimeUtils;

/**
 * Delayed Queue collection class. It is a blocking queue (its operations are
 * sync) with one thread per queue option or one thread per item option
 * {@link DelayedItem} should be used as item.
 * @author Rafael
 *
 * @param <I> The type for Id
 * @param <D> The type for Data
 *
 * @see {@link DelayedItem}
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
     * Lock used in blocking operations.
     */
    private Lock lock = new ReentrantLock();

    /**
     * Main Consumer of the Queue.
     */
    private DelayedConsumer futureConsumer = null;

    /**
     * Consuming Thread(s) state.
     */
    private boolean started;

    /**
     * Dummy listener to avoid null pointer exceptions.
     */
    private static final OnTimeListener<Object, Object> DUMMY =
            new OnTimeListener<Object, Object>() {
                @Override
                public void onTime(final DelayedItem<Object, Object> e) { }
            };

    /**
     * Timing listener. Starts with null, but is assigned to a dummy later.
     */
    @SuppressWarnings("unchecked")
    private OnTimeListener<I, D> mOnTimeListener = (OnTimeListener<I, D>) DUMMY;

    /**
     * Initialize the Queue with an Executor and a Thread Timer in it.
     */
    public DelayedQueue() {
        super();

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
        interrupt(this.futureConsumer);
    }

    /**
     * Interrupt a waiting thread and wait for it to finish.
     * @param t thread to interrupt
     */
    private void interrupt(final Thread t) {
        if (t != null) {
            LOGGER.debug("Consumer registred, interrupting it...");
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                LOGGER.warn("join() fired an InterruptedException");
            }
        }
    }

    /**
     * Reschedule the queue thread.
     */
    private void reschedule() {

        if (!started) {
            return;
        }

        interrupt(this.futureConsumer);

        if (peek() != null) {
            long waitingTime = peek().getTime()
                    - DateTimeUtils.currentTimeMillis();

            futureConsumer = new DelayedConsumer(waitingTime);

            LOGGER.info(
                    "Scheduling (" + peek() + ") to " + waitingTime + " ms");
            futureConsumer.start();
        } else {
            futureConsumer = null;
            LOGGER.info("Empty queue");
        }
    }

    /**
     * Set the listener for when the timer for the top object is fired. Setting
     * it to null will disable listening.
     * @param onTimeListener Listener to be used when a element is fired
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
    public boolean offer(
            final DelayedItem<I, D> e,
            final long timeout,
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
                // TODO make it to a thread
                this.mOnTimeListener.onTime(e);
                LOGGER.info("Consuming early " + e);
                return true;
            }

            returned = super.offer(e);

            if (returned && peek() == e) {
                reschedule();
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
     * @param <I> The type for Id
     * @param <D> The type for Data
     */
    public interface OnTimeListener<I, D> {
        /**
         * Fired when a thread finishes waiting.
         * @param e element that was fired
         */
        void onTime(DelayedItem<I, D> e);
    }

    /**
     * Thread that will wait until the time for the top element, or a new
     * element is inserted or removed.
     */
    private class DelayedConsumer extends Thread implements Serializable {

        /**
         * UID serial version.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Waiting time.
         */
        private long mWaitingTime;

        /**
         * Flag for a dying thread.
         */
        private boolean mDying = false;

        /**
         * Construct the Waiting Thread.
         * @param waitingTime
         *            The time for this thread to wait
         */
        DelayedConsumer(final long waitingTime) {
            this.mWaitingTime = waitingTime;
        }

        /**
         * Makes use of {@link Thread#sleep()} method to wait for
         * {@link DelayedConsumer#mWaitingTime} ms to remove the queue's
         * head and fire the {@link OnTimeListener#onTime(DelayedItem)}
         * event.
         * @see {@link OnTimeListener#onTime(DelayedItem)}
         */
        @Override
        public void run() {
            if (mWaitingTime > 0) {
                try {
                    LOGGER.debug("Waiting " + mWaitingTime + "ms");
                    Thread.sleep(mWaitingTime);
                } catch (InterruptedException e1) {
                    LOGGER.debug("Sleep interrupted!");
                    this.mDying = true;
                }
            } else {
                LOGGER.info("Negative time. Was the Queue stopped?");
            }

            if (!mDying) {
                DelayedItem<I, D> item = DelayedQueue.this.poll();

                DelayedQueue.this.mOnTimeListener.onTime(item);

                LOGGER.debug("Consumed (" + item + ")");
            }
        }
    }
}
