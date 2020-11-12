/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.keyboardr.kdata.internal

import java.util.*

/**
 * LinkedList, which pretends to be a map and supports modifications during iterations.
 * It is NOT thread safe.
 *
 * @param K Key type
 * @param V Value type */
internal class SafeIterableMap<K, V> : Iterable<Map.Entry<K, V>?> {
  var start: Entry<K, V>? = null
  private var end: Entry<K, V>? = null

  // using WeakHashMap over List<WeakReference>, so we don't have to manually remove
  // WeakReferences that have null in them.
  private val iterators = WeakHashMap<SupportRemove<K, V>, Boolean>()
  private var size = 0
  operator fun get(k: K): Entry<K, V>? {
    var currentNode = start
    while (currentNode != null) {
      if (currentNode.key == k) {
        break
      }
      currentNode = currentNode.next
    }
    return currentNode
  }

  /**
   * If the specified key is not already associated
   * with a value, associates it with the given value.
   *
   * @param key key with which the specified value is to be associated
   * @param v   value to be associated with the specified key
   * @return the previous value associated with the specified key,
   * or `null` if there was no mapping for the key
   */
  fun putIfAbsent(key: K, v: V): V? {
    val entry = get(key)
    if (entry != null) {
      return entry.value
    }
    put(key, v)
    return null
  }

  fun put(key: K, v: V): Entry<K, V> {
    val newEntry = Entry(key, v)
    size++
    val end = end
    if (end == null) {
      start = newEntry
      this.end = start
      return newEntry
    }
    end.next = newEntry
    newEntry.previous = end
    this.end = newEntry
    return newEntry
  }

  /**
   * Removes the mapping for a key from this map if it is present.
   *
   * @param key key whose mapping is to be removed from the map
   * @return the previous value associated with the specified key,
   * or `null` if there was no mapping for the key
   */
  fun remove(key: K): V? {
    val toRemove = get(key) ?: return null
    size--
    if (!iterators.isEmpty()) {
      for (iter in iterators.keys) {
        iter.supportRemove(toRemove)
      }
    }
    val previous = toRemove.previous
    val next = toRemove.next
    if (previous != null) {
      previous.next = next
    } else {
      start = next
    }
    if (next != null) {
      next.previous = previous
    } else {
      end = previous
    }
    toRemove.next = null
    toRemove.previous = null
    return toRemove.value
  }

  /**
   * @return the number of elements in this map
   */
  fun size(): Int {
    return size
  }

  /**
   * @return an ascending iterator, which doesn't include new elements added during an
   * iteration.
   */
  override fun iterator(): Iterator<Map.Entry<K, V>> {
    val iterator: ListIterator<K, V> = AscendingIterator(start, end)
    iterators[iterator] = false
    return iterator
  }

  /**
   * @return an descending iterator, which doesn't include new elements added during an
   * iteration.
   */
  fun descendingIterator(): Iterator<Map.Entry<K, V>> {
    val iterator = DescendingIterator(end, start)
    iterators[iterator] = false
    return iterator
  }

  /**
   * return an iterator with additions.
   */
  fun iteratorWithAdditions(): IteratorWithAdditions {
    val iterator: IteratorWithAdditions = IteratorWithAdditions()
    iterators[iterator] = false
    return iterator
  }

  /**
   * @return eldest added entry or null
   */
  fun eldest(): Map.Entry<K, V>? {
    return start
  }

  /**
   * @return newest added entry or null
   */
  fun newest(): Map.Entry<K, V>? {
    return end
  }

  override fun equals(other: Any?): Boolean {
    if (other === this) {
      return true
    }
    if (other !is SafeIterableMap<*, *>) {
      return false
    }
    if (size() != other.size()) {
      return false
    }
    val iterator1: Iterator<Map.Entry<K, V>> = iterator()
    val iterator2: Iterator<*> = other.iterator()
    while (iterator1.hasNext() && iterator2.hasNext()) {
      val next1 = iterator1.next()
      val next2 = iterator2.next()
      if (next1 != next2) {
        return false
      }
    }
    return !iterator1.hasNext() && !iterator2.hasNext()
  }

  override fun hashCode(): Int {
    var h = 0
    val i: Iterator<Map.Entry<K, V>> = iterator()
    while (i.hasNext()) {
      h += i.next().hashCode()
    }
    return h
  }

  override fun toString(): String {
    val builder = StringBuilder()
    builder.append("[")
    val iterator: Iterator<Map.Entry<K, V>> = iterator()
    while (iterator.hasNext()) {
      builder.append(iterator.next().toString())
      if (iterator.hasNext()) {
        builder.append(", ")
      }
    }
    builder.append("]")
    return builder.toString()
  }

  private abstract class ListIterator<K, V>(var next: Entry<K, V>?, var expectedEnd: Entry<K, V>?) : Iterator<Map.Entry<K, V>>, SupportRemove<K, V> {
    override fun hasNext(): Boolean = next != null

    override fun supportRemove(entry: Entry<K, V>) {
      if (expectedEnd === entry && entry === next) {
        next = null
        expectedEnd = null
      }
      if (expectedEnd === entry) {
        expectedEnd = backward(expectedEnd!!)
      }
      if (next === entry) {
        next = nextNode()
      }
    }

    private fun nextNode(): Entry<K, V>? {
      val localNext = next
      return if (localNext === expectedEnd || expectedEnd == null) {
        null
      } else forward(localNext!!)
    }

    override fun next(): Map.Entry<K, V> {
      val result: Map.Entry<K, V>? = next
      next = nextNode()
      return result!!
    }

    abstract fun forward(entry: Entry<K, V>): Entry<K, V>?
    abstract fun backward(entry: Entry<K, V>): Entry<K, V>?
  }

  private class AscendingIterator<K, V>(start: Entry<K, V>?, expectedEnd: Entry<K, V>?) : ListIterator<K, V>(start, expectedEnd) {
    override fun forward(entry: Entry<K, V>): Entry<K, V>? {
      return entry.next
    }

    override fun backward(entry: Entry<K, V>): Entry<K, V>? {
      return entry.previous
    }
  }

  private class DescendingIterator<K, V>(start: Entry<K, V>?, expectedEnd: Entry<K, V>?) : ListIterator<K, V>(start, expectedEnd) {
    override fun forward(entry: Entry<K, V>): Entry<K, V>? {
      return entry.previous
    }

    override fun backward(entry: Entry<K, V>): Entry<K, V>? {
      return entry.next
    }
  }

  internal inner class IteratorWithAdditions : Iterator<Map.Entry<K, V>>, SupportRemove<K, V> {
    private var current: Entry<K, V>? = null
    private var beforeStart = true

    override fun supportRemove(entry: Entry<K, V>) {
      val localCurrent = current
      if (entry === localCurrent) {
        current = localCurrent.previous
        beforeStart = current == null
      }
    }

    override fun hasNext(): Boolean {
      return if (beforeStart) {
        start != null
      } else {
        current?.next != null
      }
    }

    override fun next(): Map.Entry<K, V> {
      if (beforeStart) {
        beforeStart = false
        current = start
      } else {
        current = current?.next
      }
      return current!!
    }
  }

  internal interface SupportRemove<K, V> {
    fun supportRemove(entry: Entry<K, V>)
  }

  class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V> {
    var next: Entry<K, V>? = null
    var previous: Entry<K, V>? = null

    override fun toString(): String {
      return key.toString() + "=" + value
    }

    override fun equals(other: Any?): Boolean {
      if (other === this) {
        return true
      }
      if (other !is Entry<*, *>) {
        return false
      }
      return key == other.key && value == other.value
    }

    override fun hashCode(): Int {
      return key.hashCode() xor value.hashCode()
    }
  }
}