package com.nowin.core;

public class PriorityTask implements Runnable, Comparable<PriorityTask> {

    public enum Priority {
        HIGH(0),
        NORMAL(1),
        LOW(2);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private final Runnable task;
    private final Priority priority;
    private final long createdAt;

    public PriorityTask(Runnable task, Priority priority) {
        this.task = task;
        this.priority = priority;
        this.createdAt = System.currentTimeMillis();
    }

    public PriorityTask(Runnable task) {
        this(task, Priority.NORMAL);
    }

    public Priority getPriority() {
        return priority;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public void run() {
        task.run();
    }

    @Override
    public int compareTo(PriorityTask other) {
        int priorityCompare = Integer.compare(this.priority.getValue(), other.priority.getValue());
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Long.compare(this.createdAt, other.createdAt);
    }
}
