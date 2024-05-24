@DESCRIPTION@

```scala mdoc:toc
```

## Installation

Add the following line to your `build.sbt` file:

```sbt
libraryDependencies += "@ORGANIZATION@" %% "fs2-pubsub" % "@VERSION@"
```

The library is published for Scala versions: @SUPPORTED_SCALA_VERSIONS@.

## Usage

To start using the library, you'll need an http4s `Client` with permission to
call Pub/Sub APIs in GCP. You can create one using [`gcp-auth`]:

```scala mdoc:silent
import org.http4s.ember.client.EmberClientBuilder
import cats.effect.IO
import cats.syntax.all._
import com.permutive.gcp.auth.TokenProvider

val client = EmberClientBuilder
  .default[IO]
  .withHttp2
  .build
  .mproduct(client => TokenProvider.userAccount(client).toResource)
  .map { case (client, tokenProvider) => tokenProvider.clientMiddleware(client) }
```

```scala mdoc:reset:invisible
import org.http4s.client.Client
import org.http4s.HttpApp
import cats.effect.IO

val client: Client[IO] = Client.fromHttpApp[IO](HttpApp.notFound[IO])
```

### Publishing messages to a Pub/Sub topic

To publish messages to Pub/Sub, you can use the `PubsubPublisher` class:

```scala mdoc:silent
import fs2.pubsub._

val publisher: PubSubPublisher[IO, String] = PubSubPublisher
    .http[IO, String]
    .projectId(ProjectId("my-project"))
    .topic(Topic("my-topic"))
    .defaultUri
    .httpClient(client)
    .noRetry
```

Then you can use any of the `PubSubPublisher` methods to send messages to Pub/Sub.

```scala mdoc:silent
// Producing a single message

publisher.publishOne("message")
```

```scala mdoc:silent
// Producing multiple messages

val records = List(
   PubSubRecord.Publisher("message1"),
   PubSubRecord.Publisher("message2"),
   PubSubRecord.Publisher("message3")
)

publisher.publishMany(records)
```

```scala mdoc:silent
// Producing a message with attributes

publisher.publishOne("message", "key" -> "value")
```

```scala mdoc:silent
// Producing a message using the record type

val record = PubSubRecord.Publisher("message").withAttribute("key", "value")

publisher.publishOne(record)
```

#### Configuring the publisher

There are several configuration options available for the publisher:

- `projectId`: The GCP project ID.
- `topic`: The Pub/Sub topic name.
- `uri`: The URI of the Pub/Sub API. By default, it uses the Google Cloud
Pub/Sub API.
- `httpClient`: The http4s `Client` to use for making requests to the
Pub/Sub API.
- `retry`: The retry policy to use when sending messages to Pub/Sub. By
default, it retries up to 3 times with exponential backoff.

These configurations can either by provided by using a configuration object
(`PubSubPublisher.Config`) or by using the builder pattern.

#### Using gRPC (only available on 2.13 or 3.x)

You can use `PubSubPublisher.grpc` to create a publisher that uses gRPC to connect
to Pub/Sub.

This type of publisher is only available on Scala `2.13` or `3.x`.

#### Publishing messages asynchronously (in batches)

In order to publish messages asynchronously, you can use the `PubSubPublisher.Async`.
You can create an instance of this class from a regular `PubSubPublisher` by using the
`batching` method:

```scala mdoc:silent
import cats.effect.Resource
import scala.concurrent.duration._

val asyncPublisher: Resource[IO, PubSubPublisher.Async[IO, String]] = 
   publisher
    .batching
    .batchSize(10)
    .maxLatency(1.second)
```

Then you can use any of the `PubSubPublisher.Async` methods to send messages to Pub/Sub.
These methods are the same ones you'll find in the regular `PubSubPublisher`, with
the difference that they return a `F[Unit]` instead of a `F[MessageId]` and that
they expect a `PubSubRecord.Publisher.WithCallback` instead of a regular
`PubSubRecord.Publisher`.

In order to construct such class you can either use the `PubSubRecord.Publisher.WithCallback`
constructor or use the `withCallback` method on a regular `PubSubRecord.Publisher`:

```scala mdoc:silent
val recordWithCallback = PubSubRecord.Publisher("message").withCallback { _ =>
  IO(println("Message sent!"))
}
```

### Subscribing to a Pub/Sub subscription

To subscribe to a Pub/Sub subscription, you can use the `PubSubSubscriber` class:

```scala mdoc:silent
import fs2.Stream

val subscriber: Stream[IO, Option[String]] = PubSubSubscriber
    .http[IO]
    .projectId(ProjectId("my-project"))
    .subscription(Subscription("my-subscription"))
    .defaultUri
    .httpClient(client)
    .noRetry
    .noErrorHandling
    .withDefaults
    .decodeTo[String]
    .subscribeAndAck
```

#### Configuring the subscriber

There are several configuration options available for the subscriber:

- `projectId`: The GCP project ID.
- `subscription`: The Pub/Sub subscription name.
- `uri`: The URI of the Pub/Sub API. By default, it uses the Google Cloud
Pub/Sub API.
- `httpClient`: The http4s `Client` to use for making requests to the
Pub/Sub API.
- `retry`: The retry policy to use when receiving messages from Pub/Sub. By
default, it retries up to 3 times with exponential backoff.
- `errorHandling`: The error handling policy to use when performing operations
such as decoding messages or acknowledging them.
- `batchSize`: The maximum number of messages to acknowledge at once.
- `maxLatency`: The maximum time to wait for a batch of messages before 
acknowledging them.
- `maxMessages`: The maximum number of messages to receive in a single batch.
- `readConcurrency`: The number of concurrent reads from the subscription.

These configurations can either by provided by using a configuration object
(`PubSubSubscriber.Config`) or by using the builder pattern.

#### Using gRPC (only available on 2.13 or 3.x)

You can use `PubSubSubscriber.grpc` to create a subscriber that uses gRPC to connect
to Pub/Sub.

This type of subscriber is only available on Scala `2.13` or `3.x`.

#### Creating a raw subscriber

There are two types of subscribers available in the library: raw and decoded.

The raw subscriber returns the raw message received from Pub/Sub, while the
decoded subscriber decodes the message to a specific type.

The former is useful when you want to handle the message yourself, while the
latter is useful when you want to work with a specific type. You can create
a raw subscriber by using the `raw` method instead of `decodeTo`.

### Pureconfig integration

The library provides a way to load the configuration from a `ConfigSource` using
[`pureconfig`].

You just need to add the following line to your `build.sbt` file:

```sbt
libraryDependencies += "@ORGANIZATION@" %% "fs2-pubsub-pureconfig" % "@VERSION@"
```

And then add the following import when you want to use the `pureconfig` integration:

```scala mdoc:reset:invisible
import org.http4s.client.Client
import org.http4s.HttpApp
import cats.effect.IO

val client: Client[IO] = Client.fromHttpApp[IO](HttpApp.notFound[IO])
```

```scala mdoc:compile-only
import pureconfig.ConfigSource

import fs2.pubsub.PubSubPublisher
import fs2.pubsub.pureconfig._

val config = ConfigSource.default.loadOrThrow[PubSubPublisher.Config]

PubSubPublisher
    .http[IO, String]
    .fromConfig(config)
    .httpClient(client)
    .noRetry
```

## Migrating from `fs2-google-pubsub`

The most important thing you need to take into account while migrating is that
the library no longer creates an authenticated `Client` for you. You need to
provide one yourself using [`permutive-engineering/gcp-auth`][`gcp-auth`].

You can use the following table to
find the equivalent classes and methods in `fs2-pubsub`:

| `fs2-google-pubsub`                                                                                                                                                                                                                                                                   | `fs2-pubsub`                                         |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| [`com.permutive.pubsub.consumer.ConsumerRecord`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/consumer/ConsumerRecord.scala)                                        | `fs2.pubsub.PubSubRecord.Publisher`                  |
| [`com.permutive.pubsub.consumer.ConsumerRecord`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/consumer/ConsumerRecord.scala)                                        | `fs2.pubsub.PubSubRecord.Publisher`                  |
| [`com.permutive.pubsub.consumer.decoder.MessageDecoder`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/consumer/decoder/MessageDecoder.scala)                        | `fs2.pubsub.MessageDecoder`                          |
| [`com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/consumer/grpc/PubsubGoogleConsumer.scala)             | `fs2.pubsub.PubSubSubscriber.grpc`                   |
| [`com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/consumer/grpc/PubsubGoogleConsumerConfig.scala) | `fs2.pubsub.PubSubSubscriber.Config`                 |
| [`com.permutive.pubsub.consumer.http.PubsubHttpConsumer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/consumer/http/PubsubHttpConsumer.scala)                 | `fs2.pubsub.PubSubSubscriber.http`                   |
| [`com.permutive.pubsub.consumer.http.PubsubHttpConsumerConfig`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/consumer/http/PubsubHttpConsumerConfig.scala)     | `fs2.pubsub.PubSubSubscriber.Config`                 |
| [`com.permutive.pubsub.consumer.http.PubsubMessage`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/consumer/http/PubsubMessage.scala)                           | `fs2.pubsub.PubSubRecord.Subscriber`                 |
| [`com.permutive.pubsub.consumer.Model.ProjectId`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/consumer/Model.scala)                                                | `fs2.pubsub.ProjectId`                               |
| [`com.permutive.pubsub.consumer.Model.Subscription`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/consumer/Model.scala)                                             | `fs2.pubsub.Subscription`                            |
| [`com.permutive.pubsub.http.crypto.*`](https://github.com/permutive-engineering/fs2-google-pubsub/tree/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/http/crypto)                                                               | [`permutive-engineering/gcp-auth`][`gcp-auth`]       |
| [`com.permutive.pubsub.http.oauth.*`](https://github.com/permutive-engineering/fs2-google-pubsub/tree/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/http/oauth)                                                                 | [`permutive-engineering/gcp-auth`][`gcp-auth`]       |
| [`com.permutive.pubsub.http.util.RefreshableEffect`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/http/util/RefreshableEffect.scala)                           | [`permutive-engineering/refreshable`][`refreshable`] |
| [`com.permutive.pubsub.producer.AsyncPubsubProducer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/AsyncPubsubProducer.scala)                              | `fs2.pubsub.PubSubPublisher.Async`                   |
| [`com.permutive.pubsub.producer.encoder.MessageEncoder`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/encoder/MessageEncoder.scala)                        | `fs2.pubsub.MessageEncoder`                          |
| [`com.permutive.pubsub.producer.grpc.GooglePubsubProducer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/producer/grpc/GooglePubsubProducer.scala)             | `fs2.pubsub.PubSubPublisher.grpc`                    |
| [`com.permutive.pubsub.producer.grpc.PubsubProducerConfig`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-grpc/src/main/scala/com/permutive/pubsub/producer/grpc/PubsubProducerConfig.scala)             | `fs2.pubsub.PubSubPublisher.Config`                  |
| [`com.permutive.pubsub.producer.http.BatchingHttpProducerConfig`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/BatchingHttpProducerConfig.scala) | `fs2.pubsub.PubSubPublisher.Async.Config`            |
| [`com.permutive.pubsub.producer.http.BatchingHttpPubsubProducer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/BatchingHttpPubsubProducer.scala) | `fs2.pubsub.PubSubPublisher.Async.http`              |
| [`com.permutive.pubsub.producer.http.HttpPubsubProducer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/HttpPubsubProducer.scala)                 | `fs2.pubsub.PubSubPublisher.http`                    |
| [`com.permutive.pubsub.producer.http.PubsubHttpProducerConfig`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub-http/src/main/scala/com/permutive/pubsub/producer/http/PubsubHttpProducerConfig.scala)     | `fs2.pubsub.PubSubPublisher.Config`                  |
| [`com.permutive.pubsub.producer.Model.AsyncRecord`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/Model.scala)                                              | `fs2.pubsub.PubSubRecord.Subscriber.WithCallback`    |
| [`com.permutive.pubsub.producer.Model.MessageId`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/Model.scala)                                                | `fs2.pubsub.MessageId`                               |
| [`com.permutive.pubsub.producer.Model.ProjectId`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/Model.scala)                                                | `fs2.pubsub.ProjectId`                               |
| [`com.permutive.pubsub.producer.Model.SimpleRecord`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/Model.scala)                                             | `fs2.pubsub.PubSubRecord.Subscriber`                 |
| [`com.permutive.pubsub.producer.Model.Topic`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/Model.scala)                                                    | `fs2.pubsub.Topic`                                   |
| [`com.permutive.pubsub.producer.PubsubProducer`](https://github.com/permutive-engineering/fs2-google-pubsub/blob/a54ad7e698c89aaa6cc280ad482faa7f7ee210e2/fs2-google-pubsub/src/main/scala/com/permutive/pubsub/producer/PubsubProducer.scala)                                        | `fs2.pubsub.PubSubPublisher`                         |

## Contributors to this project

@CONTRIBUTORS_TABLE@

[`gcp-auth`]: https://github.com/permutive-engineering/gcp-auth/
[`refreshable`]: https://github.com/permutive-engineering/refreshable/
[`pureconfig`]: https://pureconfig.github.io/