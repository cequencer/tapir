![tapir, or Typed API descRiptions](https://github.com/softwaremill/tapir/raw/master/banner.png)

[![Join the chat at https://gitter.im/softwaremill/tapir](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/softwaremill/tapir?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![CI](https://github.com/softwaremill/tapir/workflows/CI/badge.svg)](https://github.com/softwaremill/tapir/actions?query=workflow%3A%22CI%22)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.sttp.tapir/tapir-core_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.sttp.tapir/tapir-core_2.13)

With tapir, you can describe HTTP API endpoints as immutable Scala values. Each endpoint can contain a number of 
input parameters, error-output parameters, and normal-output parameters. An endpoint specification can be 
interpreted as:

* a server, given the "business logic": a function, which computes output parameters based on input parameters. 
  Currently supported: 
  * [Akka HTTP](https://tapir.softwaremill.com/en/latest/server/akkahttp.html) `Route`s/`Directive`s
  * [Http4s](https://tapir.softwaremill.com/en/latest/server/http4s.html) `HttpRoutes[F]`
  * [Netty](https://tapir.softwaremill.com/en/latest/server/netty.html)
  * [Finatra](https://tapir.softwaremill.com/en/latest/server/finatra.html) `FinatraRoute`
  * [Play](https://tapir.softwaremill.com/en/latest/server/play.html) `Route`
  * [ZIO Http](https://tapir.softwaremill.com/en/latest/server/ziohttp.html) `Http`
  * [Armeria](https://tapir.softwaremill.com/en/latest/server/armeria.html) `HttpServiceWithRoutes`
  * [aws](https://tapir.softwaremill.com/en/latest/server/aws.html) through Lambda/SAM/Terraform
* a client, which is a function from input parameters to output parameters.
  Currently supported:
  * [sttp](https://tapir.softwaremill.com/en/latest/client/sttp.html)
  * [Play](https://tapir.softwaremill.com/en/latest/client/play.html)
  * [http4s](https://tapir.softwaremill.com/en/latest/client/http4s.html)
* documentation. Currently supported: 
  * [OpenAPI](https://tapir.softwaremill.com/en/latest/docs/openapi.html)
  * [AsyncAPI](https://tapir.softwaremill.com/en/latest/docs/asyncapi.html)

## 🐣️ April 2022: Building an adopters page

Is your company already using tapir? We're building an "adopters" section in the documentation, and the more the merrier! It would be great to feature your company's logo, but in order to do that, we'll need written permission to avoid any legal misunderstandings.

Please email us at [tapir@softwaremill.com](mailto:tapir@softwaremill.com) from your company's email with a link to your logo (if we can use it, of course!) or with details who to kindly ask for permission to feature the logo in tapir's documentation. We'll handle the rest. 

We're seeing tapir's download numbers going steadily up; as we're nearing 1.0, the additional confidence boost for newcomers will help us to build tapir's ecosystem and make it thrive. Thank you! :)

## Teaser

```scala mdoc:compile-only
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import io.circe.generic.auto._

type Limit = Int
type AuthToken = String
case class BooksFromYear(genre: String, year: Int)
case class Book(title: String)


// Define an endpoint

val booksListing: PublicEndpoint[(BooksFromYear, Limit, AuthToken), String, List[Book], Any] = 
  endpoint
    .get
    .in(("books" / path[String]("genre") / path[Int]("year")).mapTo[BooksFromYear])
    .in(query[Limit]("limit").description("Maximum number of books to retrieve"))
    .in(header[AuthToken]("X-Auth-Token"))
    .errorOut(stringBody)
    .out(jsonBody[List[Book]])


// Generate OpenAPI documentation

import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.openapi.circe.yaml._

val docs = OpenAPIDocsInterpreter().toOpenAPI(booksListing, "My Bookshop", "1.0")
println(docs.toYaml)


// Convert to akka-http Route

import sttp.tapir.server.akkahttp.AkkaHttpServerInterpreter
import akka.http.scaladsl.server.Route
import scala.concurrent.Future

def bookListingLogic(bfy: BooksFromYear,
                     limit: Limit,
                     at: AuthToken): Future[Either[String, List[Book]]] =
  Future.successful(Right(List(Book("The Sorrows of Young Werther"))))
val booksListingRoute: Route = AkkaHttpServerInterpreter()
  .toRoute(booksListing.serverLogic((bookListingLogic _).tupled))


// Convert to sttp Request

import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.client3._

val booksListingRequest: Request[DecodeResult[Either[String, List[Book]]], Any] = 
  SttpClientInterpreter()
    .toRequest(booksListing, Some(uri"http://localhost:8080"))
    .apply((BooksFromYear("SF", 2016), 20, "xyz-abc-123"))
```

## Documentation

tapir documentation is available at [tapir.softwaremill.com](http://tapir.softwaremill.com).

## Quickstart with sbt

Add the following dependency:

```sbt
"com.softwaremill.sttp.tapir" %% "tapir-core" % "1.0.0-M6"
```

Partial unification is now enabled by default from Scala 2.13. However, if you're using Scala 2.12 or older, then 
you'll need partial unification enabled in the compiler (alternatively, you'll need to manually provide type 
arguments in some cases):

```sbt
scalacOptions += "-Ypartial-unification"
```

Then, import:

```scala
import sttp.tapir._
```

And finally, type `endpoint.` and see where auto-complete gets you!

---

Sidenote for scala 2.12.4 and higher: if you encounter an issue with compiling your project because of 
a `StackOverflowException` related to [this](https://github.com/scala/bug/issues/10604) scala bug, 
please increase your stack memory. Example:

```shell
sbt -J-Xss4M clean compile
```

## Other sttp projects

sttp is a family of Scala HTTP-related projects, and currently includes:

* [sttp client](https://github.com/softwaremill/sttp): the Scala HTTP client you always wanted!
* sttp tapir: this project
* [sttp model](https://github.com/softwaremill/sttp-model): simple HTTP model classes (used by client & tapir)

## Contributing

Tapir is an early stage project. Everything might change. All suggestions welcome :)

See the list of [issues](https://github.com/softwaremill/tapir/issues) and pick one! Or report your own.

If you are having doubts on the *why* or *how* something works, don't hesitate to ask a question on
[gitter](https://gitter.im/softwaremill/tapir) or via github. This probably means that the documentation, scaladocs or 
code is unclear and be improved for the benefit of all.

### Testing locally

The JS tests use [Gecko instead of Chrome](https://github.com/scala-js/scala-js-env-selenium/issues/119), although this
causes another problem: out of memory when running JS tests for multiple modules. Work-arounds:

* run only JVM tests for a specific Scala version using `testJVM2_13`
* test single JS projects
* use CI (GitHub Actions) to test all projects - the `.github/workflows/ci.yml` enumerates them one by one

You can test only server/client/doc/other projects using `testServers`, `testClients`, `testDocs` and `testOther`.

## Commercial Support

We offer commercial support for tapir and related technologies, as well as development services. [Contact us](https://softwaremill.com) to learn more about our offer!

## Copyright

Copyright (C) 2018-2021 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
