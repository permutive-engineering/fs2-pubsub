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
import scala.collection.JavaConverters._

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

      FutureInterop.fFromFuture(Sync[F].delay(publisher.publish(message))).map(MessageId)
    }

  override def produceMany[G[_]: Traverse](records: G[Model.Record[A]]): F[List[MessageId]] =
    records.traverse(r => produce(r.data, r.attributes, r.uniqueId)).map(_.toList)
}
