Delayed Queue Library
=====================
This project implements (or tries to) the urgent needed Delayed Queue for 
Workers and Tasks.  
It does so by extending the already defined PriorityBlockingQueue from the 
Java Collections, and adding Executors to it.

**There are 3 executors:**

* **Scheduler:** Is the responsible for scheduling the lone task of consuming
elements. Only one task is fired at once in this thread pool, and reschedules
itself to the next element in the queue before dying.
* **Callbacks:** This executor is a pool of Threads fired to run the onTime 
method from the Listener. We do this because the Scheduler Task could be 
blocked by some extensive operation done in the callback.
* **Runners:** The runners executor is the Thread pool responsible for firing 
the jobs. By that we mean that we can actually store Tasks in the Queue, and 
these tasks (Runnables) will fire when they are about to be consumed.

Note about Elements consumption
-------------------------------
Elements are bound to be consumed with a certain delay. When those elements are 
consumed, they are going to be executed, if they are Runnables.  
Also, the onTime event method will be fired in a callback thread.  
Therefore, there are 2 ways of responding to a element being consumed by the 
queue: by implementing Runnable on the elements themselves or by registering a 
 listener object.  
They are supposed to be equivalents, but different approaches to tackle the 
same problem.