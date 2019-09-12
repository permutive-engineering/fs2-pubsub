package com.permutive.pubsub.producer.http

import cats.effect._
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.okhttp.OkHttpBuilder

import scala.concurrent.duration._
import scala.util.Try

object ExampleGoogle extends IOApp {

  implicit final val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig())

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val client = Blocker[IO].flatMap(
      blocker =>
        OkHttpBuilder
          .withDefaultClient[IO](blocker.blockingContext)
          .flatMap(_.resource)
    )

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    val mkProducer = HttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/service/account",
      config = PubsubHttpProducerConfig(
        host = "pubsub.googleapis.com",
        port = 443,
        oauthTokenRefreshInterval = 30.minutes
      ),
      _
    )

    client
      .flatMap(mkProducer)
      .use { producer =>
        producer.produce(
          record = ExampleObject("70251cf8-5ffb-4c3f-8f2f-40b9bfe4147c", "example.com")
        )
      }
      .flatTap(output => IO(println(output)))
      .as(ExitCode.Success)
  }
}
