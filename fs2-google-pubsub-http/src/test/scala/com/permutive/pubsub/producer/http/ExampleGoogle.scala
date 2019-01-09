package com.permutive.pubsub.producer.http

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import org.http4s.client.asynchttpclient.AsyncHttpClient

import scala.concurrent.duration._
import scala.util.Try

object ExampleGoogle extends IOApp {

  final implicit val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig())

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String,
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val mkProducer = HttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/service/account",
      config = PubsubHttpProducerConfig(
        host = "pubsub.googleapis.com",
        port = 443,
        oauthTokenRefreshInterval = 30.minutes,
      ),
      _
    )

    val http = AsyncHttpClient.resource[IO]()
    http.flatMap(mkProducer).use { producer =>
      producer.produce(
        record = ExampleObject("70251cf8-5ffb-4c3f-8f2f-40b9bfe4147c", "example.com")
      )
    }.flatTap(output => IO(println(output))) >> IO.pure(ExitCode.Success)
  }
}
