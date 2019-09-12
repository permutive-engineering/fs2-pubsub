package com.permutive.pubsub.producer.grpc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder

import scala.concurrent.duration._

object PubsubProducerExample extends IOApp {

  case class Value(v: Int) extends AnyVal

  implicit val encoder: MessageEncoder[Value] = new MessageEncoder[Value] {
    override def encode(a: Value): Either[Throwable, Array[Byte]] =
      Right(BigInt(a.v).toByteArray)
  }

  override def run(args: List[String]): IO[ExitCode] =
    GooglePubsubProducer
      .of[IO, Value](
        Model.ProjectId("test-project"),
        Model.Topic("values"),
        config = PubsubProducerConfig[IO](
          batchSize = 100,
          delayThreshold = 100.millis,
          onFailedTerminate = e => IO(println(s"Got error $e")) >> IO.unit,
        ),
      )
      .use { producer =>
        producer.produce(
          Value(10),
        )
      }
      .map(_ => ExitCode.Success)
}
