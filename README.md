# fs2-google-pubsub
[![Build Status](https://travis-ci.org/permutive/fs2-google-pubsub.svg?branch=master)](https://travis-ci.org/permutive/fs2-google-pubsub)
[![Maven Central](https://img.shields.io/maven-central/v/com.permutive/fs2-google-pubsub_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cfs2-google-pubsub)

[Google Cloud Pub/Sub][0] stream-based client built on top of [cats-effect][1], [fs2][2] and [http4s][6].

`fs2-google-pubsub` provides a mix of APIs, depending on the exact module. Consumers are provided as `fs2` streams,
while the producers are effect-based, utilising `cats-effect`.

## Table of Contents
- [Module overview](#module-overview)
  - [Public modules](#public-modules)
  - [Internal modules](#internal-modules)
- [Dependencies](#dependencies)
  - [Using Google Libraries](#using-google-libraries)
  - [Using HTTP](#using-http)
- [Examples](#examples)
  - [Consumer (Google)](#consumer-google)
  - [Consumer (HTTP)](#consumer-http)
  - [Producer (Google)](#producer-google)
  - [Producer (HTTP)](#producer-http)
  - [Producer (HTTP with automatic batching)](#producer-http-automatic-batching)
- [HTTP vs Google](#http-vs-google)
  - [Google pros and cons](#google-pros-and-cons)
  - [HTTP pros and cons](#http-pros-and-cons)

## Module overview
### Public modules
- `fs2-google-pubsub-grpc` - an implementation that utilises Google's own [Java library][3]
- `fs2-google-pubsub-http` - an implementation that uses `http4s` and communicates via the [REST API][4]

### Internal modules
- `fs2-google-pubsub` - shared classes for all implementations

## Dependencies
Add one (or more) of the following to your `build.sbt`, see [Releases][5] for latest version:

```
libraryDependencies += "com.permutive" %% "fs2-google-pubsub-grpc" % Version
```
OR
```
libraryDependencies += "com.permutive" %% "fs2-google-pubsub-http" % Version
```

Also note you need to add an explicit HTTP client implementation. `http4s` provides different implementations
for the clients, including `blaze`, `async-http-client`, `jetty`, `okhttp` and others.

If `async-http-client` is desired, add the following to `build.sbt`:
```
libraryDependencies += "org.http4s" %% "http4s-async-http-client" % "0.20.0"
```

## Examples

### Consumer (Google)
See [PubsubGoogleConsumerConfig][7] for more configuration options.
```scala
package com.permutive.pubsub.consumer.google

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder

object SimpleDriver extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Right(ValueHolder(new String(bytes)))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val stream = PubsubGoogleConsumer.subscribe[IO, ValueHolder](
      Model.ProjectId("test-project"),
      Model.Subscription("example-sub"),
      (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack,
      config = PubsubGoogleConsumerConfig(
        onFailedTerminate = _ => IO.unit
      )
    )

    stream
      .evalTap(t => t.ack >> IO(println(s"Got: ${t.value}")))
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
```

### Consumer (HTTP)
See [PubsubHttpConsumerConfig][8] for more configuration options.
```scala
package com.permutive.pubsub.consumer.http

import cats.effect._
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import org.http4s.client.asynchttpclient.AsyncHttpClient
import fs2.Stream

import scala.util.Try

object Example extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Try(ValueHolder(new String(bytes))).toEither
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val client = AsyncHttpClient.resource[IO]()

    val mkConsumer = PubsubHttpConsumer.subscribe[IO, ValueHolder](
      Model.ProjectId("test-project"),
      Model.Subscription("example-sub"),
      "/path/to/service/account",
      PubsubHttpConsumerConfig(
        host = "localhost",
        port = 8085,
        isEmulator = true,
      ),
      _,
      (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack,
    )

    Stream.resource(client)
      .flatMap(mkConsumer)
      .evalTap(t => t.ack >> IO(println(s"Got: ${t.value}")))
      .as(ExitCode.Success)
      .compile
      .lastOrError
  }
}

```

### Producer (Google)
See [PubsubProducerConfig][9] for more configuration.
```scala
package com.permutive.pubsub.producer.google

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

  override def run(args: List[String]): IO[ExitCode] = {
    GooglePubsubProducer.of[IO, Value](
      Model.ProjectId("test-project"),
      Model.Topic("values"),
      config = PubsubProducerConfig[IO](
        batchSize = 100,
        delayThreshold = 100.millis,
        onFailedTerminate = e => IO(println(s"Got error $e")) >> IO.unit
      )
    ).use { producer =>
      producer.produce(
        Value(10),
      )
    }.map(_ => ExitCode.Success)
  }
}

```

### Producer (HTTP)
See [PubsubHttpProducerConfig][10] for more configuration options.
```scala
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
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig)

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
        data = ExampleObject("70251cf8-5ffb-4c3f-8f2f-40b9bfe4147c", "example.com")
      )
    }.flatTap(output => IO(println(output))) >> IO.pure(ExitCode.Success)
  }
}
```

### Producer (HTTP) automatic-batching
See [PubsubHttpProducerConfig][10] and [BatchingHttpPublisherConfig][11] for more configuration options.
```scala
package com.permutive.pubsub.producer.http

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.client.asynchttpclient.AsyncHttpClient

import scala.concurrent.duration._
import scala.util.Try

object ExampleBatching extends IOApp {

  private[this] final implicit val unsafeLogger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

  final implicit val Codec: JsonValueCodec[ExampleObject] =
    JsonCodecMaker.make[ExampleObject](CodecMakerConfig)

  implicit val encoder: MessageEncoder[ExampleObject] = (a: ExampleObject) => {
    Try(writeToArray(a)).toEither
  }

  case class ExampleObject(
    projectId: String,
    url: String,
  )

  override def run(args: List[String]): IO[ExitCode] = {
    val mkProducer = BatchingHttpPubsubProducer.resource[IO, ExampleObject](
      projectId = Model.ProjectId("test-project"),
      topic = Model.Topic("example-topic"),
      googleServiceAccountPath = "/path/to/service/account",
      config = PubsubHttpProducerConfig(
        host = "localhost",
        port = 8085,
        oauthTokenRefreshInterval = 30.minutes,
        isEmulator = true,
      ),

      batchingConfig = BatchingHttpProducerConfig(
        batchSize = 10,
        maxLatency = 100.millis,

        retryTimes = 0,
        retryInitialDelay = 0.millis,
        retryNextDelay = _ + 250.millis,
      ),
      _
    )

    val messageCallback: Either[Throwable, Unit] => IO[Unit] = {
      case Right(_) => Logger[IO].info("Async message was sent successfully!")
      case Left(e) => Logger[IO].warn(e)("Async message was sent unsuccessfully!")
    }

    client
      .flatMap(mkProducer)
      .use { producer =>
        val produceOne = producer.produce(
          data = ExampleObject("1f9774be-9d7c-4dd9-8d97-855b681938a9", "example.com"),
        )

        val produceOneAsync = producer.produceAsync(
          data = ExampleObject("a84a3318-adbd-4eac-af78-eacf33be91ef", "example.com"),
          callback = messageCallback
        )

        for {
          result1 <- produceOne
          result2 <- produceOne
          result3 <- produceOne
          _       <- result1
          _       <- Logger[IO].info("First message was sent!")
          _       <- result2
          _       <- Logger[IO].info("Second message was sent!")
          _       <- result3
          _       <- Logger[IO].info("Third message was sent!")
          _       <- produceOneAsync
          _       <- IO.never
        } yield ()
      }
      .as(ExitCode.Success)
  }
}
```

## HTTP vs Google
### Google pros and cons
Pros of using the Google library
- Underlying library well supported (theoretically)
- Uses gRPC and HTTP/2 (should be faster)
- Automatically handles authentication

Cons of using Google Library
- Uses gRPC (if you uses multiple Google libraries with different gRPC versions, something *will* break)
- Bloated
- More dependencies
- Less functional
- Doesn't work with the official [PubSub emulator][12] (is in [feature backlog][13])
- Google API can change at any point (shouldn't be exposed to users of `fs2-google-pubsub`, but slows development/updating)

### HTTP pros and cons
Pros of using HTTP variant
- Less dependencies
- Works with the [PubSub emulator][12]
- Fully functional
- Stable API
- Theoretically less memory usage, especially for producer

Cons of using HTTP variant
- Authentication is handled manually, hence *potentially* less secure/reliable
- By default uses old HTTP 1.1 (potentially slower), but can be configured to use HTTP/2 if supported HTTP client backend is chosen


## Licence
```
   Copyright 2018-2019 Permutive, Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

[0]: https://cloud.google.com/pubsub
[1]: https://github.com/typelevel/cats-effect
[2]: https://github.com/functional-streams-for-scala/fs2
[3]: https://cloud.google.com/pubsub/docs/reference/libraries
[4]: https://cloud.google.com/pubsub/docs/reference/rest/
[5]: https://github.com/permutive/fs2-google-pubsub/releases
[6]: https://github.com/http4s/http4s
[7]: https://github.com/permutive/fs2-google-pubsub/blob/master/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/consumer/grpc/PubsubGoogleConsumerConfig.scala
[8]: https://github.com/permutive/fs2-google-pubsub/blob/master/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/consumer/http/PubsubHttpConsumerConfig.scala
[9]: https://github.com/permutive/fs2-google-pubsub/blob/master/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/producer/grpc/PubsubProducerConfig.scala
[10]: https://github.com/permutive/fs2-google-pubsub/blob/master/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/PubsubHttpProducerConfig.scala
[11]: https://github.com/permutive/fs2-google-pubsub/blob/master/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/BatchingHttpProducerConfig.scala
[12]: https://cloud.google.com/pubsub/docs/emulator
[13]: https://github.com/googleapis/google-cloud-java/wiki/Feature-backlog
