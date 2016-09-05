package com.neptune.queue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class DelayedTest implements Delayed {

    public DelayedTest(long delay) {
        super();
        this.delay = delay;
    }

    private long delay;

    @Override
    public int compareTo(Delayed o) {
        if (o == this) {
            return 0;
        }
        long diff = this.getDelay(MILLISECONDS) - o.getDelay(MILLISECONDS);
        return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delay - System.currentTimeMillis(), MILLISECONDS);
    }

}

class DelayedRunTest extends DelayedTest implements Runnable {

    public int callCount = 0;

    public DelayedRunTest(long delay) {
        super(delay);
    }

    @Override
    public synchronized void run() {
        callCount++;
        this.notify();
    }

}
