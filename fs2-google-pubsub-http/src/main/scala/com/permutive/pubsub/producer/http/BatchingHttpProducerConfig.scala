/*
 * Copyright 2018 Permutive
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

package com.permutive.pubsub.producer.http

import scala.concurrent.duration.FiniteDuration

@deprecated(
  "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
  since = "0.22.2"
)
case class BatchingHttpProducerConfig(
  batchSize: Int,
  maxLatency: FiniteDuration,
  retryTimes: Int,
  retryInitialDelay: FiniteDuration,
  retryNextDelay: FiniteDuration => FiniteDuration
)
