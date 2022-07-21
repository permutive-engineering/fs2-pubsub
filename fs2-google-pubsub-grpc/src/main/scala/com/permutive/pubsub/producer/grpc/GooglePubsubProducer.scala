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

package com.permutive.pubsub.producer.grpc

import cats.effect.kernel.{Async, Resource}
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.grpc.internal.{DefaultPublisher, PubsubPublisher}

object GooglePubsubProducer {
  def of[F[_]: Async, A: MessageEncoder](
    projectId: ProjectId,
    topic: Topic,
    config: PubsubProducerConfig[F]
  ): Resource[F, PubsubProducer[F, A]] =
    for {
      publisher <- PubsubPublisher.createJavaPublisher(projectId, topic, config)
    } yield new DefaultPublisher(publisher)
}
