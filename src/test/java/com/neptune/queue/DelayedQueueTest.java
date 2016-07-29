package com.neptune.queue;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.neptune.queue.DelayedQueue.OnTimeListener;

import junit.framework.TestCase;

public class DelayedQueueTest extends TestCase {

    int callCount = 0;

    // Helper lists
    List<DelayedItem<Object, Object>> addList = new LinkedList<>();
    List<DelayedItem<Object, Object>> removeList = new LinkedList<>();
    List<DelayedItem<Object, Object>> retainList = new LinkedList<>();

    DelayedItem<Object, Object> result;

    OnTimeListener<Object, Object> listener = new OnTimeListener<Object, Object>() {
        @Override
        public void onTime(DelayedItem<Object, Object> e) {
            callCount++;
            result = e;
        }
    };

    private DelayedQueue<Object, Object> queue;

    public DelayedQueueTest() {
    }

    @Before
    public void setUp() throws Exception {
        callCount = 0;
        result = null;
        queue = new DelayedQueue<Object, Object>();
        queue.setOnTimeListener(listener);

        for (int i = 0; i < 10; i++) {
            addList.add(new DelayedItem<Object, Object>(
                    DateTime.now().plus((i + 1) * 500 + 1000).getMillis(),
                    new Integer(i)));
        }

        removeList.addAll(addList.subList(0, 4));
        retainList.addAll(addList.subList(3, 7));
    }

    @After
    public void tearDown() throws Exception {
        queue.stop();

        addList.clear();
        removeList.clear();
        retainList.clear();
    }

    @Test
    public void test_start_stop() throws InterruptedException {
        // Tests with the Queue stopped
        queue.stop();

        queue.add(new DelayedItem<Object, Object>(
                DateTime.now().plus(10).getMillis()));
        queue.add(new DelayedItem<Object, Object>(
                DateTime.now().plus(11).getMillis()));

        queue.get();
        queue.stay();

        assertNotNull("Element was mistakenly removed with stopped timer",
                queue.peek());
        assertEquals("Listener method was called with timer stopped", 0,
                callCount);

        queue.add(new DelayedItem<Object, Object>(
                DateTime.now().minusSeconds(1).getMillis()));
        assertEquals(
                "Listener method was called with timer stopped with passed time",
                0, callCount);

        // Start again to check if the elements are consumed
        queue.start();

        queue.get();
        queue.get();
        queue.stay();

        assertNull("Failed to remove the elements", queue.peek());
        assertEquals("All element should fire", 3, callCount);
    }

    @Test
    public void test_simpleAdds() throws InterruptedException {

        DelayedItem<Object, Object> ontime = new DelayedItem<Object, Object>(
                DateTime.now().plusSeconds(1).getMillis(), 1L);
        queue.add(ontime);

        queue.get();
        queue.stay();

        assertNull("Failed to remove the element", queue.peek());
        assertEquals("Listener method was not called", 1, callCount);
        assertEquals("The right element was not passed to the listener", ontime,
                result);

        DelayedItem<Object, Object> late = new DelayedItem<Object, Object>(
                DateTime.now().minusSeconds(1).getMillis(), 2L);
        queue.add(late);

        queue.get();
        queue.stay();

        assertEquals("Listener not fired when add an already passed time", 2,
                callCount);
        assertEquals("The right element was not passed to the listener", late,
                result);

        queue.add(new DelayedItem<Object, Object>(Long.MAX_VALUE));
        queue.add(new DelayedItem<Object, Object>(Long.MAX_VALUE));
        assertTrue("Wrong size", queue.size() == 2);
    }

    @Test
    public void test_listAdds() throws InterruptedException {
        queue.addAll(addList);
        assertEquals("Wrong size based on List", addList.size(), queue.size());
    }

    @Test
    public void test_simpleRemoves()
            throws InterruptedException, ExecutionException {
        // This process is kind of slow...
        queue.addAll(addList);

        assertTrue("Removing the first item from the queue failed",
                queue.remove(addList.get(0)));
        assertFalse("The right element was removed",
                queue.contains(addList.get(0)));
        assertEquals("The right element is the head", addList.get(1),
                queue.peek());
        assertEquals("Wrong size based on List after removed",
                addList.size() - 1, queue.size());

        queue.get();
        queue.stay();

        assertEquals("Listener method was not called the right amount of times",
                1, callCount);
        assertEquals("The right element was not passed to the listener",
                addList.get(1), result);

        assertEquals("Polled element failed", addList.get(2), queue.poll());
        assertEquals("Wrong size based on List after removed",
                addList.size() - 3, queue.size());

        queue.get();
        queue.stay();

        assertEquals("Listener method was not called the right amount of times",
                2, callCount);
        assertEquals("The right element was not passed to the listener",
                addList.get(3), result);

        assertTrue("Removing an arbitrary item from the queue failed",
                queue.remove(addList.get(4)));
        assertFalse("The right element was removed",
                queue.contains(addList.get(4)));
        assertEquals("Wrong size based on List after removed",
                addList.size() - 5, queue.size());

    }

    @Test
    public void test_timeChecker() throws InterruptedException {
        //FIXME implement tests for timing!
    }
}
