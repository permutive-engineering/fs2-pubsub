package com.permutive.pubsub.producer.http

import cats.effect._
import cats.syntax.all._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.okhttp.OkHttpBuilder

import scala.concurrent.duration._
import scala.util.Try

object ExampleEmulator extends IOApp {

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
    val client = Blocker[IO].flatMap(
      OkHttpBuilder
        .withDefaultClient[IO](_)
        .flatMap(_.resource)
    )

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    val mkProducer = HttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/nothing",
      config = PubsubHttpProducerConfig(
        host = "localhost",
        port = 8085,
        oauthTokenRefreshInterval = 30.minutes,
        isEmulator = true,
      ),
      _
    )

    client
      .flatMap(mkProducer)
      .use { producer =>
        producer.produce(
          record = ExampleObject("hsaudhiasuhdiu21hi3und", "example.com")
        )
      }
      .flatTap(output => IO(println(output)))
      .map(_ => ExitCode.Success)
  }
}
