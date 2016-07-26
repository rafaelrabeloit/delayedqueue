package com.neptune.queue;

import javax.validation.constraints.NotNull;

/**
 * Class used as item in delayed queues.
 * @author Rafael
 *
 * @param <I> The type for Id
 * @param <D> The type for Data
 */
public class DelayedItem<I, D> implements Comparable<DelayedItem<I, D>> {

    /**
     * "Future" timestamp to when this item should fire.
     * If not set, is supposed {@link Integer#MAX_VALUE} or
     * the End of Times.
     */
    private Long mTime;

    /**
     * The data associated with this item.
     * Can be null, because can be retrieved by the Id from a database.
     */
    private D mData;

    /**
     * The identifier associated with this item.
     * Can be null if the data is set.
     */
    private I mId;

    /**
     * Basic constructor method.
     * @param time timestamp for when this element should fire. Can't be null
     * @param id the identifier for this element
     * @param data the data for this element
     */
    public DelayedItem(@NotNull final Long time, final I id, final D data) {
        super();
        this.mTime = time;
        this.mData = data;
        this.mId = id;
    }

    /**
     * Constructor without data.
     * @param time timestamp for when this element should fire. Can't be null
     * @param id the identifier for this element
     */
    public DelayedItem(@NotNull final Long time, final I id) {
        this(time, id, null);
    }

    /**
     * Constructor without data and id.
     * @param time timestamp for when this element should fire. Can't be null
     */
    public DelayedItem(@NotNull final Long time) {
        this(time, null, null);
    }

    /**
     * Constructor without data and time.
     * Time is supposed to be "End of Times"
     * @param id element identifier
     */
    public DelayedItem(@NotNull final I id) {
        this(Long.MAX_VALUE, id, null);
    }

    /**
     * Timestamp for this item.
     * @return time
     */
    public final Long getTime() {
        return mTime;
    }

    /**
     * Data for this item.
     * @return data
     */
    public final D getData() {
        return mData;
    }

    /**
     * The id is used to check if two elements are "equal" even though they are
     * different objects.
     * This because these object are meant to be distributed.
     * @return id
     */
    public final I getId() {
        return mId;
    }

    @Override
    public final int compareTo(final DelayedItem<I, D> o) {
        return this.getTime().compareTo(o.getTime());
    }

    @Override
    public final int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result;
        if (mId != null) {
            result += mId.hashCode();
        }
        return result;
    }

    @Override
    public final boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        @SuppressWarnings("unchecked")
        DelayedItem<I, D> other = (DelayedItem<I, D>) obj;

        if (mId == null) {
            if (other.mId != null) {
                return false;
            }
        } else if (!mId.equals(other.mId)) {
            return false;
        }
        return true;
    }

    @Override
    public final String toString() {
        return "DelayedItem ["
                + "time=" + mTime
                + ", data=" + mData
                + ", id=" + mId
                + "]";
    }
}
