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

package com.permutive.pubsub.http.oauth

import cats.syntax.all._
import cats.{Applicative, Functor}
import org.http4s.{AuthScheme, Credentials, Request}
import org.http4s.headers.Authorization

// This could be implemented as client middleware but we think that could be a footgun; a client using auth middleware
// could easily be used in the wrong place by accident.
trait RequestAuthorizer[F[_]] {
  def authorize(request: Request[F]): F[Request[F]]
}

object RequestAuthorizer {

  @deprecated(
    "Use `gcp-auth` instead. Replace with: `\"com.permutive\" %% \"gcp-auth\" % \"0.2.0\"",
    since = "0.22.2"
  )
  def tokenProvider[F[_]: Functor](tokenProvider: TokenProvider[F]): RequestAuthorizer[F] =
    new RequestAuthorizer[F] {
      override def authorize(request: Request[F]): F[Request[F]] =
        tokenProvider.accessToken.map(token =>
          request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token.accessToken)))
        )
    }

  def noop[F[_]: Applicative]: RequestAuthorizer[F] = new RequestAuthorizer[F] {
    override def authorize(request: Request[F]): F[Request[F]] = Applicative[F].pure(request)
  }

}
