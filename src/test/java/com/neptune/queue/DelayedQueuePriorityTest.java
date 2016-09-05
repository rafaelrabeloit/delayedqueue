package com.neptune.queue;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.junit.Test;

import junit.framework.TestCase;

public class DelayedQueuePriorityTest extends TestCase {

    private DelayedQueue<Delayed> queue;

    public DelayedQueuePriorityTest() {
    }

    @Test
    public void test_mostPriotiryFirst() throws InterruptedException {
        queue = new DelayedQueue<>(1, false);
        
        DelayedTest second = new DelayedTest(
                DateTime.now().plusSeconds(100).getMillis());
        queue.add(second);
        
        DelayedTest first = new DelayedTest(
                DateTime.now().plusSeconds(10).getMillis());
        queue.offer(first, 1, TimeUnit.MILLISECONDS);
        
        assertEquals("Most Priority element is not first", first, queue.peek());
        assertEquals("Queue size is wrong", 1, queue.size());
        
        queue.stop();
    }
}
