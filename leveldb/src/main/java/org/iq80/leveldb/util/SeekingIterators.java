/**
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.util;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import org.iq80.leveldb.impl.SeekingIterator;

import java.util.Comparator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public class SeekingIterators
{
    /**
     * Combines multiple iterators into a single iterator by concatenating the {@code inputs}
     * iterators one after the other. The input iterators are not polled until necessary.
     * {@code NullPointerException} if any of the input iterators are null.
     */
    public static <K, V> SeekingIterator<K, V> concat(SeekingIterator<K, ? extends SeekingIterator<K, ? extends V>> inputs)
    {
        return new ConcatenatingIterator<K, V>(inputs);
    }

    private static final class ConcatenatingIterator<K, V> implements SeekingIterator<K, V>
    {
        private final SeekingIterator<K, ? extends SeekingIterator<K, ? extends V>> inputs;
        private SeekingIterator<K, ? extends V> current;
        private Entry<K,V> nextElement;

        public ConcatenatingIterator(SeekingIterator<K, ? extends SeekingIterator<K, ? extends V>> inputs)
        {
            this.inputs = inputs;
            current = emptyIterator();
        }

        @Override
        public void seekToFirst()
        {
            // reset index to before first and clear the data iterator
            inputs.seekToFirst();
            current = emptyIterator();
            nextElement = null;
        }

        @Override
        public void seek(K targetKey)
        {
            // seek the index to the block containing the key
            inputs.seek(targetKey);

            // if indexIterator does not have a next, it mean the key does not exist in this iterator
            if (inputs.hasNext()) {
                // seek the current iterator to the key
                current = inputs.next().getValue();
                current.seek(targetKey);
                nextElement = null;
            }
            else {
                current = emptyIterator();
            }
        }

        @Override
        public boolean hasNext()
        {
            advanceToNext();
            return nextElement != null;
        }

        @Override
        public Entry<K, V> next()
        {
            advanceToNext();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }

            Entry<K, V> result = nextElement;
            nextElement = null;
            return result;
        }

        @Override
        public Entry<K, V> peek()
        {
            advanceToNext();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }

            return nextElement;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        private void advanceToNext()
        {
            if (nextElement == null) {
                // note: it must be here & not where 'current' is assigned,
                // because otherwise we'll have called inputs.next() before throwing
                // the first NPE, and the next time around we'll call inputs.next()
                // again, incorrectly moving beyond the error.
                boolean currentHasNext;
                while (!(currentHasNext = current.hasNext()) && inputs.hasNext()) {
                    current = inputs.next().getValue();
                }
                if (currentHasNext) {
                    nextElement = (Entry<K, V>) current.next();
                } else {
                    // set current to empty iterator to avoid extra calls to user iterators
                    current = emptyIterator();
                }
            }
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("ConcatenatingIterator");
            sb.append("{inputs=").append(inputs);
            sb.append(", current=").append(current);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * Combines multiple iterators into a single iterator by merging the values from the {@code inputs}
     * iterators in natural sorted order.  The supplied inputs are assumed to be natural sorted.
     * {@code NullPointerException} if any of the input iterators are null.
     */
    public static <K extends Comparable<K>, V> SeekingIterator<K, V> merge(Iterable<? extends SeekingIterator<K, ? extends V>> inputs)
    {
        return new MergingIterator<K, V>(inputs, Ordering.<K>natural());
    }

    /**
     * Combines multiple iterators into a single iterator by merging the values from the {@code inputs}
     * iterators in sorted order as specified by the comparator.  The supplied inputs are assumed to be
     * sorted by the specified comparator.
     * {@code NullPointerException} if any of the input iterators are null.
     */
    public static <K, V> SeekingIterator<K, V> merge(Iterable<? extends SeekingIterator<K, ? extends V>> inputs, Comparator<K> comparator)
    {
        int size = Iterables.size(inputs);
        if (size == 0) {
            return emptyIterator();
        } else if (size == 1) {
            return (SeekingIterator<K, V>) Iterables.getOnlyElement(inputs);
        } else {
            return new MergingIterator<K, V>(inputs, comparator);
        }
    }

    private static final class MergingIterator<K, V> implements SeekingIterator<K, V>
    {
        private final Iterable<? extends SeekingIterator<K, ? extends V>> inputs;
        private final PriorityQueue<ComparableIterator<K, V>> priorityQueue;
        private final Comparator<K> comparator;
        private Entry<K,V> nextElement;

        public MergingIterator(Iterable<? extends SeekingIterator<K, ? extends V>> inputs, Comparator<K> comparator)
        {
            this.inputs = inputs;
            this.comparator = comparator;

            this.priorityQueue = new PriorityQueue<ComparableIterator<K, V>>(Iterables.size(inputs));
            resetPriorityQueue(inputs, comparator);

            findSmallestChild();
        }

        private void resetPriorityQueue(Iterable<? extends SeekingIterator<K, ? extends V>> inputs, Comparator<K> comparator)
        {
            int i = 0;
            for (SeekingIterator<K, ? extends V> input : inputs) {
                if (input.hasNext()) {
                    priorityQueue.add(new ComparableIterator<K, V>(input, comparator, i++, (Entry<K, V>) input.next()));
                }
            }
        }

        @Override
        public void seekToFirst()
        {
            for (SeekingIterator<K, ? extends V> input : inputs) {
                input.seekToFirst();
            }
            resetPriorityQueue(inputs, comparator);
            findSmallestChild();
        }

        @Override
        public void seek(K targetKey)
        {
            for (SeekingIterator<K, ? extends V> input : inputs) {
                input.seek(targetKey);
            }
            resetPriorityQueue(inputs, comparator);
            findSmallestChild();
        }

        @Override
        public boolean hasNext()
        {
            return nextElement != null;
        }

        @Override
        public Entry<K, V> next()
        {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<K, V> result = nextElement;
            findSmallestChild();
            return result;
        }

        @Override
        public Entry<K, V> peek()
        {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return nextElement;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        /**
         * Assigns current to the iterator that will return the smallest value for next, or null
         * if all of the iterators are exhausted.
         */
        private void findSmallestChild()
        {
            nextElement = null;

            ComparableIterator<K, V> nextIterator = priorityQueue.poll();
            if (nextIterator != null) {
                nextElement = nextIterator.getNextElement();
                nextIterator.advanceToNextElement();
                if (nextIterator.getNextElement() != null) {
                    priorityQueue.add(nextIterator);
                }
            }
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("MergingIterator");
            sb.append("{inputs=").append(Iterables.toString(inputs));
            sb.append(", comparator=").append(comparator);
            sb.append(", nextElement=").append(nextElement);
            sb.append('}');
            return sb.toString();
        }

        private static class ComparableIterator<K, V> implements Comparable<ComparableIterator<K, V>> {
            private final SeekingIterator<K, ? extends V> iterator;
            private final Comparator<K> comparator;
            private final int ordinal;
            private Entry<K,V> nextElement;

            private ComparableIterator(SeekingIterator<K, ? extends V> iterator, Comparator<K> comparator, int ordinal, Entry<K, V> nextElement)
            {
                this.iterator = iterator;
                this.comparator = comparator;
                this.ordinal = ordinal;
                this.nextElement = nextElement;
            }

            public Entry<K, V> getNextElement()
            {
                return nextElement;
            }

            public void advanceToNextElement()
            {
                if (iterator.hasNext()) {
                    nextElement = (Entry<K, V>) iterator.next();
                } else {
                    nextElement = null;
                }

            }

            @Override
            public boolean equals(Object o)
            {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }

                ComparableIterator<?, ?> comparableIterator = (ComparableIterator<?, ?>) o;

                if (ordinal != comparableIterator.ordinal) {
                    return false;
                }
                if (nextElement != null ? !nextElement.equals(comparableIterator.nextElement) : comparableIterator.nextElement != null) {
                    return false;
                }

                return true;
            }

            @Override
            public int hashCode()
            {
                int result = ordinal;
                result = 31 * result + (nextElement != null ? nextElement.hashCode() : 0);
                return result;
            }

            @Override
            public int compareTo(ComparableIterator<K, V> that)
            {
                int result = comparator.compare(this.nextElement.getKey(), that.nextElement.getKey());
                if (result == 0) {
                    result = Ints.compare(this.ordinal, that.ordinal);
                }
                return result;
            }
        }
    }

    /**
     * Returns an iterator that applies {@code function} to each element of {@code
     * fromIterator}.
     */
    public static <K, V1, V2> SeekingIterator<K, V2> transformValues(
            SeekingIterator<K, V1> fromIterator,
            final Function<V1, V2> function)
    {
        Preconditions.checkNotNull(fromIterator, "fromIterator is null");
        Preconditions.checkNotNull(function, "function is null");


        Function<Entry<K, V1>, Entry<K, V2>> entryEntryFunction = new Function<Entry<K, V1>, Entry<K, V2>>()
        {
            @Override
            public Entry<K, V2> apply(Entry<K, V1> input)
            {
                K key = input.getKey();
                V2 value = function.apply(input.getValue());
                return Maps.immutableEntry(key, value);
            }
        };
        return new TransformingSeekingIterator<K, V1, K, V2>(fromIterator, entryEntryFunction, Functions.<K>identity());
    }

    
    public static <K1, K2, V> SeekingIterator<K2, V> transformKeys(
            SeekingIterator<K1, V> fromIterator,
            final Function<K1, K2> keyFunction,
            Function<K2, K1> reverseKeyFunction)
    {
        Preconditions.checkNotNull(fromIterator, "fromIterator is null");
        Preconditions.checkNotNull(keyFunction, "keyFunction is null");
        Preconditions.checkNotNull(reverseKeyFunction, "reverseKeyFunction is null");

        Function<Entry<K1, V>, Entry<K2, V>> entryEntryFunction = new Function<Entry<K1, V>, Entry<K2, V>>()
        {
            @Override
            public Entry<K2, V> apply(Entry<K1, V> input)
            {
                K2 key = keyFunction.apply(input.getKey());
                V value = input.getValue();
                return Maps.immutableEntry(key, value);
            }
        };
        return new TransformingSeekingIterator<K1, V, K2, V>(fromIterator, entryEntryFunction, reverseKeyFunction);
    }

    
    /**
     * Returns an iterator that applies {@code function} to each element of {@code
     * fromIterator}.
     */
    public static <K1, V1, K2, V2> SeekingIterator<K2, V2> transformEntries(
            SeekingIterator<K1, V1> fromIterator,
            Function<Entry<K1, V1>, Entry<K2, V2>> entryFunction,
            Function<? super K2, ? extends K1> reverseKeyFunction)
    {
        Preconditions.checkNotNull(fromIterator, "fromIterator is null");
        Preconditions.checkNotNull(entryFunction, "entryFunction is null");
        Preconditions.checkNotNull(reverseKeyFunction, "reverseKeyFunction is null");

        return new TransformingSeekingIterator<K1, V1, K2, V2>(fromIterator, entryFunction, reverseKeyFunction);
    }

    // split into a key and separate value transformer since this is pretty unreadable
    private static class TransformingSeekingIterator<K1, V1, K2, V2> implements SeekingIterator<K2, V2>
    {
        private final SeekingIterator<K1, V1> fromIterator;
        private final Function<Entry<K1, V1>, Entry<K2, V2>> entryFunction;
        private final Function<? super K2, ? extends K1> reverseKeyFunction;
        private Entry<K2,V2> nextElement;

        public TransformingSeekingIterator(SeekingIterator<K1, V1> fromIterator,
                Function<Entry<K1, V1>, Entry<K2, V2>> entryFunction,
                Function<? super K2, ? extends K1> reverseKeyFunction)
        {
            this.fromIterator = fromIterator;
            this.entryFunction = entryFunction;
            this.reverseKeyFunction = reverseKeyFunction;
        }

        @Override
        public void seekToFirst()
        {
            fromIterator.seekToFirst();
            nextElement = null;
        }

        @Override
        public void seek(K2 targetKey)
        {
            fromIterator.seek(reverseKeyFunction.apply(targetKey));
            nextElement = null;
        }

        @Override
        public boolean hasNext()
        {
            advanceToNext();
            return nextElement != null;
        }

        @Override
        public Entry<K2, V2> next()
        {
            advanceToNext();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }

            Entry<K2, V2> result = nextElement;
            nextElement = null;
            return result;
        }

        @Override
        public Entry<K2, V2> peek()
        {
            advanceToNext();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }
            return nextElement;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        private void advanceToNext()
        {
            if (nextElement == null && fromIterator.hasNext()) {
                Entry<K1, V1> from = fromIterator.next();
                nextElement = entryFunction.apply(from);
            }
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("TransformingSeekingIterator");
            sb.append("{fromIterator=").append(fromIterator);
            sb.append(", entryFunction=").append(entryFunction);
            sb.append(", reverseKeyFunction=").append(reverseKeyFunction);
            sb.append(", nextElement=").append(nextElement);
            sb.append('}');
            return sb.toString();
        }
    }

    public static <K, V> SeekingIterator<K, V> emptyIterator()
    {
        return (SeekingIterator<K, V>) EMPTY_ITERATOR;
    }

    private static final EmptySeekingIterator EMPTY_ITERATOR = new EmptySeekingIterator();

    private static final class EmptySeekingIterator implements SeekingIterator<Object, Object>
    {
        @Override
        public boolean hasNext()
        {
            return false;
        }

        @Override
        public void seekToFirst()
        {
        }

        @Override
        public void seek(Object targetKey)
        {
        }

        @Override
        public Entry<Object, Object> peek()
        {
            throw new NoSuchElementException();
        }

        @Override
        public Entry<Object, Object> next()
        {
            throw new NoSuchElementException();
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }

}
