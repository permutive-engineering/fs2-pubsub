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

package com.permutive.pubsub.producer.grpc.internal

import cats.Traverse
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all._
import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.{Model, PubsubProducer}

import java.util.UUID
import scala.jdk.CollectionConverters._

@deprecated(
  "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
  since = "0.22.2"
)
private[pubsub] class DefaultPublisher[F[_]: Async, A: MessageEncoder](
  publisher: Publisher,
) extends PubsubProducer[F, A] {
  final override def produce(
    data: A,
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID.toString
  ): F[MessageId] =
    MessageEncoder[A].encode(data).liftTo[F].flatMap { v =>
      val message =
        PubsubMessage.newBuilder
          .setData(ByteString.copyFrom(v))
          .setMessageId(uniqueId)
          .putAllAttributes(attributes.asJava)
          .build()

      FutureInterop.fFromFuture(Sync[F].delay(publisher.publish(message))).map(MessageId(_))
    }

  override def produceMany[G[_]: Traverse](records: G[Model.Record[A]]): F[List[MessageId]] =
    records.traverse(r => produce(r.data, r.attributes, r.uniqueId)).map(_.toList)
}
