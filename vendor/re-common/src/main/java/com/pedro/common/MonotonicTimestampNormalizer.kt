/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.common

/**
 * Keeps presentation timestamps strictly increasing if a source repeats or resets its clock.
 * Forward timestamps are preserved. On a duplicate/backwards value, the output advances by at
 * least one nominal frame duration and an offset is carried forward across subsequent frames.
 */
class MonotonicTimestampNormalizer {
  private var lastOutputNanos = NO_TIMESTAMP
  private var offsetNanos = 0L

  @Synchronized
  fun normalize(sourceTimestampNanos: Long, nominalFrameDurationNanos: Long): Long {
    val sourceNanos = sourceTimestampNanos.coerceAtLeast(0L)
    val adjusted = saturatedAdd(sourceNanos, offsetNanos)
    val previous = lastOutputNanos
    val output = if (previous == NO_TIMESTAMP || adjusted > previous) {
      adjusted
    } else {
      saturatedAdd(previous, nominalFrameDurationNanos.coerceAtLeast(1L))
    }
    if (output > adjusted) offsetNanos = (output - sourceNanos).coerceAtLeast(0L)
    lastOutputNanos = output
    return output
  }

  @Synchronized
  fun reset() {
    lastOutputNanos = NO_TIMESTAMP
    offsetNanos = 0L
  }

  private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

  private companion object {
    const val NO_TIMESTAMP = Long.MIN_VALUE
  }
}
